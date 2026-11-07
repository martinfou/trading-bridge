package com.martinfou.trading.runtime.ibkr;

import com.martinfou.trading.broker.AccountState;
import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.broker.BrokerEvent;
import com.martinfou.trading.broker.BrokerEventType;
import com.martinfou.trading.broker.IbkrBroker;
import com.martinfou.trading.broker.IbkrGatewaySupervisor;
import com.martinfou.trading.broker.IbkrStateReconciler;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.CmeFuturesCalendar;
import com.martinfou.trading.core.CmeGlobexMarketCalendar;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.core.SmallAccountMarginGuard;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.guardrails.OrderTripwire;
import com.martinfou.trading.data.ibkr.IbkrBarSource;
import com.martinfou.trading.data.ibkr.IbkrConnectionConfig;
import com.martinfou.trading.data.ibkr.IbkrContractResolver;
import com.martinfou.trading.data.ibkr.TcpIbkrGatewayClient;
import com.martinfou.trading.runtime.BarCloseScheduler;
import com.martinfou.trading.runtime.engine.FillLogRecord;
import com.martinfou.trading.runtime.engine.FillLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Composition root for the IBKR Live/Paper Futures Lane (Story 46.10).
 *
 * <h2>Environment Contract</h2>
 * <ul>
 *   <li>{@code IBKR_HOST}: gateway host (default {@code 127.0.0.1})</li>
 *   <li>{@code IBKR_PORT}: gateway port (default {@code 4002}; refuses {@code 4001}, {@code 7496}, {@code 7497})</li>
 *   <li>{@code IBKR_ACCOUNT_ID}: required paper account id</li>
 *   <li>{@code IBKR_CLIENT_ID}: runner client id (default {@code 11}; {@link IbkrBarSource} uses {@code clientId + 1})</li>
 *   <li>{@code FUTURES_SYMBOL}: single futures root symbol (default {@code MGC}; refuses comma-separated lists)</li>
 *   <li>{@code FUTURES_TIMEFRAME}: IBKR bar size (default {@code 30 mins}; must pair with {@code FUTURES_INTERVAL_MINUTES})</li>
 *   <li>{@code FUTURES_INTERVAL_MINUTES}: scheduler cadence in minutes (default {@code 30})</li>
 *   <li>{@code STRATEGY}: single strategy short name resolved via {@code LiveStrategyRunner.getStrategyMap()}</li>
 *   <li>{@code TB_ALLOW_ORDERS}: must be {@code 1} (or {@code true}) unless {@code DRY_RUN=true}</li>
 *   <li>{@code TB_STATE_DIR}: bar cursor persistence directory (default {@code /app/data/state/futures})</li>
 *   <li>{@code DRY_RUN}: {@code true} to evaluate and log without dispatching orders (default {@code false})</li>
 * </ul>
 */
