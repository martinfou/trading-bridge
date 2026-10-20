package com.martinfou.trading.data.ibkr;

import com.ib.client.CommissionAndFeesReport;
import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import com.ib.client.DefaultEWrapper;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.Execution;
import com.ib.client.ExecutionFilter;
import com.ib.client.OrderCancel;
import com.ib.client.OrderState;
import com.ib.client.OrderType;
import com.ib.client.Types;
import com.martinfou.trading.core.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Real IB Gateway / TWS client over the official socket API ({@code TwsApi.jar}).
 *
 * <p>Threading: IB callbacks run on a dedicated reader thread and only touch concurrent
 * structures; request methods block the caller (never the reader) on {@link CompletableFuture}s
 * with {@link #requestTimeout}. Order listeners are invoked on the reader thread and must not block.</p>
 */
public final class EClientIbkrGatewayClient implements IbkrGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(EClientIbkrGatewayClient.class);

    static final String ACCOUNT_TAGS =
        "NetLiquidation,TotalCashValue,BuyingPower,AvailableFunds,InitMarginReq,MaintMarginReq";

    /** Reporting read: the base tags plus the per-currency ledger (exchange rates, realized and unrealized P&L). */
    static final String ACCOUNT_DETAIL_TAGS = ACCOUNT_TAGS + ",GrossPositionValue,$LEDGER:ALL";

    private static final DateTimeFormatter IB_EXEC_TIME = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");
    private static final DateTimeFormatter IB_EXEC_TIME_UTC = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss");

    /** Order-scoped codes that are warnings, not rejections. */
    static final Set<Integer> ORDER_WARNING_CODES = Set.of(
        161,   // cancel attempted when order not cancellable (already filled / cancelled)
        399,   // order message, e.g. "will not be placed until market open"
        404,   // shares for short sale not yet available; order held
        10147, // cancel: order id not found
        10148, // cancel: order cannot be cancelled in its state
        10167, // displaying delayed market data
        10349  // order TIF was set to DAY based on preset
    );

    /** Codes that mean the connection attempt itself failed. */
    static final Set<Integer> CONNECT_FAILURE_CODES = Set.of(326, 502, 504, 507);

    private static final Duration ACCOUNT_CACHE_TTL = Duration.ofSeconds(2);

    private final IbkrConnectionConfig config;
    private final Duration requestTimeout;
    final Wrapper wrapper = new Wrapper();

    private final List<Consumer<IbkrOrderUpdate>> listeners = new CopyOnWriteArrayList<>();
    private final Map<Integer, Tracked> tracked = new ConcurrentHashMap<>();
    private final Set<String> seenExecIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Qualified> contractCache = new ConcurrentHashMap<>();
    private final Map<Integer, ContractDetailsRequest> contractDetailsRequests = new ConcurrentHashMap<>();
    private final Map<Integer, AccountSummaryRequest> accountRequests = new ConcurrentHashMap<>();
    private final Map<Integer, PendingList<IbkrExecutionReport>> executionRequests = new ConcurrentHashMap<>();
    /** Commission reports by execId; they can arrive after execDetailsEnd, so they are kept for the day. */
    private final Map<String, CommissionAndFeesReport> commissionsByExecId = new ConcurrentHashMap<>();
    private final AtomicInteger nextOrderId = new AtomicInteger(-1);
    private final AtomicInteger nextReqId = new AtomicInteger(9_000);
    private final Object positionsLock = new Object();
    private final Object openOrdersLock = new Object();
    private final Object executionsLock = new Object();

    private volatile EClientSocket client;
    private volatile EJavaSignal signal;
    private volatile boolean connected;
    private volatile CompletableFuture<Integer> nextValidIdFuture = new CompletableFuture<>();
    private volatile CompletableFuture<String> managedAccountsFuture = new CompletableFuture<>();
    private volatile PendingList<IbkrPositionSnapshot> pendingPositions;
    private volatile PendingList<IbkrOpenOrder> pendingOpenOrders;
    private volatile IbkrAccountSnapshot cachedAccount;
    private volatile Instant cachedAccountAt = Instant.EPOCH;
    private volatile Instant uptimeStart;
    private volatile Instant lastMessageAt;
    private volatile List<String> managedAccounts = List.of();

    public EClientIbkrGatewayClient(IbkrConnectionConfig config) {
        this(config, Duration.ofSeconds(10));
    }

    public EClientIbkrGatewayClient(IbkrConnectionConfig config, Duration requestTimeout) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
    }

    // ------------------------------------------------------------------ session

    @Override
    public synchronized void connect() {
        if (isConnected()) {
            return;
        }
        nextValidIdFuture = new CompletableFuture<>();
        managedAccountsFuture = new CompletableFuture<>();
        EJavaSignal sig = new EJavaSignal();
        EClientSocket socket = new EClientSocket(wrapper, sig);
        this.signal = sig;
        this.client = socket;

        socket.eConnect(config.host(), config.port(), config.clientId());
        if (!socket.isConnected()) {
            throw new IllegalStateException(String.format(
                "Cannot connect to IB Gateway at %s:%d. Is it running and logged in, with "
                    + "Configure > Settings > API > 'Enable ActiveX and Socket Clients' on and the socket port set to %d?",
                config.host(), config.port(), config.port()));
        }
        EReader reader = new EReader(socket, sig);
        reader.start();
        Thread pump = new Thread(() -> pumpMessages(socket, sig, reader), "ibkr-msg-" + config.clientId());
        pump.setDaemon(true);
        pump.start();

        try {
            int firstId = await(nextValidIdFuture, "nextValidId");
            nextOrderId.set(firstId);
            String accounts = await(managedAccountsFuture, "managedAccounts");
            List<String> accountList = Arrays.stream(accounts.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
            managedAccounts = accountList;
            String expected = config.accountId();
            if (expected != null && !expected.isBlank() && !accountList.contains(expected)) {
                throw new IllegalStateException("IB session does not manage account " + mask(expected)
                    + " (logged-in accounts: " + accountList.stream().map(EClientIbkrGatewayClient::mask).toList()
                    + "). Check IBKR_ACCOUNT_ID / ibkr.properties and the paper vs live login.");
            }
            connected = true;
            uptimeStart = Instant.now();
            log.info("Connected to IB Gateway {}:{} clientId={} account={} serverVersion={} nextOrderId={}",
                config.host(), config.port(), config.clientId(), mask(expected), socket.serverVersion(), firstId);
            socket.reqOpenOrders(); // rebuild order tracking for orders placed before a reconnect
            emit(IbkrOrderUpdate.connection("CONNECTED", "IB Gateway " + config.host() + ":" + config.port()));
        } catch (RuntimeException e) {
            socket.eDisconnect();
            sig.issueSignal();
            throw e;
        }
    }

    private void pumpMessages(EClientSocket socket, EJavaSignal sig, EReader reader) {
        while (socket.isConnected()) {
            sig.waitForSignal();
            try {
                reader.processMsgs();
            } catch (Exception e) {
                log.warn("IB message processing error: {}", e.toString());
            }
        }
    }

    @Override
    public synchronized void disconnect() {
        connected = false;
        EClientSocket socket = client;
        if (socket != null && socket.isConnected()) {
            socket.eDisconnect();
        }
        if (signal != null) {
            signal.issueSignal();
        }
        RuntimeException closed = new IllegalStateException("IB Gateway disconnected");
        tracked.values().forEach(t -> t.ack.completeExceptionally(closed));
        contractDetailsRequests.values().forEach(r -> r.future.completeExceptionally(closed));
        accountRequests.values().forEach(r -> r.future.completeExceptionally(closed));
        executionRequests.values().forEach(r -> r.future.completeExceptionally(closed));
    }

    @Override
    public boolean isConnected() {
        EClientSocket socket = client;
        return connected && socket != null && socket.isConnected();
    }

    public Instant uptimeStart() {
        return uptimeStart;
    }

    public Instant lastMessageAt() {
        return lastMessageAt;
    }

    /** Accounts managed by the logged-in IB session (paper accounts start with {@code DU}). */
    public List<String> managedAccounts() {
        return managedAccounts;
    }

    @Override
    public void addOrderListener(Consumer<IbkrOrderUpdate> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    // ------------------------------------------------------------------ orders

    @Override
    public IbkrOrderAck placeOrder(IbkrOrderRequest request) {
        if (!isConnected()) {
            return IbkrOrderAck.rejected(null, "IB Gateway not connected");
        }
        Qualified q;
        try {
            q = qualify(request.symbol());
        } catch (RuntimeException e) {
            return IbkrOrderAck.rejected(null, "Contract resolution failed for " + request.symbol() + ": " + e.getMessage());
        }
        if (q.secType() != IbkrContractResolver.SecType.CASH && request.quantity() != Math.rint(request.quantity())) {
            return IbkrOrderAck.rejected(null,
                "IBKR requires whole-number quantity for " + q.secType() + " " + request.symbol() + ": " + request.quantity());
        }

        int legs = 1 + (request.takeProfit() > 0 ? 1 : 0) + (request.stopLoss() > 0 ? 1 : 0);
        int parentId = nextOrderId.getAndAdd(legs);
        List<com.ib.client.Order> orders = buildOrders(request, parentId, config.accountId(), q.minTick());

        List<Tracked> legsTracked = new ArrayList<>();
        for (com.ib.client.Order o : orders) {
            Tracked t = new Tracked(o.orderId(), o.orderRef(), q.tradingSymbol(), sideOf(o), request.quantity());
            tracked.put(o.orderId(), t);
            legsTracked.add(t);
        }
        EClientSocket socket = client;
        for (com.ib.client.Order o : orders) {
            socket.placeOrder(o.orderId(), q.contract(), o);
        }
        log.info("IBKR placeOrder id={} ref={} {} {} {} x{} lmt={} stp={} sl={} tp={}",
            parentId, request.clientOrderId(), request.side(), request.type(), q.tradingSymbol(), request.quantity(),
            request.limitPrice(), request.stopPrice(), request.stopLoss(), request.takeProfit());

        List<String> children = legsTracked.stream().skip(1).map(t -> String.valueOf(t.orderId)).toList();
        try {
            String status = legsTracked.get(0).ack.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return IbkrOrderAck.accepted(String.valueOf(parentId), children, status);
        } catch (ExecutionException e) {
            String reason = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return IbkrOrderAck.rejected(String.valueOf(parentId), reason);
        } catch (TimeoutException e) {
            // The order may well be live; never report it as rejected (would invite a duplicate retry).
            log.warn("IBKR order {} not acknowledged within {}; treating as pending", parentId, requestTimeout);
            return IbkrOrderAck.accepted(String.valueOf(parentId), children, "PendingAck");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return IbkrOrderAck.accepted(String.valueOf(parentId), children, "PendingAck");
        }
    }

    @Override
    public IbkrOrderAck cancelOrder(String brokerOrderId) {
        if (!isConnected()) {
            return IbkrOrderAck.rejected(brokerOrderId, "IB Gateway not connected");
        }
        int id;
        try {
            id = Integer.parseInt(brokerOrderId.trim());
        } catch (RuntimeException e) {
            return IbkrOrderAck.rejected(brokerOrderId, "Invalid IBKR order id: " + brokerOrderId);
        }
        client.cancelOrder(id, new OrderCancel());
        return IbkrOrderAck.accepted(brokerOrderId, List.of(), "PendingCancel");
    }

    /**
     * Builds the IB order(s): the parent plus optional take-profit (LMT) and stop-loss (STP) children.
     * Children carry {@code parentId} (IB links them as OCA), only the last leg transmits.
     */
    static List<com.ib.client.Order> buildOrders(IbkrOrderRequest req, int parentId, String account, double minTick) {
        List<com.ib.client.Order> out = new ArrayList<>();
        com.ib.client.Order parent = baseOrder(parentId, req.side(), req.quantity(), account, req.clientOrderId());
        switch (req.type()) {
            case MARKET -> parent.orderType(OrderType.MKT);
            case LIMIT -> {
                parent.orderType(OrderType.LMT);
                parent.lmtPrice(roundToTick(req.limitPrice(), minTick));
            }
            case STOP -> {
                parent.orderType(OrderType.STP);
                parent.auxPrice(roundToTick(req.stopPrice(), minTick));
            }
        }
        parent.tif(Types.TimeInForce.DAY);
        parent.outsideRth(req.outsideRth());
        out.add(parent);

        Order.Side exit = req.side() == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        int nextId = parentId + 1;
        String ref = req.clientOrderId() == null ? String.valueOf(parentId) : req.clientOrderId();
        if (req.takeProfit() > 0) {
            com.ib.client.Order tp = baseOrder(nextId++, exit, req.quantity(), account, ref + "-TP");
            tp.orderType(OrderType.LMT);
            tp.lmtPrice(roundToTick(req.takeProfit(), minTick));
            tp.parentId(parentId);
            tp.tif(Types.TimeInForce.GTC);
            out.add(tp);
        }
        if (req.stopLoss() > 0) {
            com.ib.client.Order sl = baseOrder(nextId, exit, req.quantity(), account, ref + "-SL");
            sl.orderType(OrderType.STP);
            sl.auxPrice(roundToTick(req.stopLoss(), minTick));
            sl.parentId(parentId);
            sl.tif(Types.TimeInForce.GTC);
            out.add(sl);
        }
        for (int i = 0; i < out.size(); i++) {
            out.get(i).transmit(i == out.size() - 1);
        }
        return out;
    }

    private static com.ib.client.Order baseOrder(int id, Order.Side side, double qty, String account, String ref) {
        com.ib.client.Order o = new com.ib.client.Order();
        o.orderId(id);
        o.action(side == Order.Side.BUY ? Types.Action.BUY : Types.Action.SELL);
        o.totalQuantity(Decimal.get(qty));
        if (account != null && !account.isBlank()) {
            o.account(account);
        }
        if (ref != null) {
            o.orderRef(ref);
        }
        return o;
    }

    static double roundToTick(double price, double minTick) {
        if (!(minTick > 0) || !(price > 0)) {
            return price;
        }
        BigDecimal tick = BigDecimal.valueOf(minTick);
        return BigDecimal.valueOf(price).divide(tick, 0, RoundingMode.HALF_UP).multiply(tick).doubleValue();
    }

    private static Order.Side sideOf(com.ib.client.Order o) {
        return o.action() == Types.Action.SELL ? Order.Side.SELL : Order.Side.BUY;
    }

    // ------------------------------------------------------------------ contracts

    Qualified qualify(String symbol) {
        IbkrContractResolver.IbkrContractDetails details =
            IbkrContractResolver.resolve(symbol, LocalDate.now(ZoneOffset.UTC));
        String key = details.secType() + "|" + details.symbol() + "|" + details.currency() + "|"
            + (details.lastTradeDateOrContractMonth() == null ? "" : details.lastTradeDateOrContractMonth());
        Qualified cached = contractCache.get(key);
        if (cached != null) {
            return cached;
        }
        Contract request = IbkrContractResolver.toIbContract(details);
        List<ContractDetails> matches = requestContractDetails(request);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("IB returned no contract for " + symbol);
        }
        ContractDetails chosen = matches.stream()
            .min(Comparator.comparing(cd -> cd.contract().lastTradeDateOrContractMonth() == null
                ? "" : cd.contract().lastTradeDateOrContractMonth()))
            .orElseThrow();
        Contract contract = chosen.contract();
        contract.exchange(details.exchange()); // keep SMART routing for stocks
        String tradingSymbol = details.secType() == IbkrContractResolver.SecType.CASH
            ? details.symbol() + "_" + details.currency()
            : details.symbol();
        Qualified q = new Qualified(contract, chosen.minTick(), tradingSymbol, details.secType());
        contractCache.put(key, q);
        log.info("IBKR qualified {} -> conId={} {} {} {} minTick={}", symbol, contract.conid(), contract.getSecType(),
            contract.localSymbol(), contract.lastTradeDateOrContractMonth(), chosen.minTick());
        return q;
    }

    private List<ContractDetails> requestContractDetails(Contract contract) {
        int reqId = nextReqId.incrementAndGet();
        ContractDetailsRequest r = new ContractDetailsRequest();
        contractDetailsRequests.put(reqId, r);
        try {
            client.reqContractDetails(reqId, contract);
            return await(r.future, "contractDetails");
        } finally {
            contractDetailsRequests.remove(reqId);
        }
    }

    // ------------------------------------------------------------------ account / positions / open orders

    @Override
    public IbkrAccountSnapshot fetchAccountSummary() {
        ensureConnected();
        IbkrAccountSnapshot cached = cachedAccount;
        if (cached != null && Duration.between(cachedAccountAt, Instant.now()).compareTo(ACCOUNT_CACHE_TTL) < 0) {
            return cached;
        }
        int reqId = nextReqId.incrementAndGet();
        AccountSummaryRequest r = new AccountSummaryRequest();
        accountRequests.put(reqId, r);
        try {
            client.reqAccountSummary(reqId, "All", ACCOUNT_TAGS);
            await(r.future, "accountSummary");
        } finally {
            accountRequests.remove(reqId);
            if (isConnected()) {
                client.cancelAccountSummary(reqId);
            }
        }
        String account = config.accountId();
        Map<String, String> values = account != null && r.values.containsKey(account)
            ? r.values.get(account)
            : r.values.values().stream().findFirst().orElse(Map.of());
        double equity = parse(values.get("NetLiquidation"));
        double cash = parse(values.get("TotalCashValue"));
        String currency = r.currency != null ? r.currency : "USD";
        IbkrAccountSnapshot snapshot = new IbkrAccountSnapshot(cash, equity, currency);
        cachedAccount = snapshot;
        cachedAccountAt = Instant.now();
        return snapshot;
    }

    @Override
    public List<IbkrPositionSnapshot> fetchOpenPositions() {
        ensureConnected();
        synchronized (positionsLock) {
            PendingList<IbkrPositionSnapshot> pending = new PendingList<>();
            pendingPositions = pending;
            try {
                client.reqPositions();
                return List.copyOf(await(pending.future, "positions"));
            } finally {
                pendingPositions = null;
                if (isConnected()) {
                    client.cancelPositions();
                }
            }
        }
    }

    @Override
    public List<IbkrOpenOrder> fetchOpenOrders() {
        return fetchOrders(false);
    }

    @Override
    public List<IbkrOpenOrder> fetchAllOpenOrders() {
        return fetchOrders(true);
    }

    private List<IbkrOpenOrder> fetchOrders(boolean wholeAccount) {
        ensureConnected();
        synchronized (openOrdersLock) {
            PendingList<IbkrOpenOrder> pending = new PendingList<>();
            pendingOpenOrders = pending;
            try {
                if (wholeAccount) {
                    client.reqAllOpenOrders();
                } else {
                    client.reqOpenOrders();
                }
                return List.copyOf(await(pending.future, "openOrders"));
            } finally {
                pendingOpenOrders = null;
            }
        }
    }

    // ------------------------------------------------------------------ read-only reporting (hermes-web)

    @Override
    public IbkrAccountDetails fetchAccountDetails() {
        ensureConnected();
        int reqId = nextReqId.incrementAndGet();
        AccountSummaryRequest r = new AccountSummaryRequest();
        accountRequests.put(reqId, r);
        try {
            client.reqAccountSummary(reqId, "All", ACCOUNT_DETAIL_TAGS);
            await(r.future, "accountSummary");
        } finally {
            accountRequests.remove(reqId);
            if (isConnected()) {
                client.cancelAccountSummary(reqId);
            }
        }
        String account = resolveReportingAccount(r.values.keySet());
        Map<String, String> tags = new HashMap<>(r.values.getOrDefault("All", Map.of()));
        Map<String, String> ledger = new HashMap<>(r.ledger.getOrDefault("All", Map.of()));
        if (account != null) {
            tags.putAll(r.values.getOrDefault(account, Map.of()));
            ledger.putAll(r.ledger.getOrDefault(account, Map.of()));
        }
        return toAccountDetails(account, r.currency, tags, ledger);
    }

    /** Configured account, else the session's single managed account, else any real (non-"All") key. */
    private String resolveReportingAccount(Set<String> reported) {
        if (config.accountId() != null && !config.accountId().isBlank()) {
            return config.accountId();
        }
        if (!managedAccounts.isEmpty()) {
            return managedAccounts.get(0);
        }
        return reported.stream().filter(a -> !"All".equalsIgnoreCase(a)).findFirst().orElse(null);
    }

    /**
     * Builds the reporting view from raw summary rows. Base tags come in the base currency; ledger rows are
     * keyed {@code tag|currency}, where {@code BASE} is the base-currency aggregate and a real currency code
     * carries that currency's own {@code ExchangeRate} into base.
     */
    static IbkrAccountDetails toAccountDetails(String account, String baseCurrency, Map<String, String> tags,
                                               Map<String, String> ledger) {
        String base = baseCurrency != null && !baseCurrency.isBlank() ? baseCurrency : ledgerBaseCurrency(ledger);
        Map<String, Double> values = new HashMap<>();
        for (String tag : List.of(IbkrAccountDetails.NET_LIQUIDATION, IbkrAccountDetails.TOTAL_CASH,
                IbkrAccountDetails.BUYING_POWER, IbkrAccountDetails.AVAILABLE_FUNDS, IbkrAccountDetails.INIT_MARGIN,
                IbkrAccountDetails.MAINT_MARGIN, IbkrAccountDetails.GROSS_POSITION_VALUE)) {
            Double v = parseOrNull(tags.get(tag));
            if (v != null) {
                values.put(tag, v);
            }
        }
        for (String tag : List.of(IbkrAccountDetails.UNREALIZED_PNL, IbkrAccountDetails.REALIZED_PNL)) {
            Double v = parseOrNull(ledger.get(tag + "|BASE"));
            if (v != null) {
                values.put(tag, v);
            }
        }
        Map<String, Double> rates = new HashMap<>();
        ledger.forEach((key, value) -> {
            int bar = key.indexOf('|');
            if (bar > 0 && key.startsWith("ExchangeRate|")) {
                String ccy = key.substring(bar + 1).toUpperCase();
                Double rate = parseOrNull(value);
                if (!"BASE".equals(ccy) && rate != null && rate > 0) {
                    rates.put(ccy, rate);
                }
            }
        });
        return new IbkrAccountDetails(account, base, values, rates);
    }

    private static String ledgerBaseCurrency(Map<String, String> ledger) {
        // The base currency is the one whose ExchangeRate into base is exactly 1.
        return ledger.entrySet().stream()
            .filter(e -> e.getKey().startsWith("ExchangeRate|") && !e.getKey().endsWith("|BASE"))
            .filter(e -> { Double v = parseOrNull(e.getValue()); return v != null && v == 1.0; })
            .map(e -> e.getKey().substring(e.getKey().indexOf('|') + 1))
            .findFirst().orElse("USD");
    }

    @Override
    public List<IbkrExecutionReport> fetchExecutions() {
        ensureConnected();
        synchronized (executionsLock) {
            int reqId = nextReqId.incrementAndGet();
            PendingList<IbkrExecutionReport> pending = new PendingList<>();
            executionRequests.put(reqId, pending);
            List<IbkrExecutionReport> raw;
            try {
                ExecutionFilter filter = new ExecutionFilter();
                if (config.accountId() != null && !config.accountId().isBlank()) {
                    filter.acctCode(config.accountId());
                }
                client.reqExecutions(reqId, filter);
                raw = await(pending.future, "executions");
            } finally {
                executionRequests.remove(reqId);
            }
            List<IbkrExecutionReport> joined = new ArrayList<>(raw.size());
            for (IbkrExecutionReport e : raw) {
                joined.add(withCommission(e, commissionsByExecId.get(e.execId())));
            }
            return List.copyOf(joined);
        }
    }

    static IbkrExecutionReport withCommission(IbkrExecutionReport e, CommissionAndFeesReport report) {
        if (report == null || report.commissionAndFees() == Double.MAX_VALUE) {
            return e;
        }
        double realized = report.realizedPNL() == Double.MAX_VALUE ? Double.NaN : report.realizedPNL();
        return e.withCommission(report.commissionAndFees(), report.currency(), realized);
    }

    /**
     * IB execution times are {@code yyyyMMdd HH:mm:ss [zone]} in the Gateway's time zone, or
     * {@code yyyyMMdd-HH:mm:ss} in UTC depending on the API settings. Unparseable means "now".
     */
    static Instant parseExecutionTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        String s = raw.trim();
        try {
            if (s.length() >= 17 && s.charAt(8) == '-') {
                return LocalDateTime.parse(s.substring(0, 17), IB_EXEC_TIME_UTC).toInstant(ZoneOffset.UTC);
            }
            String[] parts = s.split("\\s+");
            LocalDateTime local = LocalDateTime.parse(parts[0] + " " + parts[1], IB_EXEC_TIME);
            ZoneId zone = parts.length > 2 ? ZoneId.of(parts[2]) : ZoneId.systemDefault();
            return local.atZone(zone).toInstant();
        } catch (RuntimeException e) {
            log.debug("Unparseable IB execution time '{}': {}", raw, e.toString());
            return Instant.now();
        }
    }

    // ------------------------------------------------------------------ helpers

    private void ensureConnected() {
        if (!isConnected()) {
            throw new IllegalStateException("IB Gateway not connected");
        }
    }

    private <T> T await(CompletableFuture<T> future, String what) {
        try {
            return future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof RuntimeException re ? re : new IllegalStateException(cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out after " + requestTimeout.toSeconds() + "s waiting for IB " + what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for IB " + what, e);
        }
    }

    private void emit(IbkrOrderUpdate update) {
        for (Consumer<IbkrOrderUpdate> listener : listeners) {
            try {
                listener.accept(update);
            } catch (RuntimeException e) {
                log.warn("IBKR order listener failed: {}", e.toString());
            }
        }
    }

    private static double parse(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Like {@link #parse} but an absent or malformed value is {@code null}, never a fabricated 0. */
    private static Double parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            double d = Double.parseDouble(value);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double dec(Decimal d) {
        return d == null || !d.isValid() ? 0 : d.value().doubleValue();
    }

    static String mask(String accountId) {
        if (accountId == null || accountId.length() <= 4) {
            return "****";
        }
        return "****" + accountId.substring(accountId.length() - 4);
    }

    /** Test hook: register a tracked order without going through the socket. */
    void trackForTest(int orderId, String clientOrderId, String symbol, Order.Side side, double quantity) {
        tracked.put(orderId, new Tracked(orderId, clientOrderId, symbol, side, quantity));
    }

    CompletableFuture<String> ackFutureForTest(int orderId) {
        return tracked.get(orderId).ack;
    }

    record Qualified(Contract contract, double minTick, String tradingSymbol, IbkrContractResolver.SecType secType) {}

    static final class Tracked {
        final int orderId;
        final String clientOrderId;
        final String symbol;
        final Order.Side side;
        final double quantity;
        final CompletableFuture<String> ack = new CompletableFuture<>();
        final AtomicBoolean terminal = new AtomicBoolean();
        volatile String status = "PendingSubmit";
        volatile String lastError;

        Tracked(int orderId, String clientOrderId, String symbol, Order.Side side, double quantity) {
            this.orderId = orderId;
            this.clientOrderId = clientOrderId;
            this.symbol = symbol;
            this.side = side;
            this.quantity = quantity;
        }
    }

    private static final class ContractDetailsRequest {
        final List<ContractDetails> results = Collections.synchronizedList(new ArrayList<>());
        final CompletableFuture<List<ContractDetails>> future = new CompletableFuture<>();
    }

    private static final class AccountSummaryRequest {
        final Map<String, Map<String, String>> values = new ConcurrentHashMap<>();
        /** $LEDGER rows per account, keyed {@code tag|currency}. */
        final Map<String, Map<String, String>> ledger = new ConcurrentHashMap<>();
        volatile String currency;
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
    }

    private static final class PendingList<T> {
        final List<T> items = Collections.synchronizedList(new ArrayList<>());
        final CompletableFuture<List<T>> future = new CompletableFuture<>();
    }

    // ------------------------------------------------------------------ EWrapper callbacks (reader thread)

    final class Wrapper extends DefaultEWrapper {

        @Override
        public void nextValidId(int orderId) {
            lastMessageAt = Instant.now();
            nextOrderId.accumulateAndGet(orderId, Math::max);
            nextValidIdFuture.complete(orderId);
        }

        @Override
        public void managedAccounts(String accountsList) {
            managedAccountsFuture.complete(accountsList == null ? "" : accountsList);
        }

        @Override
        public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining, double avgFillPrice,
                                long permId, int parentId, double lastFillPrice, int clientId, String whyHeld,
                                double mktCapPrice) {
            lastMessageAt = Instant.now();
            Tracked t = tracked.get(orderId);
            if (t == null) {
                log.debug("orderStatus for untracked order {}: {}", orderId, status);
                return;
            }
            t.status = status;
            double filledQty = dec(filled);
            double remainingQty = dec(remaining);
            switch (status) {
                case "PreSubmitted", "Submitted", "Filled", "PendingCancel" -> t.ack.complete(status);
                case "Cancelled", "ApiCancelled" -> {
                    if (!t.ack.isDone() && t.lastError != null) {
                        reject(t, t.lastError);
                    } else {
                        t.ack.complete(status);
                        terminal(t, IbkrOrderUpdate.Kind.CANCELLED, filledQty, remainingQty, avgFillPrice,
                            t.lastError != null ? t.lastError : "Cancelled");
                    }
                }
                case "Inactive" -> reject(t, t.lastError != null ? t.lastError : "Order inactive (rejected by IB)");
                default -> { }
            }
            emit(new IbkrOrderUpdate(IbkrOrderUpdate.Kind.STATUS, Instant.now(), String.valueOf(orderId),
                t.clientOrderId, t.symbol, t.side, 0, lastFillPrice, filledQty, remainingQty, avgFillPrice,
                status, null, whyHeld));
        }

        @Override
        public void openOrder(int orderId, Contract contract, com.ib.client.Order order, OrderState orderState) {
            lastMessageAt = Instant.now();
            String symbol = IbkrContractResolver.toTradingSymbol(contract);
            Order.Side side = sideOf(order);
            double qty = dec(order.totalQuantity());
            tracked.computeIfAbsent(orderId, id -> new Tracked(id, order.orderRef(), symbol, side, qty));
            PendingList<IbkrOpenOrder> pending = pendingOpenOrders;
            if (pending != null) {
                pending.items.add(new IbkrOpenOrder(String.valueOf(orderId), order.orderRef(), symbol, side,
                    order.getOrderType(), qty, order.lmtPrice(), order.auxPrice(),
                    order.parentId() > 0 ? String.valueOf(order.parentId()) : null, orderState.getStatus()));
            }
        }

        @Override
        public void openOrderEnd() {
            PendingList<IbkrOpenOrder> pending = pendingOpenOrders;
            if (pending != null) {
                pending.future.complete(new ArrayList<>(pending.items));
            }
        }

        @Override
        public void execDetails(int reqId, Contract contract, Execution execution) {
            lastMessageAt = Instant.now();
            if (execution == null) {
                return;
            }
            PendingList<IbkrExecutionReport> report = reqId >= 0 ? executionRequests.get(reqId) : null;
            if (report != null) {
                // Reporting read (reqExecutions): collected only. Never emitted to order listeners and never
                // marked seen, so a live execution that arrives afterwards still reaches IbkrBroker.
                report.items.add(new IbkrExecutionReport(execution.execId(), execution.permId(),
                    execution.orderId(), execution.orderRef(), execution.acctNumber(),
                    IbkrContractResolver.toTradingSymbol(contract),
                    contract.getSecType(), contract.currency(),
                    "SLD".equalsIgnoreCase(execution.side()) ? Order.Side.SELL : Order.Side.BUY,
                    dec(execution.shares()), execution.price(), parseExecutionTime(execution.time()),
                    false, 0, null, Double.NaN));
                return;
            }
            if (!seenExecIds.add(execution.execId())) {
                return;
            }
            Tracked t = tracked.get(execution.orderId());
            double shares = dec(execution.shares());
            double cum = dec(execution.cumQty());
            Order.Side side = "SLD".equalsIgnoreCase(execution.side()) ? Order.Side.SELL : Order.Side.BUY;
            String symbol = t != null ? t.symbol : IbkrContractResolver.toTradingSymbol(contract);
            String ref = t != null ? t.clientOrderId : execution.orderRef();
            double remaining = t != null ? Math.max(0, t.quantity - cum) : 0;
            log.info("IBKR execution order={} ref={} {} {} x{} @ {} (cum {} avg {})", execution.orderId(), ref,
                side, symbol, shares, execution.price(), cum, execution.avgPrice());
            emit(new IbkrOrderUpdate(IbkrOrderUpdate.Kind.EXECUTION, Instant.now(),
                String.valueOf(execution.orderId()), ref, symbol, side, shares, execution.price(), cum, remaining,
                execution.avgPrice(), t != null ? t.status : null, execution.execId(), null));
        }

        @Override
        public void execDetailsEnd(int reqId) {
            PendingList<IbkrExecutionReport> report = executionRequests.get(reqId);
            if (report != null) {
                report.future.complete(new ArrayList<>(report.items));
            }
        }

        @Override
        public void commissionAndFeesReport(CommissionAndFeesReport report) {
            if (report != null && report.execId() != null) {
                commissionsByExecId.put(report.execId(), report);
            }
            if (report == null || report.commissionAndFees() == Double.MAX_VALUE) {
                return;
            }
            emit(new IbkrOrderUpdate(IbkrOrderUpdate.Kind.COMMISSION, Instant.now(), null, null, null, null,
                0, report.commissionAndFees(), 0, 0, 0, report.currency(), report.execId(), null));
        }

        @Override
        public void contractDetails(int reqId, ContractDetails contractDetails) {
            ContractDetailsRequest r = contractDetailsRequests.get(reqId);
            if (r != null) {
                r.results.add(contractDetails);
            }
        }

        @Override
        public void contractDetailsEnd(int reqId) {
            ContractDetailsRequest r = contractDetailsRequests.get(reqId);
            if (r != null) {
                r.future.complete(new ArrayList<>(r.results));
            }
        }

        @Override
        public void accountSummary(int reqId, String account, String tag, String value, String currency) {
            AccountSummaryRequest r = accountRequests.get(reqId);
            if (r != null) {
                r.values.computeIfAbsent(account, a -> new ConcurrentHashMap<>()).put(tag, value);
                if (currency != null && !currency.isBlank()) {
                    r.ledger.computeIfAbsent(account, a -> new ConcurrentHashMap<>()).put(tag + "|" + currency, value);
                }
                if ("NetLiquidation".equals(tag) && currency != null && !currency.isBlank()) {
                    r.currency = currency;
                }
            }
        }

        @Override
        public void accountSummaryEnd(int reqId) {
            AccountSummaryRequest r = accountRequests.get(reqId);
            if (r != null) {
                r.future.complete(Boolean.TRUE);
            }
        }

        @Override
        public void position(String account, Contract contract, Decimal pos, double avgCost) {
            PendingList<IbkrPositionSnapshot> pending = pendingPositions;
            if (pending == null) {
                return;
            }
            String expected = config.accountId();
            if (expected != null && !expected.isBlank() && !expected.equals(account)) {
                return;
            }
            double qty = dec(pos);
            if (qty == 0) {
                return;
            }
            pending.items.add(new IbkrPositionSnapshot(
                IbkrContractResolver.toTradingSymbol(contract),
                qty > 0 ? Order.Side.BUY : Order.Side.SELL,
                Math.abs(qty),
                IbkrContractResolver.perUnitPrice(contract, avgCost)));
        }

        @Override
        public void positionEnd() {
            PendingList<IbkrPositionSnapshot> pending = pendingPositions;
            if (pending != null) {
                pending.future.complete(new ArrayList<>(pending.items));
            }
        }

        @Override
        public void error(Exception e) {
            log.warn("IB API exception: {}", e.toString());
        }

        @Override
        public void error(String str) {
            log.warn("IB API error: {}", str);
        }

        @Override
        public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
            lastMessageAt = Instant.now();
            if (id >= 0) {
                ContractDetailsRequest cd = contractDetailsRequests.get(id);
                if (cd != null) {
                    cd.future.completeExceptionally(new IllegalArgumentException("IB " + errorCode + ": " + errorMsg));
                    return;
                }
                AccountSummaryRequest ar = accountRequests.get(id);
                if (ar != null) {
                    ar.future.completeExceptionally(new IllegalStateException("IB " + errorCode + ": " + errorMsg));
                    return;
                }
                PendingList<IbkrExecutionReport> er = executionRequests.get(id);
                if (er != null) {
                    er.future.completeExceptionally(new IllegalStateException("IB " + errorCode + ": " + errorMsg));
                    return;
                }
                Tracked t = tracked.get(id);
                if (t != null) {
                    onOrderError(t, errorCode, errorMsg);
                    return;
                }
            }
            onSessionMessage(errorCode, errorMsg);
        }

        private void onOrderError(Tracked t, int code, String msg) {
            String text = code + ": " + msg;
            if (code == 202) { // order cancelled; orderStatus(Cancelled) normally follows
                if (!t.ack.isDone() && t.lastError != null) {
                    reject(t, t.lastError);
                } else {
                    t.ack.complete("Cancelled");
                    terminal(t, IbkrOrderUpdate.Kind.CANCELLED, 0, 0, 0, msg);
                }
            } else if (ORDER_WARNING_CODES.contains(code) || (code >= 2100 && code < 2200)) {
                log.warn("IBKR order {} warning {}", t.orderId, text);
            } else {
                log.warn("IBKR order {} rejected {}", t.orderId, text);
                t.lastError = text;
                reject(t, text);
            }
        }

        private void onSessionMessage(int code, String msg) {
            switch (code) {
                case 1100 -> {
                    log.error("IB Gateway lost connectivity to IBKR servers: {}", msg);
                    emit(IbkrOrderUpdate.connection("LOST", msg));
                }
                case 1101, 1102 -> {
                    log.warn("IB Gateway connectivity restored ({}): {}", code, msg);
                    emit(IbkrOrderUpdate.connection("RESTORED", msg));
                    EClientSocket socket = client;
                    if (socket != null && socket.isConnected()) {
                        socket.reqOpenOrders();
                    }
                }
                default -> {
                    if (CONNECT_FAILURE_CODES.contains(code)) {
                        IllegalStateException failure = new IllegalStateException("IB " + code + ": " + msg);
                        nextValidIdFuture.completeExceptionally(failure);
                        managedAccountsFuture.completeExceptionally(failure);
                        log.error("IB connection error {}: {}", code, msg);
                    } else if (code >= 2100 && code < 2200) {
                        log.debug("IB info {}: {}", code, msg);
                    } else {
                        log.warn("IB message {}: {}", code, msg);
                    }
                }
            }
        }

        @Override
        public void connectionClosed() {
            boolean wasConnected = connected;
            connected = false;
            log.warn("IB Gateway connection closed");
            if (wasConnected) {
                emit(IbkrOrderUpdate.connection("CLOSED", "IB Gateway socket closed"));
            }
        }

        private void reject(Tracked t, String reason) {
            t.ack.completeExceptionally(new IllegalStateException(reason));
            terminal(t, IbkrOrderUpdate.Kind.REJECTED, 0, 0, 0, reason);
        }

        private void terminal(Tracked t, IbkrOrderUpdate.Kind kind, double filled, double remaining, double avg,
                              String message) {
            if (t.terminal.compareAndSet(false, true)) {
                emit(new IbkrOrderUpdate(kind, Instant.now(), String.valueOf(t.orderId), t.clientOrderId, t.symbol,
                    t.side, t.quantity, 0, filled, remaining, avg, t.status, null, message));
            }
        }
    }
}
