package com.martinfou.trading.data.ibkr;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.DefaultEWrapper;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.Execution;
import com.martinfou.trading.core.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * IBKR TWS / IB Gateway session via the official {@code com.ib.client} API (v1045.01).
 *
 * <p>Migrated 2026-08-24 from the raw-TCP placeholder to {@link EClientSocket}. This class is the
 * <b>only</b> thing that speaks the IBKR wire protocol; everything above it (the broker, the kill
 * switch, the runtime) consumes the {@link IbkrGatewayClient} interface and never touches
 * {@code com.ib.client} directly.</p>
 *
 * <p>Concurrency contract (must stay true):</p>
 * <ul>
 *   <li>{@link #connect()} performs a synchronous handshake and returns only when the Gateway has
 *       acked the connection ({@code connectAck}) or the attempt has failed (connection refused /
 *       server error). It throws {@link IllegalStateException} on failure — fail-loud, never a
 *       silent half-connected client.</li>
 *   <li>Fills NEVER arrive synchronously: {@link #placeMarketOrder} only <em>accepts</em> the
 *       order. The fill (or reject) is delivered later, on the IBKR reader thread, through the
 *       execution listeners registered via {@link #addExecutionListener}.</li>
 *   <li>{@link #fetchAccountSummary()} / {@link #fetchOpenPositions()} are fail-closed: if the
 *       Gateway does not answer within the timeout they return the last known (or zeroed) state,
 *       never a fabricated healthy account.</li>
 * </ul>
 */
public final class TcpIbkrGatewayClient implements IbkrGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(TcpIbkrGatewayClient.class);

    private static final Duration ACCOUNT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration POSITIONS_TIMEOUT = Duration.ofSeconds(3);

    private final IbkrConnectionConfig config;
    private final GatewayWrapper wrapper;
    private final IbkrAccountCache cache;

    private volatile EClientSocket client;
    private volatile EJavaSignal signal;
    private volatile EReader reader;
    private volatile boolean connected;
    private volatile String lastError;

    // Account / position capture state (reset per request).
    private volatile CountDownLatch accountDownloadLatch;
    private volatile CountDownLatch positionsLatch;
    private final List<IbkrPositionSnapshot> positions = new CopyOnWriteArrayList<>();

    // Order correlation: IBKR does NOT echo the clientTag in execDetails/orderStatus, so the
    // orderId -> clientTag map is the only bridge between an application order and its execution.
    private final Map<Integer, String> orderIdToClientTag = new ConcurrentHashMap<>();
    private final Set<String> notifiedExecIds = ConcurrentHashMap.newKeySet();
    private final Set<Integer> filledOrderIds = ConcurrentHashMap.newKeySet();
    private final List<Consumer<IbkrExecution>> executionListeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger orderIdCounter = new AtomicInteger(1000);

    public TcpIbkrGatewayClient(IbkrConnectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
        this.wrapper = new GatewayWrapper();
        this.cache = new IbkrAccountCache(config.accountId());
    }

    @Override
    public void connect() {
        EClientSocket socket = this.client;
        if (socket != null && socket.isConnected()) {
            return; // already connected
        }

        EJavaSignal signal = new EJavaSignal();
        this.signal = signal;
        this.connected = false;
        this.lastError = null;

        // Synchronous handshake: eConnect blocks until the Gateway replies with the server
        // version (fires connectAck) or the socket connect fails (fires error + connectionClosed).
        EClientSocket newClient = new EClientSocket(wrapper, signal);
        this.client = newClient;
        newClient.eConnect(config.host(), config.port(), config.clientId());

        if (!newClient.isConnected()) {
            String reason = lastError != null ? lastError : "no Gateway response";
            throw new IllegalStateException(
                "Failed to connect to IB Gateway at " + config.host() + ":" + config.port() + " — " + reason);
        }
        this.connected = true;
        startReaderThread(newClient, signal);
        log.info("IB Gateway connected at {}:{} (clientId={}, account={})",
            config.host(), config.port(), config.clientId(), maskAccount(config.accountId()));
    }

    private void startReaderThread(EClientSocket socket, EJavaSignal signal) {
        EReader r = new EReader(socket, signal);
        this.reader = r;
        r.start();
        Thread processor = new Thread(() -> {
            while (socket.isConnected()) {
                signal.waitForSignal();
                try {
                    r.processMsgs();
                } catch (IOException e) {
                    log.warn("IB Gateway reader error: {}", e.getMessage());
                    break;
                }
            }
        }, "ibkr-msg-processor");
        processor.setDaemon(true);
        processor.start();
    }

    @Override
    public void disconnect() {
        connected = false;
        EClientSocket socket = this.client;
        this.client = null;
        this.reader = null;
        this.signal = null;
        if (socket != null) {
            socket.eDisconnect();
        }
    }

    @Override
    public boolean isConnected() {
        EClientSocket socket = this.client;
        return connected && socket != null && socket.isConnected();
    }

    @Override
    public IbkrMarketOrderResult placeMarketOrder(String symbol, double quantity, Order.Side side, String clientTag) {
        if (!isConnected()) {
            return IbkrMarketOrderResult.failure("IB Gateway not connected");
        }
        if (symbol == null || symbol.isBlank()) {
            return IbkrMarketOrderResult.failure("symbol is required");
        }
        if (side == null) {
            return IbkrMarketOrderResult.failure("side is required");
        }

        int orderId = generateOrderId();
        trackOrder(orderId, clientTag);
        Contract contract = buildContract(symbol);
        com.ib.client.Order order = buildMarketOrder(orderId, quantity, side, config.accountId());
        try {
            client.placeOrder(orderId, contract, order);
        } catch (RuntimeException e) {
            orderIdToClientTag.remove(orderId);
            return IbkrMarketOrderResult.failure("placeOrder failed: " + e.getMessage());
        }
        // Accepted, not filled: the fill (execDetails) arrives asynchronously on the reader thread.
        return new IbkrMarketOrderResult(true, Integer.toString(orderId), null, null, null);
    }

    @Override
    public void addExecutionListener(Consumer<IbkrExecution> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
        executionListeners.add(listener);
    }

    @Override
    public IbkrAccountSnapshot fetchAccountSummary() {
        if (!isConnected()) {
            return failClosedAccount();
        }
        this.accountDownloadLatch = new CountDownLatch(1);
        client.reqAccountUpdates(true, accountCode());
        await(accountDownloadLatch, ACCOUNT_TIMEOUT);
        IbkrAccountCache.AccountSummary summary = cache.snapshot();
        return new IbkrAccountSnapshot(summary.totalCashBalance(), summary.netLiquidation(), "USD");
    }

    @Override
    public List<IbkrPositionSnapshot> fetchOpenPositions() {
        if (!isConnected()) {
            return List.of();
        }
        positions.clear();
        this.positionsLatch = new CountDownLatch(1);
        client.reqPositions();
        await(positionsLatch, POSITIONS_TIMEOUT);
        return List.copyOf(positions);
    }

    private IbkrAccountSnapshot failClosedAccount() {
        IbkrAccountCache.AccountSummary summary = cache.snapshot();
        return new IbkrAccountSnapshot(summary.totalCashBalance(), summary.netLiquidation(), "USD");
    }

    private String accountCode() {
        String account = config.accountId();
        return account != null ? account : "";
    }

    private int generateOrderId() {
        return orderIdCounter.getAndIncrement();
    }

    // ------------------------------------------------------------------
    // Package-private test seams (unit tests exercise these without a live Gateway).
    // ------------------------------------------------------------------

    void trackOrder(int orderId, String clientTag) {
        orderIdToClientTag.put(orderId, clientTag);
    }

    String clientTagForOrderId(int orderId) {
        return orderIdToClientTag.get(orderId);
    }

    static Contract buildContract(String symbol) {
        return buildContract(IbkrContractResolver.resolve(symbol));
    }

    static Contract buildContract(IbkrContractResolver.IbkrContractDetails details) {
        Contract contract = new Contract();
        contract.symbol(details.symbol());
        contract.secType(details.secType().name());
        contract.exchange(details.exchange());
        if (details.primaryExchange() != null) {
            contract.primaryExch(details.primaryExchange());
        }
        contract.currency(details.currency());
        if (details.multiplier() > 0) {
            contract.multiplier(formatMultiplier(details.multiplier()));
        }
        if (details.lastTradeDateOrContractMonth() != null) {
            contract.lastTradeDateOrContractMonth(details.lastTradeDateOrContractMonth());
        }
        return contract;
    }

    static com.ib.client.Order buildMarketOrder(int orderId, double quantity, Order.Side side, String accountId) {
        com.ib.client.Order order = new com.ib.client.Order();
        order.orderId(orderId);
        order.action(side == Order.Side.BUY ? "BUY" : "SELL");
        order.totalQuantity(Decimal.get(quantity));
        order.orderType("MKT");
        order.transmit(true);
        if (accountId != null && !accountId.isBlank()) {
            order.account(accountId);
        }
        return order;
    }

    static IbkrExecution mapExecution(Execution execution, String symbol, String clientTag) {
        String orderId = execution.orderId() != 0 ? Integer.toString(execution.orderId()) : null;
        Order.Side side = mapSide(execution.side());
        double quantity = toDouble(execution.shares());
        return IbkrExecution.filled(orderId, execution.execId(), clientTag, symbol, side, quantity, execution.price());
    }

    static Order.Side mapSide(String ibSide) {
        if ("BOT".equalsIgnoreCase(ibSide)) {
            return Order.Side.BUY;
        }
        if ("SLD".equalsIgnoreCase(ibSide)) {
            return Order.Side.SELL;
        }
        return null;
    }

    static String formatMultiplier(double multiplier) {
        if (multiplier == Math.rint(multiplier)) {
            return String.valueOf((long) multiplier);
        }
        return String.valueOf(multiplier);
    }

    static double toDouble(Decimal decimal) {
        return decimal == null || !decimal.isValid() ? 0.0 : decimal.value().doubleValue();
    }

    private static void await(CountDownLatch latch, Duration timeout) {
        try {
            latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String maskAccount(String accountId) {
        if (accountId == null || accountId.length() <= 4) {
            return "****";
        }
        return "****" + accountId.substring(accountId.length() - 4);
    }

    // ------------------------------------------------------------------
    // EWrapper adapter — extends DefaultEWrapper (the v1045 contract requires it, never
    // `implements EWrapper`: ~82 abstract ProtoBuf methods have empty impls only in
    // DefaultEWrapper).
    // ------------------------------------------------------------------

    private final class GatewayWrapper extends DefaultEWrapper {

        @Override
        public void connectAck() {
            connected = true;
            log.debug("IB Gateway connectAck");
        }

        @Override
        public void connectionClosed() {
            connected = false;
            log.warn("IB Gateway connection closed");
        }

        @Override
        public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
            lastError = errorCode + ": " + errorMsg;
            log.warn("IB Gateway error id={} code={}: {}", id, errorCode, errorMsg);
            if (errorCode == 502 || errorCode == 503 || errorCode == 504) {
                connected = false; // CONNECT_FAIL / UPDATE_TWS / ALREADY_CONNECTED
            }
        }

        @Override
        public void error(Exception e) {
            lastError = e.getMessage();
            log.warn("IB Gateway exception: {}", e.toString());
        }

        @Override
        public void error(String str) {
            lastError = str;
            log.warn("IB Gateway error: {}", str);
        }

        @Override
        public void updateAccountValue(String key, String value, String currency, String accountName) {
            cache.updateFromString(key, value);
        }

        @Override
        public void accountDownloadEnd(String accountName) {
            CountDownLatch latch = accountDownloadLatch;
            if (latch != null) {
                latch.countDown();
            }
        }

        @Override
        public void position(String account, Contract contract, Decimal pos, double avgCost) {
            if (pos == null || pos.isZero()) {
                return;
            }
            double qty = toDouble(pos);
            if (qty <= 0) {
                return;
            }
            Order.Side side = pos.value().signum() > 0 ? Order.Side.BUY : Order.Side.SELL;
            positions.add(new IbkrPositionSnapshot(contract.symbol(), side, Math.abs(qty), avgCost));
        }

        @Override
        public void positionEnd() {
            CountDownLatch latch = positionsLatch;
            if (latch != null) {
                latch.countDown();
            }
        }

        @Override
        public void execDetails(int reqId, Contract contract, Execution execution) {
            String clientTag = orderIdToClientTag.get(execution.orderId());
            String symbol = contract != null ? contract.symbol() : null;
            IbkrExecution mapped = mapExecution(execution, symbol, clientTag);
            if (mapped.execId() != null && !notifiedExecIds.add(mapped.execId())) {
                log.debug("Ignoring duplicate execDetails for execId={}", mapped.execId());
                return;
            }
            if (mapped.isFill()) {
                filledOrderIds.add(execution.orderId());
            }
            notifyExecution(mapped);
        }

        @Override
        public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining,
                                double avgFillPrice, long permId, int parentId, double lastFillPrice,
                                int clientId, String whyHeld, double mktCapPrice) {
            if (status == null) {
                return;
            }
            String clientTag = orderIdToClientTag.get(orderId);
            String upper = status.toUpperCase();
            if (upper.contains("CANCELLED") || upper.contains("CANCELED") || upper.contains("REJECTED")) {
                String reason = whyHeld != null && !whyHeld.isBlank() ? whyHeld : status;
                notifyExecution(IbkrExecution.rejected(Integer.toString(orderId), clientTag, null, reason));
            } else if (upper.contains("FILLED") && !filledOrderIds.contains(orderId)) {
                // Rare: orderStatus reports Filled without a preceding execDetails. Emit the fill
                // from orderStatus data so the broker is not left waiting on a phantom execution.
                double qty = toDouble(filled);
                notifyExecution(IbkrExecution.filled(
                    Integer.toString(orderId), null, clientTag, null, null, qty, avgFillPrice));
            }
        }

        @Override
        public void nextValidId(int orderId) {
            if (orderId > 0) {
                orderIdCounter.accumulateAndGet(orderId, Math::max);
            }
        }
    }

    private void notifyExecution(IbkrExecution execution) {
        for (Consumer<IbkrExecution> listener : executionListeners) {
            listener.accept(execution);
        }
    }
}
