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
 *   <li>{@code TB_STATE_DIR}: bar cursor persistence directory (default {@code /app/data-futures/state})</li>
 *   <li>{@code DRY_RUN}: {@code true} to evaluate and log without dispatching orders (default {@code false})</li>
 * </ul>
 */
public final class IbkrLiveRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IbkrLiveRunner.class);

    /** Ports forbidden for the futures paper runner (live ports 4001/7496 and TWS desktop 7497 per spec §5 46-10 & §6.1). */
    public static final Set<Integer> FORBIDDEN_PORTS = Set.of(4001, 7496, 7497);
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
        boolean dryRun
    ) {
        public static RunnerConfig fromEnv(Map<String, String> env) {
            String host = getOrDefault(env, "IBKR_HOST", "127.0.0.1");
            int port = Integer.parseInt(getOrDefault(env, "IBKR_PORT", "4002"));
            String accountId = env.getOrDefault("IBKR_ACCOUNT_ID", "").trim();
            int clientId = Integer.parseInt(getOrDefault(env, "IBKR_CLIENT_ID", "11"));
            String symbol = getOrDefault(env, "FUTURES_SYMBOL", "MGC");
            String timeframe = getOrDefault(env, "FUTURES_TIMEFRAME", "30 mins");
            int intervalMinutes = Integer.parseInt(getOrDefault(env, "FUTURES_INTERVAL_MINUTES", "30"));
            String strategyName = env.getOrDefault("STRATEGY", "").trim();
            String allowOrders = env.getOrDefault(OrderTripwire.ENV_ALLOW_ORDERS, "").trim();
            Path stateDir = Paths.get(getOrDefault(env, "TB_STATE_DIR", "/app/data-futures/state"));
            boolean dryRun = Boolean.parseBoolean(getOrDefault(env, "DRY_RUN", "false"));
            return new RunnerConfig(
                host, port, accountId, clientId, symbol, timeframe,
                intervalMinutes, strategyName, allowOrders, stateDir, dryRun
            );
        }

        private static String getOrDefault(Map<String, String> env, String key, String defaultVal) {
            String v = env.get(key);
            return (v != null && !v.isBlank()) ? v.trim() : defaultVal;
        }
    }

    private final RunnerConfig config;
    private final Broker broker;
    private final Function<String, List<Bar>> barProvider;
    private final AutoCloseable barSourceCloseable;
    private final Clock clock;
    private final FillLogWriter fillLogWriter;
    private final double commissionPerContract;
    private final Map<String, Double> requestedPricesByOrderId = new ConcurrentHashMap<>();

    private IbkrGatewaySupervisor supervisor;
    private BarCloseScheduler scheduler;
    private SqStrategyEvaluator evaluator;
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
    }

    private static FillLogWriter resolveDefaultFillLogWriter(RunnerConfig config) {
        if (config == null || config.stateDir() == null) {
            return null;
        }
        Path parent = config.stateDir().getParent();
        Path fillsDir = parent != null ? parent.resolve("fills") : config.stateDir().resolve("fills");
        return FillLogWriter.forRun(fillsDir, config.strategyName(), config.symbol(), "paper");
    }

    /**
     * Validates configuration, resolves contract expiry, connects the broker, starts the supervisor,
     * runs state reconciliation, instantiates the strategy, loads the bar cursor, and starts the
     * {@link BarCloseScheduler} in the exact startup sequence specified by Story 46.10.
     */
    public void start() {
        // 1. Refuse live ports, a missing account, a multi-symbol list, and a timeframe/interval mismatch.
        validateConfig(config);
        log.info("[1/7] Startup port & env check OK: host={} port={} clientId={} account={} symbol={} timeframe='{}' interval={}m dryRun={}",
            config.host(), config.port(), config.clientId(), config.accountId(),
            config.symbol(), config.timeframe(), config.intervalMinutes(), config.dryRun());

        // 2. Resolve the contract: IbkrContractResolver.resolve(symbol, LocalDate.now(EASTERN_ZONE)).
        // Log the expiry and the days remaining. Refuse to start under 3 days.
        LocalDate todayEt = LocalDate.now(clock.withZone(CmeGlobexMarketCalendar.EASTERN_ZONE));
        IbkrContractResolver.IbkrContractDetails details = IbkrContractResolver.resolve(config.symbol(), todayEt);
        CmeFuturesCalendar.ContractSpec spec = CmeFuturesCalendar.activeContract(config.symbol(), todayEt);
        long daysRemaining = ChronoUnit.DAYS.between(todayEt, spec.expiryDate());
        log.info("[2/7] Resolved contract for {}: code={} exchange={} contractMonth={} expiryDate={} daysRemaining={}",
            config.symbol(), spec.contractCode(), details.exchange(),
            details.lastTradeDateOrContractMonth(), spec.expiryDate(), daysRemaining);
        if (daysRemaining < MIN_DAYS_TO_EXPIRY) {
            throw new IllegalStateException(
                "Refusing to start IbkrLiveRunner: contract " + spec.contractCode() + " expires on "
                + spec.expiryDate() + " (" + daysRemaining + " days remaining < minimum " + MIN_DAYS_TO_EXPIRY + " days)");
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

        // 5. Instantiate strategy from STRATEGY via the same getStrategyMap() reflection LiveStrategyRunner uses.
        Strategy strategy = SqStrategyEvaluator.instantiateStrategy(config.strategyName(), config.symbol());
        log.info("[5/7] Instantiated strategy: key='{}' class={} displayName='{}' symbol={}",
            config.strategyName(), strategy.getClass().getSimpleName(), strategy.name(), config.symbol());

        // 6. Load the bar cursor from TB_STATE_DIR.
        this.evaluator = new SqStrategyEvaluator(
            config.strategyName(),
            config.symbol(),
            strategy,
            config.stateDir(),
            config.dryRun()
        );
        log.info("[6/7] Bar cursor initialized from {}: lastProcessedBarTimestamp={}",
            config.stateDir(), evaluator.lastProcessedBarTimestamp());

        // 7. Build and start() the BarCloseScheduler.
        this.dispatcher = new IbkrOrderDispatcher(
            broker,
            config.strategyName(),
            config.symbol(),
            this::stop,
            config.dryRun()
        );
        SmallAccountMarginGuard marginGuard = new SmallAccountMarginGuard(
            SmallAccountMarginGuard.DEFAULT_MAX_MARGIN_UTILIZATION,
            1,
            5000.0
        );
        this.scheduler = new BarCloseScheduler(
            List.of(config.symbol()),
            config.intervalMinutes(),
            clock,
            barProvider,
            Map.of(config.symbol(), evaluator),
            () -> broker.getAccountState().equity(),
            broker::getPositions,
            marginGuard,
            dispatcher
        );
        this.scheduler.start();
        log.info("[7/7] BarCloseScheduler started for symbol={}: nextTriggerTime={}",
            config.symbol(), scheduler.nextTriggerTime());
    }

    public static void validateConfig(RunnerConfig config) {
        Objects.requireNonNull(config, "config is required");
        if (FORBIDDEN_PORTS.contains(config.port()) || config.port() <= 0) {
            throw new IllegalArgumentException(
                "Refusing forbidden or invalid IBKR_PORT=" + config.port()
                + ". Only paper gateway port (4002) is allowed; live/TWS ports (4001, 7496, 7497) are strictly refused.");
        }
        if (config.accountId() == null || config.accountId().isBlank()) {
            throw new IllegalArgumentException("IBKR_ACCOUNT_ID is required and must not be blank");
        }
        if (config.symbol() == null || config.symbol().isBlank() || config.symbol().contains(",")) {
            throw new IllegalArgumentException(
                "FUTURES_SYMBOL must name a single symbol (comma-separated lists are refused): '" + config.symbol() + "'");
        }
        if (config.strategyName() == null || config.strategyName().isBlank() || config.strategyName().contains(",") || config.strategyName().contains(" ")) {
            throw new IllegalArgumentException(
                "STRATEGY is required and must name a single strategy for the MVP: '" + config.strategyName() + "'");
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
            if (event.orderId() != null) {
                requestedPricesByOrderId.remove(event.orderId());
            }
            double qty = event.quantity() != null ? event.quantity() : 0.0;
            String side = event.side() != null ? event.side() : "";
            double slippage = FillLogRecord.computeSlippage(side, requestedPrice, filledPrice);
            double commission = Math.round(commissionPerContract * qty * 10_000.0) / 10_000.0;
            String ts = event.timestamp() != null ? event.timestamp().toString() : clock.instant().toString();
            FillLogRecord record = new FillLogRecord(
                config.strategyName(),
                config.symbol(),
                side,
                qty,
                requestedPrice,
                filledPrice,
                slippage,
                commission,
                ts,
                "paper"
            );
            fillLogWriter.append(record);
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
        if (evaluator != null) {
            try {
                evaluator.persistCursor();
            } catch (Exception e) {
                log.warn("Error persisting cursor on shutdown: {}", e.getMessage());
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