public final class IbkrLiveRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IbkrLiveRunner.class);

    /** Ports forbidden for the futures paper runner (live ports 4001/7496 and TWS desktop 7497 per spec §5 46-10 & §6.1). */
    public static final Set<Integer> FORBIDDEN_PORTS = Set.of(4001, 7496, 7497);
    /** Ports forbidden for the futures live runner (paper port 4002 and TWS desktop 7496/7497). */
    public static final Set<Integer> FORBIDDEN_LIVE_PORTS = Set.of(4002, 7496, 7497);
    public static final int MIN_DAYS_TO_EXPIRY = 3;
    public static final int DEFAULT_WARMUP_BAR_COUNT = 120;
    public static final double DEFAULT_IBKR_MGC_COMMISSION = 0.62;

    public record RunnerConfig(
        String host,
        int port,
        String accountId,
        int clientId,
        String symbol,
        String timeframe,
        int intervalMinutes,
        String strategyName,
        String allowOrdersFlag,
        Path stateDir,
        boolean dryRun,
        String ibkrEnv
    ) {
        public RunnerConfig(
            String host,
            int port,
            String accountId,
            int clientId,
            String symbol,
            String timeframe,
            int intervalMinutes,
            String strategyName,
            String allowOrdersFlag,
            Path stateDir,
            boolean dryRun
        ) {
            this(
                host, port, accountId, clientId, symbol, timeframe,
                intervalMinutes, strategyName, allowOrdersFlag, stateDir, dryRun, "paper"
            );
        }

        public static RunnerConfig fromEnv(Map<String, String> env) {
            String ibkrEnv = getOrDefault(env, "IBKR_ENV", "paper").toLowerCase();
            boolean isLive = "live".equals(ibkrEnv) || "prod".equals(ibkrEnv) || "production".equals(ibkrEnv);
            String host = getOrDefault(env, "IBKR_HOST", "127.0.0.1");
            int port = Integer.parseInt(getOrDefault(env, "IBKR_PORT", isLive ? "4001" : "4002"));
            String accountId = env.getOrDefault("IBKR_ACCOUNT_ID", "").trim();
            int clientId = Integer.parseInt(getOrDefault(env, "IBKR_CLIENT_ID", isLive ? "21" : "11"));
            String symbol = getOrDefault(env, "FUTURES_SYMBOL", "MGC");
            String timeframe = getOrDefault(env, "FUTURES_TIMEFRAME", "30 mins");
            int intervalMinutes = Integer.parseInt(getOrDefault(env, "FUTURES_INTERVAL_MINUTES", "30"));
            String strategyName = env.getOrDefault("STRATEGY", "").trim();
            String allowOrders = env.getOrDefault(OrderTripwire.ENV_ALLOW_ORDERS, "").trim();
            Path stateDir = Paths.get(getOrDefault(env, "TB_STATE_DIR", "/app/data/state/futures"));
            boolean dryRun = Boolean.parseBoolean(getOrDefault(env, "DRY_RUN", "false"));
            return new RunnerConfig(
                host, port, accountId, clientId, symbol, timeframe,
                intervalMinutes, strategyName, allowOrders, stateDir, dryRun, ibkrEnv
            );
        }

        private static String getOrDefault(Map<String, String> env, String key, String defaultVal) {
            String v = env.get(key);
            return (v != null && !v.isBlank()) ? v.trim() : defaultVal;
        }

        public boolean isLiveEnv() {
            if (ibkrEnv == null) return false;
            String normalized = ibkrEnv.trim().toLowerCase();
            return "live".equals(normalized) || "prod".equals(normalized) || "production".equals(normalized);
        }

        public List<String> symbols() {
            if (symbol == null || symbol.isBlank()) {
                return List.of();
            }
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            for (String part : symbol.split(",")) {
                String trimmed = part.trim().toUpperCase();
                if (!trimmed.isEmpty() && !out.contains(trimmed)) {
                    out.add(trimmed);
                }
            }
            return List.copyOf(out);
        }

        public List<String> strategyNames() {
            if (strategyName == null || strategyName.isBlank()) {
                return List.of();
            }
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            for (String part : strategyName.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
            return List.copyOf(out);
        }
    }

    private final RunnerConfig config;
    private final Broker broker;
    private final Function<String, List<Bar>> barProvider;
    private final AutoCloseable barSourceCloseable;
    private final Clock clock;
    private final FillLogWriter fillLogWriter;
    private final Map<String, FillLogWriter> fillLogWritersByStrategy = new ConcurrentHashMap<>();
    private final double commissionPerContract;
    private final Map<String, Double> requestedPricesByOrderId = new ConcurrentHashMap<>();
    private final Map<String, String> strategyByOrderId = new ConcurrentHashMap<>();
    private volatile String lastDispatchedStrategy;

    private IbkrGatewaySupervisor supervisor;
    private BarCloseScheduler scheduler;
    private SqStrategyEvaluator evaluator;
    private final List<SqStrategyEvaluator> evaluators = new java.util.ArrayList<>();
    private IbkrOrderDispatcher dispatcher;

    public IbkrLiveRunner(
        RunnerConfig config,
        Broker broker,
        Function<String, List<Bar>> barProvider,
        AutoCloseable barSourceCloseable,
        Clock clock
    ) {
        this(
            config,
            broker,
            barProvider,
            barSourceCloseable,
            clock,
            resolveDefaultFillLogWriter(config),
            DEFAULT_IBKR_MGC_COMMISSION
        );
    }

    public IbkrLiveRunner(
        RunnerConfig config,
        Broker broker,
        Function<String, List<Bar>> barProvider,
        AutoCloseable barSourceCloseable,
        Clock clock,
        FillLogWriter fillLogWriter,
        double commissionPerContract
    ) {
        this.config = Objects.requireNonNull(config, "config is required");
        this.broker = Objects.requireNonNull(broker, "broker is required");
        this.barProvider = Objects.requireNonNull(barProvider, "barProvider is required");
        this.barSourceCloseable = barSourceCloseable;
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.fillLogWriter = fillLogWriter;
        this.commissionPerContract = commissionPerContract;
        if (config.stateDir() != null) {
            Path fillsDir = resolveFillsDir(config.stateDir());
            String laneLabel = config.isLiveEnv() ? "live" : "paper";
            String defaultSym = config.symbols().isEmpty() ? config.symbol() : config.symbols().get(0);
            for (String s : config.strategyNames()) {
                String stratSym = config.symbols().size() <= 1
                    ? defaultSym
                    : SqStrategyEvaluator.resolveSymbolForStrategy(s, defaultSym);
                fillLogWritersByStrategy.put(s, FillLogWriter.forRun(fillsDir, s, stratSym, laneLabel));
            }
        }
    }

    static Path resolveFillsDir(Path stateDir) {
        String envFills = System.getenv("TB_FUTURES_FILLS_DIR");
        if (envFills != null && !envFills.isBlank()) {
            return Paths.get(envFills.trim());
        }
        if (stateDir == null) {
            return FillLogWriter.DEFAULT_FILLS_DIR;
        }
        Path parent = stateDir.getParent();
        if (parent != null && parent.getFileName() != null
                && "state".equals(parent.getFileName().toString())
                && parent.getParent() != null) {
            return parent.getParent().resolve("fills");
        }
        return parent != null ? parent.resolve("fills") : stateDir.resolve("fills");
    }

    private static FillLogWriter resolveDefaultFillLogWriter(RunnerConfig config) {
        if (config == null || config.stateDir() == null) {
            return null;
        }
        Path fillsDir = resolveFillsDir(config.stateDir());
        String primaryStrategy = config.strategyNames().isEmpty() ? config.strategyName() : config.strategyNames().get(0);
        String defaultSym = config.symbols().isEmpty() ? config.symbol() : config.symbols().get(0);
        String laneLabel = config.isLiveEnv() ? "live" : "paper";
        return FillLogWriter.forRun(fillsDir, primaryStrategy, defaultSym, laneLabel);
    }

    /**
     * Validates configuration, resolves contract expiry, connects the broker, starts the supervisor,
     * runs state reconciliation, instantiates the strategy, loads the bar cursor, and starts the
     * {@link BarCloseScheduler} in the exact startup sequence specified by Story 46.10.
     */
    public void start() {
        // 1. Refuse forbidden ports, a missing account, unmapped symbols, and a timeframe/interval mismatch.
        validateConfig(config);
        List<String> targetSymbols = config.symbols();
        log.info("[1/7] Startup port & env check OK: env={} host={} port={} clientId={} account={} symbols={} timeframe='{}' interval={}m dryRun={}",
            config.isLiveEnv() ? "live" : "paper", config.host(), config.port(), config.clientId(), config.accountId(),
            targetSymbols, config.timeframe(), config.intervalMinutes(), config.dryRun());

        // 2. Resolve the contract(s): IbkrContractResolver.resolve(symbol, LocalDate.now(EASTERN_ZONE)).
        // Log the expiry and the days remaining. Refuse to start under 3 days.
        LocalDate todayEt = LocalDate.now(clock.withZone(CmeGlobexMarketCalendar.EASTERN_ZONE));
        for (String sym : targetSymbols) {
            IbkrContractResolver.IbkrContractDetails details = IbkrContractResolver.resolve(sym, todayEt);
            CmeFuturesCalendar.ContractSpec spec = CmeFuturesCalendar.activeContract(sym, todayEt);
            long daysRemaining = ChronoUnit.DAYS.between(todayEt, spec.expiryDate());
            log.info("[2/7] Resolved contract for {}: code={} exchange={} contractMonth={} expiryDate={} daysRemaining={}",
                sym, spec.contractCode(), details.exchange(),
                details.lastTradeDateOrContractMonth(), spec.expiryDate(), daysRemaining);
            if (daysRemaining < MIN_DAYS_TO_EXPIRY) {
                throw new IllegalStateException(
                    "Refusing to start IbkrLiveRunner: contract " + spec.contractCode() + " expires on "
                    + spec.expiryDate() + " (" + daysRemaining + " days remaining < minimum " + MIN_DAYS_TO_EXPIRY + " days)");
            }
        }

        // 3. Connect IbkrBroker. Log getAccountState().
        broker.addEventListener(this::onBrokerEvent);
        broker.connect();
        AccountState accountState = broker.getAccountState();
        log.info("[3/7] Connected IbkrBroker. Account state: balance={} equity={} currency={}",
            accountState.balance(), accountState.equity(), accountState.currency());

        // 4. IbkrGatewaySupervisor up. Run one IbkrStateReconciler pass and log any broker position the runner does not expect.
        AtomicReference<List<Position>> localExpectedPositions = new AtomicReference<>(List.of());
        IbkrStateReconciler reconciler = new IbkrStateReconciler(
            broker::getPositions,
            localExpectedPositions::get,
            reconciled -> {
                for (Position p : reconciled) {
                    log.warn("[4/7] Unexpected open broker position found during reconciliation: symbol={} side={} qty={} entryPrice={}",
                        p.symbol(), p.side(), p.quantity(), p.entryPrice());
                }
                localExpectedPositions.set(reconciled);
            }
        );
        IbkrStateReconciler.ReconciliationReport report = reconciler.reconcile();
        log.info("[4/7] Reconciliation complete: brokerPositions={} localPositions={} matched={} inSync={}",
            report.brokerPositionCount(), report.localPositionCount(), report.matchedPositions(), report.inSync());

        this.supervisor = new IbkrGatewaySupervisor(
            () -> {
                if (!broker.isConnected()) {
                    broker.connect();
                }
            },
            broker::disconnect,
            reconciler::reconcile,
            broker::isConnected
        );
        this.supervisor.start();

        // 5 & 6. Instantiate strategy(ies) from STRATEGY via LiveStrategyRunner.getStrategyMap() and load bar cursors.
        this.evaluators.clear();
        String defaultSym = targetSymbols.get(0);
        Map<String, List<SqStrategyEvaluator>> evaluatorsBySymbol = new java.util.LinkedHashMap<>();
        for (String sym : targetSymbols) {
            evaluatorsBySymbol.put(sym, new java.util.ArrayList<>());
        }
        for (String stratName : config.strategyNames()) {
            String stratSym = targetSymbols.size() == 1
                ? defaultSym
                : SqStrategyEvaluator.resolveSymbolForStrategy(stratName, defaultSym);
            if (!evaluatorsBySymbol.containsKey(stratSym)) {
                stratSym = defaultSym;
            }
            Strategy strategy = SqStrategyEvaluator.instantiateStrategy(stratName, stratSym);
            log.info("[5/7] Instantiated strategy: key='{}' class={} displayName='{}' symbol={}",
                stratName, strategy.getClass().getSimpleName(), strategy.name(), stratSym);

            SqStrategyEvaluator ev = new SqStrategyEvaluator(
                stratName,
                stratSym,
                strategy,
                config.stateDir(),
                config.dryRun()
            );
            log.info("[6/7] Bar cursor initialized for {} from {}: lastProcessedBarTimestamp={}",
                stratName, config.stateDir(), ev.lastProcessedBarTimestamp());
            this.evaluators.add(ev);
            evaluatorsBySymbol.computeIfAbsent(stratSym, k -> new java.util.ArrayList<>()).add(ev);
        }
        this.evaluator = this.evaluators.isEmpty() ? null : this.evaluators.get(0);

        // 7. Build and start() the BarCloseScheduler.
        String primaryStrategy = config.strategyNames().get(0);
        this.dispatcher = new IbkrOrderDispatcher(
            broker,
            primaryStrategy,
            defaultSym,
            this::stop,
            config.dryRun(),
            (orderId, stratName) -> {
                if (orderId != null && stratName != null) {
                    strategyByOrderId.put(orderId, stratName);
                    lastDispatchedStrategy = stratName;
                }
            }
        );
        SmallAccountMarginGuard marginGuard = new SmallAccountMarginGuard(
            SmallAccountMarginGuard.DEFAULT_MAX_MARGIN_UTILIZATION,
            Math.max(1, targetSymbols.size()),
            5000.0
        );
        Map<String, Function<List<Bar>, List<com.martinfou.trading.core.Order>>> schedulerEvaluators = new java.util.LinkedHashMap<>();
        for (String sym : targetSymbols) {
            List<SqStrategyEvaluator> symEvaluators = evaluatorsBySymbol.getOrDefault(sym, List.of());
            schedulerEvaluators.put(sym, bars -> {
                java.util.ArrayList<com.martinfou.trading.core.Order> orders = new java.util.ArrayList<>();
                for (SqStrategyEvaluator ev : symEvaluators) {
                    List<com.martinfou.trading.core.Order> generated = ev.apply(bars);
                    if (generated != null && !generated.isEmpty()) {
                        orders.addAll(generated);
                    }
                }
                return orders;
            });
        }
        this.scheduler = new BarCloseScheduler(
            targetSymbols,
            config.intervalMinutes(),
            clock,
            barProvider,
            schedulerEvaluators,
            () -> broker.getAccountState().equity(),
            broker::getPositions,
            marginGuard,
            dispatcher
        );
        this.scheduler.start();
        log.info("[7/7] BarCloseScheduler started for symbols={} strategies={}: nextTriggerTime={}",
            targetSymbols, config.strategyNames(), scheduler.nextTriggerTime());
    }

    public static void validateConfig(RunnerConfig config) {
        Objects.requireNonNull(config, "config is required");
        if (config.isLiveEnv()) {
            if (FORBIDDEN_LIVE_PORTS.contains(config.port()) || config.port() <= 0) {
                throw new IllegalArgumentException(
                    "Refusing forbidden or invalid IBKR_PORT=" + config.port()
                    + " for IBKR_ENV=live. Only live gateway port (4001) is allowed; paper/TWS ports (4002, 7496, 7497) are strictly refused.");
            }
        } else {
            if (FORBIDDEN_PORTS.contains(config.port()) || config.port() <= 0) {
                throw new IllegalArgumentException(
                    "Refusing forbidden or invalid IBKR_PORT=" + config.port()
                    + ". Only paper gateway port (4002) is allowed; live/TWS ports (4001, 7496, 7497) are strictly refused.");
            }
        }
        if (config.accountId() == null || config.accountId().isBlank()) {
            throw new IllegalArgumentException("IBKR_ACCOUNT_ID is required and must not be blank");
        }
        if (config.isLiveEnv() && config.accountId().trim().toUpperCase().startsWith("DU")) {
            throw new IllegalArgumentException(
                "Refusing paper account '" + config.accountId() + "' when IBKR_ENV=live (live accounts do not start with 'DU')");
        }
        List<String> syms = config.symbols();
        if (syms.isEmpty()) {
            throw new IllegalArgumentException(
                "FUTURES_SYMBOL is required and must name at least one symbol: '" + config.symbol() + "'");
        }
        List<String> stratNames = config.strategyNames();
        if (stratNames.isEmpty()) {
            throw new IllegalArgumentException(
                "STRATEGY is required and must name at least one strategy: '" + config.strategyName() + "'");
        }
        for (String s : stratNames) {
            if (s.isBlank() || s.contains(" ")) {
                throw new IllegalArgumentException(
                    "Invalid strategy name in STRATEGY: '" + s + "'");
            }
        }
        if (syms.size() > 1) {
            for (String sym : syms) {
                boolean hasMatchingStrategy = stratNames.stream()
                    .anyMatch(st -> sym.equalsIgnoreCase(SqStrategyEvaluator.resolveSymbolForStrategy(st, "")));
                if (!hasMatchingStrategy) {
                    throw new IllegalArgumentException(
                        "FUTURES_SYMBOL lists '" + sym + "' in '" + config.symbol()
                        + "', but no strategy in STRATEGY (" + stratNames + ") resolves to " + sym);
                }
            }
        }
        if (config.intervalMinutes() <= 0) {
            throw new IllegalArgumentException("FUTURES_INTERVAL_MINUTES must be > 0");
        }
        Duration tfDuration = IbkrBarSource.parseTimeframeDuration(config.timeframe());
        if (tfDuration.toMinutes() != config.intervalMinutes()) {
            throw new IllegalArgumentException(
                "Timeframe/interval mismatch: FUTURES_TIMEFRAME='" + config.timeframe()
                + "' (" + tfDuration.toMinutes() + " mins) != FUTURES_INTERVAL_MINUTES=" + config.intervalMinutes());
        }
        if (!config.dryRun()) {
            String flag = config.allowOrdersFlag() != null ? config.allowOrdersFlag().trim().toLowerCase() : "";
            if (!"1".equals(flag) && !"true".equals(flag)) {
                throw new IllegalArgumentException(
                    "TB_ALLOW_ORDERS must be set to '1' (or 'true') when DRY_RUN=false");
            }
        }
    }

    public BarCloseScheduler scheduler() {
        return scheduler;
    }

    public SqStrategyEvaluator evaluator() {
        return evaluator;
    }

    public List<SqStrategyEvaluator> evaluators() {
        return List.copyOf(evaluators);
    }

    public IbkrOrderDispatcher dispatcher() {
        return dispatcher;
    }

    public FillLogWriter fillLogWriter() {
        return fillLogWriter;
    }

    private void onBrokerEvent(BrokerEvent event) {
        if (event == null || fillLogWriter == null) {
            return;
        }
        if (event.type() == BrokerEventType.ORDER_SUBMITTED) {
            if (event.orderId() != null && event.price() != null) {
                requestedPricesByOrderId.put(event.orderId(), event.price());
            }
            return;
        }
        if (event.type() == BrokerEventType.REJECT) {
            if (event.orderId() != null) {
                requestedPricesByOrderId.remove(event.orderId());
                strategyByOrderId.remove(event.orderId());
            }
            return;
        }
        if (event.type() == BrokerEventType.FILL) {
            double filledPrice = event.price() != null ? event.price() : 0.0;
            double requestedPrice = event.orderId() != null
                ? requestedPricesByOrderId.getOrDefault(event.orderId(), filledPrice)
                : filledPrice;
            if (requestedPrice <= 0.0) {
                requestedPrice = filledPrice;
            }
            String resolvedStrat = null;
            if (event.orderId() != null) {
                requestedPricesByOrderId.remove(event.orderId());
                resolvedStrat = strategyByOrderId.remove(event.orderId());
            }
            if (resolvedStrat == null || resolvedStrat.isBlank()) {
                resolvedStrat = lastDispatchedStrategy != null
                    ? lastDispatchedStrategy
                    : (config.strategyNames().isEmpty() ? config.strategyName() : config.strategyNames().get(0));
            }
            double qty = event.quantity() != null ? event.quantity() : 0.0;
            String side = event.side() != null ? event.side() : "";
            double slippage = FillLogRecord.computeSlippage(side, requestedPrice, filledPrice);
            double commission = Math.round(commissionPerContract * qty * 10_000.0) / 10_000.0;
            String ts = event.timestamp() != null ? event.timestamp().toString() : clock.instant().toString();
            String defaultSym = config.symbols().isEmpty() ? config.symbol() : config.symbols().get(0);
            String fillSymbol = (event.symbol() != null && !event.symbol().isBlank())
                ? event.symbol()
                : (config.symbols().size() <= 1 ? defaultSym : SqStrategyEvaluator.resolveSymbolForStrategy(resolvedStrat, defaultSym));
            String laneLabel = config.isLiveEnv() ? "live" : "paper";
            FillLogRecord record = new FillLogRecord(
                resolvedStrat,
                fillSymbol,
                side,
                qty,
                requestedPrice,
                filledPrice,
                slippage,
                commission,
                ts,
                laneLabel
            );
            FillLogWriter writer = fillLogWritersByStrategy.getOrDefault(resolvedStrat, fillLogWriter);
            writer.append(record);
        }
    }

    public synchronized void stop() {
        log.info("Stopping IbkrLiveRunner...");
        if (scheduler != null) {
            try {
                scheduler.close();
            } catch (Exception e) {
                log.warn("Error closing scheduler: {}", e.getMessage());
            }
        }
        for (SqStrategyEvaluator ev : evaluators) {
            try {
                ev.persistCursor();
            } catch (Exception e) {
                log.warn("Error persisting cursor on shutdown for {}: {}", ev.strategyName(), e.getMessage());
            }
        }
        if (supervisor != null) {
            try {
                supervisor.close();
            } catch (Exception e) {
                log.warn("Error closing supervisor: {}", e.getMessage());
            }
        }
        if (barSourceCloseable != null) {
            try {
                barSourceCloseable.close();
            } catch (Exception e) {
                log.warn("Error closing bar source: {}", e.getMessage());
            }
        }
        try {
            broker.disconnect();
        } catch (Exception e) {
            log.warn("Error disconnecting broker: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        stop();
    }

    public static void main(String[] args) {
        try {
            RunnerConfig cfg = RunnerConfig.fromEnv(System.getenv());
            validateConfig(cfg);

            IbkrConnectionConfig brokerConnCfg = new IbkrConnectionConfig(
                cfg.host(), cfg.port(), cfg.clientId(), cfg.accountId()
            );
            // Distinct clientId for IbkrBarSource so IBKR never rejects with error 326
            IbkrConnectionConfig barConnCfg = new IbkrConnectionConfig(
                cfg.host(), cfg.port(), cfg.clientId() + 1, cfg.accountId()
            );

            IbkrBroker broker = new IbkrBroker(new TcpIbkrGatewayClient(brokerConnCfg));
            IbkrBarSource barSource = new IbkrBarSource(barConnCfg);

            IbkrLiveRunner runner = new IbkrLiveRunner(
                cfg,
                broker,
                sym -> barSource.latestBars(sym, cfg.timeframe(), DEFAULT_WARMUP_BAR_COUNT),
                barSource,
                Clock.systemUTC()
            );

            CountDownLatch keepAlive = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                runner.close();
                keepAlive.countDown();
            }, "ibkr-live-runner-shutdown"));

            runner.start();
            keepAlive.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Fatal error in IbkrLiveRunner: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
