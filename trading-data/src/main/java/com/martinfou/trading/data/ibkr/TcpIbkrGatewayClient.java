package com.martinfou.trading.data.ibkr;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.DefaultEWrapper;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.Execution;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.guardrails.OrderTripwire;
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
    /**
     * Open positions keyed by contract, so a repeated callback for the same contract REPLACES its row
     * instead of adding a second one. A duplicate row would make the kill switch submit two closing
     * orders for one position.
     */
    private final Map<String, IbkrPositionSnapshot> positionsByContract = new ConcurrentHashMap<>();
    /** Last POSITION answer the venue actually completed; never cleared by a failed query. */
    private volatile List<IbkrPositionSnapshot> lastKnownPositions = List.of();
    /** False until the venue actually answers a positions request: an empty list alone means nothing. */
    private volatile boolean lastPositionQueryConfirmed;

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
        OrderTripwire.checkOrderAllowed(symbol, String.valueOf(quantity), "TcpIbkrGatewayClient.placeMarketOrder",
                config.host() + ":" + config.port());
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
            // Not "flat": unknown. An empty list here would let a kill switch confirm a liquidation
            // it never verified, so hand back the last state the venue did report and say so.
            lastPositionQueryConfirmed = false;
            return lastKnownPositions;
        }
        positionsByContract.clear();
        this.positionsLatch = new CountDownLatch(1);
        lastPositionQueryConfirmed = false;
        client.reqPositions();
        if (!await(positionsLatch, POSITIONS_TIMEOUT)) {
            // Same reasoning for a silent Gateway: a timeout must never read as "no positions".
            // Cancel so a late positionEnd() cannot trip the latch of the NEXT query.
            try {
                client.cancelPositions();
            } catch (RuntimeException e) {
                log.debug("cancelPositions after timeout failed: {}", e.toString());
            }
            log.warn("IBKR positions query did not complete within {}; last known state ({} row(s)) returned UNCONFIRMED",
                POSITIONS_TIMEOUT, lastKnownPositions.size());
            return lastKnownPositions;
        }
        List<IbkrPositionSnapshot> snapshot = List.copyOf(positionsByContract.values());
        lastKnownPositions = snapshot;
        lastPositionQueryConfirmed = true;
        return snapshot;
    }

    @Override
    public boolean lastPositionQueryConfirmed() {
        return lastPositionQueryConfirmed;
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

    /**
     * IBKR reports {@code avgCost} with the contract multiplier already applied for derivatives
     * (measured 2026-10-21: 1 MES filled at 7766.5 came back as 38833.11, i.e. x5). A position
     * price must be a PRICE, so divide it back out; anything downstream that sizes or computes
     * P&L off this number would otherwise be off by the multiplier.
     */
    static double normalizeAveragePrice(double avgCost, String multiplier) {
        if (multiplier == null || multiplier.isBlank()) {
            return avgCost;
        }
        try {
            double m = Double.parseDouble(multiplier.trim());
            return m > 0.0 && m != 1.0 ? avgCost / m : avgCost;
        } catch (NumberFormatException e) {
            return avgCost;
        }
    }

    /**
     * Maps one IBKR position row to a snapshot. The SIGN carries the side: a negative quantity is a
     * SHORT, and it must survive (a dropped short is an invisible exposure).
     */
    static IbkrPositionSnapshot toSnapshot(Contract contract, double signedQuantity, double avgCost) {
        Order.Side side = signedQuantity > 0 ? Order.Side.BUY : Order.Side.SELL;
        String symbol = contract != null ? contract.symbol() : null;
        if ((symbol == null || symbol.isBlank()) && contract != null) {
            // A bare contract may carry only the local symbol (MESZ6); without any symbol the closing
            // order would have no instrument. conid is the last resort: it still identifies the trade.
            symbol = contract.localSymbol();
            if (symbol == null || symbol.isBlank()) {
                symbol = contract.conid() > 0 ? String.valueOf(contract.conid()) : null;
            }
        }
        String multiplier = contract != null ? contract.multiplier() : null;
        if (multiplier == null || multiplier.isBlank()) {
            // IBKR's position callback does not always carry the multiplier (the Contract it hands
            // back is minimal). Without it a derivative's avgCost stays 5x or 100x too large, so fall
            // back to the contract registry rather than silently returning money as a price.
            multiplier = FuturesRegistry.find(symbol)
                .map(c -> String.valueOf(c.multiplier()))
                .orElse(null);
        }
        return new IbkrPositionSnapshot(symbol, side, Math.abs(signedQuantity),
            normalizeAveragePrice(avgCost, multiplier));
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

    private static boolean await(CountDownLatch latch, Duration timeout) {
        try {
            return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
            if (contract == null) {
                log.warn("IBKR position callback without a contract ignored (qty={})", pos);
                return;
            }
            double qty = toDouble(pos);
            if (qty == 0.0) {
                return;
            }
            // A SHORT position arrives as a negative quantity: dropping it here made every short
            // invisible to the broker adapter, so the kill switch saw a flat account while the
            // venue still held the position.
            // Keyed by account + instrument so a repeated callback REPLACES the row instead of adding
            // a duplicate (a duplicate would submit two closing orders for one position).
            positionsByContract.put(positionKey(account, contract), toSnapshot(contract, qty, avgCost));
        }

        /**
         * Identity of one open position: the account plus the instrument. conid is authoritative when
         * IBKR supplies it; otherwise the full contract description is used so two different
         * instruments that merely share a symbol cannot overwrite each other. Namespaced so a conid can
         * never collide with a textual key.
         */
        private static String positionKey(String account, Contract contract) {
            String who = account == null || account.isBlank() ? "-" : account;
            if (contract.conid() > 0) {
                return who + "|conid:" + contract.conid();
            }
            return who + "|sym:" + nullSafe(contract.symbol()) + "/" + nullSafe(contract.localSymbol())
                + "/" + nullSafe(contract.secType()) + "/" + nullSafe(contract.lastTradeDateOrContractMonth())
                + "/" + nullSafe(contract.right()) + "/" + contract.strike()
                + "/" + nullSafe(contract.currency()) + "/" + nullSafe(contract.multiplier())
                + "/" + nullSafe(contract.tradingClass());
        }

        private static String nullSafe(com.ib.client.Types.SecType value) {
            return value == null ? "" : value.toString();
        }

        private static String nullSafe(com.ib.client.Types.Right value) {
            return value == null ? "" : value.toString();
        }

        private static String nullSafe(String value) {
            return value == null ? "" : value;
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
