package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.BacktestResultPayload;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyCatalog;
import com.martinfou.trading.data.DukascopyDownloader;
import com.martinfou.trading.data.BarStore;

import java.io.IOException;
import java.time.Instant;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.file.Path;

/**
 * Starts strategy runs asynchronously and persists {@link RunEvent} records to the {@link EventStore}.
 * Lifecycle-only orchestration — promote, gaps, and deployments are handled by other services.
 */
public class RunManager implements RunLifecycle, AutoCloseable {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunManager.class);
    private static final Map<String, java.util.concurrent.locks.ReentrantLock> downloadLocks = new ConcurrentHashMap<>();

    public record StartRunRequest(
        String strategyId,
        String symbol,
        String mode,
        BarSourceResolver.BarsSource barsSource,
        Double capital,
        Double lotSize,
        Double commissionPerTrade,
        Double slippagePct,
        String executionLabel,
        String brokerAccountId,
        String dataTimeframe,
        String strategyTimeframe,
        Double maxDailyDrawdownPct,
        Double dailyLossLimitPct,
        Double weeklyLossLimitPct,
        Boolean force
    ) {
        public StartRunRequest(
            String strategyId,
            String symbol,
            String mode,
            BarSourceResolver.BarsSource barsSource,
            Double capital,
            Double lotSize,
            Double commissionPerTrade,
            Double slippagePct,
            String executionLabel,
            String brokerAccountId,
            String dataTimeframe,
            String strategyTimeframe,
            Double maxDailyDrawdownPct,
            Double dailyLossLimitPct,
            Double weeklyLossLimitPct
        ) {
            this(strategyId, symbol, mode, barsSource, capital, lotSize, commissionPerTrade, slippagePct,
                executionLabel, brokerAccountId, dataTimeframe, strategyTimeframe, maxDailyDrawdownPct, dailyLossLimitPct, weeklyLossLimitPct, false);
        }

        public StartRunRequest(
            String strategyId,
            String symbol,
            String mode,
            BarSourceResolver.BarsSource barsSource,
            Double capital,
            Double lotSize,
            Double commissionPerTrade,
            Double slippagePct,
            String executionLabel,
            String brokerAccountId
        ) {
            this(strategyId, symbol, mode, barsSource, capital, lotSize, commissionPerTrade, slippagePct,
                executionLabel, brokerAccountId, null, null, null, null, null, false);
        }

        public StartRunRequest(
            String strategyId,
            String symbol,
            String mode,
            BarSourceResolver.BarsSource barsSource,
            Double capital,
            Double lotSize,
            Double commissionPerTrade,
            Double slippagePct,
            String executionLabel
        ) {
            this(strategyId, symbol, mode, barsSource, capital, lotSize, commissionPerTrade, slippagePct,
                executionLabel, null, null, null, null, null, null, false);
        }

        public StartRunRequest(
            String strategyId,
            String symbol,
            String mode,
            BarSourceResolver.BarsSource barsSource,
            Double capital,
            Double commissionPerTrade,
            Double slippagePct,
            String executionLabel
        ) {
            this(strategyId, symbol, mode, barsSource, capital, null, commissionPerTrade, slippagePct,
                executionLabel, null, null, null, null, null, null, false);
        }
    }

    private final EventStore eventStore;
    private final BrokerFactory brokerFactory;
    private final boolean requireOandaCredentials;
    private final KillSwitchRegistry killSwitchRegistry;
    private final Optional<DeploymentStore> deploymentStore;
    private final BrokerAccountRegistry brokerAccountRegistry;
    private final Map<String, DailyDrawdownMetrics> dailyDrawdownByRun = new ConcurrentHashMap<>();
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.locks.ReentrantLock> startupLocks = new ConcurrentHashMap<>();
    private final Map<String, RunConfigSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, AutoCloseable> activeExecutors = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RunTransitionListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final com.martinfou.trading.backtest.persistence.SqliteTradeAlignmentStore alignmentStore;
    private final com.martinfou.trading.backtest.persistence.SqliteTradeStore tradeStore;
    private final RunRecordStore runRecordStore;
    private final Map<String, List<com.martinfou.trading.core.Order>> btOrdersByRun = new ConcurrentHashMap<>();
    private final Map<String, List<com.martinfou.trading.core.Order>> liveOrdersByRun = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveTimeDrifts = new ConcurrentHashMap<>();

    public RunManager(EventStore eventStore) {
        this(eventStore, BrokerFactory.fromRegistry(BrokerAccountRegistry.loadDefault()), true,
            new KillSwitchRegistry(), Optional.empty(), BrokerAccountRegistry.loadDefault());
    }

    /** Control plane wiring — deployment store enables cross-account routing guards. */
    public RunManager(EventStore eventStore, DeploymentStore deploymentStore) {
        this(eventStore, BrokerFactory.fromRegistry(BrokerAccountRegistry.loadDefault()), true,
            new KillSwitchRegistry(), Optional.of(deploymentStore), BrokerAccountRegistry.loadDefault());
    }

    /** Test hook — inject broker without OANDA environment credentials. */
    RunManager(EventStore eventStore, BrokerFactory brokerFactory) {
        this(eventStore, brokerFactory, false, new KillSwitchRegistry(),
            Optional.empty(), BrokerAccountRegistry.loadDefault());
    }

    /** Test hook — shared kill switch registry with control plane. */
    RunManager(EventStore eventStore, BrokerFactory brokerFactory, KillSwitchRegistry killSwitchRegistry) {
        this(eventStore, brokerFactory, false, killSwitchRegistry,
            Optional.empty(), BrokerAccountRegistry.loadDefault());
    }

    /** Test hook — multi-account registry and deployment store. */
    RunManager(
        EventStore eventStore,
        BrokerFactory brokerFactory,
        DeploymentStore deploymentStore,
        BrokerAccountRegistry brokerAccountRegistry
    ) {
        this(eventStore, brokerFactory, false, new KillSwitchRegistry(),
            Optional.of(deploymentStore), brokerAccountRegistry);
    }

    private RunManager(
        EventStore eventStore,
        BrokerFactory brokerFactory,
        boolean requireOandaCredentials,
        KillSwitchRegistry killSwitchRegistry,
        Optional<DeploymentStore> deploymentStore,
        BrokerAccountRegistry brokerAccountRegistry
    ) {
        if (eventStore == null) {
            throw new IllegalArgumentException("eventStore must not be null");
        }
        if (brokerFactory == null) {
            throw new IllegalArgumentException("brokerFactory must not be null");
        }
        this.eventStore = new ReconcilingEventStore(eventStore);
        this.brokerFactory = brokerFactory;
        this.requireOandaCredentials = requireOandaCredentials;
        this.killSwitchRegistry = killSwitchRegistry != null ? killSwitchRegistry : new KillSwitchRegistry();
        this.deploymentStore = deploymentStore != null ? deploymentStore : Optional.empty();
        this.brokerAccountRegistry = brokerAccountRegistry != null
            ? brokerAccountRegistry
            : BrokerAccountRegistry.loadDefault();
        this.alignmentStore = createAlignmentStore(eventStore);
        this.tradeStore = createTradeStore(eventStore);
        this.runRecordStore = createRunRecordStore(eventStore);
    }

    private com.martinfou.trading.backtest.persistence.SqliteTradeAlignmentStore createAlignmentStore(EventStore eventStore) {
        EventStore unwrapped = eventStore;
        while (unwrapped != null) {
            if (unwrapped instanceof ReconcilingEventStore) {
                unwrapped = ((ReconcilingEventStore) unwrapped).delegate;
            } else if (unwrapped.getClass().getSimpleName().equals("BroadcastingEventStore")) {
                try {
                    java.lang.reflect.Field field = unwrapped.getClass().getDeclaredField("delegate");
                    field.setAccessible(true);
                    unwrapped = (EventStore) field.get(unwrapped);
                } catch (Exception e) {
                    break;
                }
            } else {
                break;
            }
        }
        
        if (unwrapped instanceof SqliteEventStore) {
            Connection conn = ((SqliteEventStore) unwrapped).connection();
            return new com.martinfou.trading.backtest.persistence.SqliteTradeAlignmentStore(conn, false);
        }
        
        try {
            Connection inMemoryConnection = DriverManager.getConnection("jdbc:sqlite::memory:");
            return new com.martinfou.trading.backtest.persistence.SqliteTradeAlignmentStore(inMemoryConnection, true);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create in-memory SQLite connection for alignment store", e);
        }
    }

    private com.martinfou.trading.backtest.persistence.SqliteTradeStore createTradeStore(EventStore eventStore) {
        EventStore unwrapped = eventStore;
        while (unwrapped != null) {
            if (unwrapped instanceof ReconcilingEventStore) {
                unwrapped = ((ReconcilingEventStore) unwrapped).delegate;
            } else if (unwrapped.getClass().getSimpleName().equals("BroadcastingEventStore")) {
                try {
                    java.lang.reflect.Field field = unwrapped.getClass().getDeclaredField("delegate");
                    field.setAccessible(true);
                    unwrapped = (EventStore) field.get(unwrapped);
                } catch (Exception e) {
                    break;
                }
            } else {
                break;
            }
        }
        
        if (unwrapped instanceof SqliteEventStore) {
            Connection conn = ((SqliteEventStore) unwrapped).connection();
            if (conn != null) {
                return new com.martinfou.trading.backtest.persistence.SqliteTradeStore(conn, false);
            }
        }
        
        try {
            Connection inMemoryConnection = DriverManager.getConnection("jdbc:sqlite::memory:");
            return new com.martinfou.trading.backtest.persistence.SqliteTradeStore(inMemoryConnection, true);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create in-memory SQLite connection for trade store", e);
        }
    }

    private RunRecordStore createRunRecordStore(EventStore eventStore) {
        EventStore unwrapped = eventStore;
        while (unwrapped != null) {
            if (unwrapped instanceof ReconcilingEventStore) {
                unwrapped = ((ReconcilingEventStore) unwrapped).delegate;
            } else if (unwrapped.getClass().getSimpleName().equals("BroadcastingEventStore")) {
                try {
                    java.lang.reflect.Field field = unwrapped.getClass().getDeclaredField("delegate");
                    field.setAccessible(true);
                    unwrapped = (EventStore) field.get(unwrapped);
                } catch (Exception e) {
                    break;
                }
            } else {
                break;
            }
        }
        
        if (unwrapped instanceof SqliteEventStore) {
            EventStoreConfig config = ((SqliteEventStore) unwrapped).config();
            if (config != null) {
                return new SqliteRunRecordStore(config);
            }
        }
        
        return new InMemoryRunRecordStore();
    }


    public KillSwitchRegistry killSwitchRegistry() {
        return killSwitchRegistry;
    }

    public BrokerAccountRegistry brokerAccountRegistry() {
        return brokerAccountRegistry;
    }

    public Optional<DailyDrawdownMetrics> dailyDrawdownMetrics(String runId) {
        return Optional.ofNullable(dailyDrawdownByRun.get(runId));
    }

    public Optional<AutoCloseable> getActiveExecutor(String runId) {
        return Optional.ofNullable(activeExecutors.get(runId));
    }

    public Map<String, AutoCloseable> getActiveExecutors() {
        return Map.copyOf(activeExecutors);
    }

    public Map<String, Object> getAlignmentDetails(String runId) {
        List<com.martinfou.trading.backtest.reconciliation.ReconciliationAnomaly> anomalies = alignmentStore.getAnomalies(runId);
        
        List<com.martinfou.trading.core.Order> btOrders = btOrdersByRun.get(runId);
        List<com.martinfou.trading.core.Order> liveOrders = liveOrdersByRun.get(runId);
        
        if (btOrders == null || liveOrders == null) {
            List<com.martinfou.trading.core.Order> tempBt = new ArrayList<>();
            List<com.martinfou.trading.core.Order> tempLive = new ArrayList<>();
            for (com.martinfou.trading.backtest.events.RunEvent event : eventStore.replayAll(runId)) {
                if (event.mode() != null && (event.mode().equalsIgnoreCase("PAPER") || event.mode().equalsIgnoreCase("LIVE"))) {
                    if (event.type() == com.martinfou.trading.backtest.events.RunEventType.ORDER_SUBMITTED) {
                        Map<String, Object> payload = event.payload();
                        if (payload != null) {
                            try {
                                String symbol = String.valueOf(payload.get("symbol"));
                                String sideStr = String.valueOf(payload.get("side"));
                                com.martinfou.trading.core.Order.Side side = com.martinfou.trading.core.Order.Side.valueOf(sideStr);
                                double quantity = toDouble(payload.get("quantity"));
                                double price = toDouble(payload.get("price"));
                                String correlationId = payload.containsKey("correlationId") && payload.get("correlationId") != null 
                                    ? String.valueOf(payload.get("correlationId")) 
                                    : null;
                                String orderId = payload.containsKey("orderId") && payload.get("orderId") != null 
                                    ? String.valueOf(payload.get("orderId")) 
                                    : null;
                                com.martinfou.trading.core.Order btOrder = new com.martinfou.trading.core.Order(symbol, side, com.martinfou.trading.core.Order.Type.MARKET, quantity, price)
                                    .withCorrelationId(correlationId)
                                    .withStatus(com.martinfou.trading.core.Order.Status.FILLED)
                                    .withFilledAt(event.timestamp());
                                if (orderId != null) btOrder.withId(orderId);
                                tempBt.add(btOrder);
                            } catch (Exception ignored) {}
                        }
                    } else if (event.type() == com.martinfou.trading.backtest.events.RunEventType.FILL) {
                        Map<String, Object> payload = event.payload();
                        if (payload != null) {
                            try {
                                String symbol = String.valueOf(payload.get("symbol"));
                                String sideStr = String.valueOf(payload.get("side"));
                                com.martinfou.trading.core.Order.Side side = com.martinfou.trading.core.Order.Side.valueOf(sideStr);
                                double quantity = toDouble(payload.get("quantity"));
                                double price = toDouble(payload.get("price"));
                                String correlationId = payload.containsKey("correlationId") && payload.get("correlationId") != null 
                                    ? String.valueOf(payload.get("correlationId")) 
                                    : null;
                                String orderId = payload.containsKey("orderId") && payload.get("orderId") != null 
                                    ? String.valueOf(payload.get("orderId")) 
                                    : null;
                                com.martinfou.trading.core.Order liveOrder = new com.martinfou.trading.core.Order(symbol, side, com.martinfou.trading.core.Order.Type.MARKET, quantity, price)
                                    .withCorrelationId(correlationId)
                                    .withStatus(com.martinfou.trading.core.Order.Status.FILLED)
                                    .withFilledAt(event.timestamp());
                                if (orderId != null) liveOrder.withId(orderId);
                                tempLive.add(liveOrder);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            btOrders = tempBt;
            liveOrders = tempLive;
            btOrdersByRun.put(runId, btOrders);
            liveOrdersByRun.put(runId, liveOrders);
        }
        
        return Map.of(
            "anomalies", anomalies,
            "backtestOrders", btOrders,
            "liveOrders", liveOrders
        );
    }

    @Override
    public RunRecord register(RunConfigSnapshot config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        validateSnapshot(config);
        String runId = UUID.randomUUID().toString();
        RunMode runMode = RunMode.valueOf(config.mode().toUpperCase());
        RunRecord record = new RunRecord(runId, config.strategyId(), config.symbol(), runMode, config);
        snapshots.put(runId, config);
        runs.put(runId, record);
        notifyTransition(null, record, RunTransition.REGISTER);
        return record;
    }

    public RunRecord restoreRun(String runId, RunConfigSnapshot config) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be null or blank");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        RunMode runMode = RunMode.valueOf(config.mode().toUpperCase());
        RunRecord record = new RunRecord(runId, config.strategyId(), config.symbol(), runMode, config);
        snapshots.put(runId, config);
        runs.put(runId, record);
        notifyTransition(null, record, RunTransition.REGISTER);
        return record;
    }

    public void restoreRun(RunRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        runs.put(record.runId(), record);
        RunConfigSnapshot snapshot = RunConfigSnapshot.fromRecord(record);
        snapshots.put(record.runId(), snapshot);
    }

    @Override
    public RunRecord start(String runId) {
        RunRecord record = requireRun(runId);
        RunRecord before = record;
        if (record.status() != RunRecord.Status.CREATED && record.status() != RunRecord.Status.PAUSED) {
            throw new IllegalStateException(
                "Cannot start run " + runId + " from status " + record.status());
        }
        RunConfigSnapshot config = snapshots.get(runId);
        if (config == null) {
            throw new IllegalStateException("Missing config snapshot for run " + runId);
        }
        if (record.status() == RunRecord.Status.CREATED) {
            List<com.martinfou.trading.core.Bar> bars;
            try {
                bars = loadBars(config, null, null);
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to load bars for run " + runId + ": " + e.getMessage(), e);
            }
            if (bars.isEmpty()) {
                throw new IllegalArgumentException("No bars loaded for run");
            }
            double capital = config.resolvedCapital();
            record.markRunning();
            notifyTransition(before, record, RunTransition.START);
            RunConfigSnapshot snapshot = config;
            executor.submit(() -> executeRun(runId, snapshot, bars, capital, record));
        } else {
            record.markRunning();
            notifyTransition(before, record, RunTransition.RESUME);
        }
        loadHistoricalOrders(runId);
        return record;
    }

    @Override
    public RunRecord stop(String runId) {
        return stop(runId, false);
    }

    public RunRecord stop(String runId, boolean liquidate) {
        consecutiveTimeDrifts.remove(runId);
        RunRecord record = requireRun(runId);
        RunRecord before = record;
        return switch (record.status()) {
            case CREATED -> {
                record.markFailed("stopped before start");
                notifyTransition(before, record, RunTransition.STOP);
                yield record;
            }
            case RUNNING -> {
                record.markCompleted(Map.of("message", "stopped by operator"));
                AutoCloseable exec = activeExecutors.get(runId);
                if (exec != null) {
                    try {
                        if (liquidate && exec instanceof OandaStreamingExecutor poe) {
                            poe.liquidateAndStop();
                        } else {
                            exec.close();
                        }
                    } catch (Exception e) {
                        log.error("Failed to stop executor for run {}", runId, e);
                    }
                }
                notifyTransition(before, record, RunTransition.STOP);
                yield record;
            }
            case PAUSED -> {
                record.markFailed("stopped while paused");
                notifyTransition(before, record, RunTransition.STOP);
                yield record;
            }
            // Already terminal — stopping again is a no-op; return the record silently.
            case COMPLETED, FAILED, ARCHIVED -> {
                yield record;
            }
            default -> throw new IllegalStateException(
                "Cannot stop run " + runId + " from status " + record.status());
        };
    }

    public void reconnectBroker(String runId) {
        AutoCloseable exec = activeExecutors.get(runId);
        if (exec instanceof OandaStreamingExecutor poe) {
            poe.reconnectBroker();
        }
    }

    @Override
    public RunRecord pause(String runId) {
        RunRecord record = requireRun(runId);
        RunRecord before = record;
        if (record.status() != RunRecord.Status.RUNNING) {
            throw new IllegalStateException(
                "Cannot pause run " + runId + " from status " + record.status());
        }
        record.markPaused();
        notifyTransition(before, record, RunTransition.PAUSE);
        return record;
    }

    @Override
    public RunRecord resume(String runId) {
        return start(runId);
    }

    @Override
    public RunRecord archive(String runId) {
        RunRecord record = requireRun(runId);
        RunRecord before = record;
        if (record.status() != RunRecord.Status.COMPLETED && record.status() != RunRecord.Status.FAILED) {
            throw new IllegalStateException(
                "Cannot archive run " + runId + " from status " + record.status());
        }
        record.markArchived();
        notifyTransition(before, record, RunTransition.ARCHIVE);
        return record;
    }

    /**
     * Retires a run — gracefully marks it RETIRED so it is excluded from dashboards and
     * duplicate-run checks without being considered a failure. Only COMPLETED or FAILED
     * runs can be retired; RUNNING runs must be killed first.
     */
    public RunRecord retire(String runId, String reason) {
        RunRecord record = requireRun(runId);
        RunRecord before = record;
        RunRecord.Status s = record.status();
        if (s != RunRecord.Status.COMPLETED && s != RunRecord.Status.FAILED && s != RunRecord.Status.ARCHIVED) {
            throw new IllegalStateException(
                "Cannot retire run " + runId + " from status " + s + ". Kill it first.");
        }
        record.markRetired(reason != null ? reason : "Retired by operator");
        log.info("Run {} retired: {}", runId, record.errorMessage().orElse(""));
        notifyTransition(before, record, RunTransition.RETIRE);
        return record;
    }

    @Override
    public Optional<RunRecord> get(String runId) {
        return getRun(runId);
    }

    @Override
    public List<RunRecord> list(RunRecord.Status filter) {
        if (filter == null) {
            return List.copyOf(runs.values());
        }
        return runs.values().stream()
            .filter(r -> r.status() == filter)
            .toList();
    }

    @Override
    public void addTransitionListener(RunTransitionListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Convenience: validate request, register snapshot, and start execution. */
    public String startRun(StartRunRequest request) throws IOException {
        var lock = startupLocks.computeIfAbsent(request.strategyId(), k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            String symbol = request.symbol() != null && !request.symbol().isBlank()
                ? request.symbol()
                : StrategyCatalog.defaultSymbol(request.strategyId());

            boolean isForce = Boolean.TRUE.equals(request.force());
            if (!isForce) {
                boolean duplicateExists = runs.values().stream()
                    .anyMatch(r -> r.status() == RunRecord.Status.RUNNING 
                        && r.strategyId().equals(request.strategyId())
                        && r.symbol().equalsIgnoreCase(symbol)
                        && r.mode().name().equalsIgnoreCase(request.mode()));
                if (duplicateExists) {
                    throw new IllegalArgumentException("Run already active for strategyId=" + request.strategyId() + ", symbol=" + symbol + ", mode=" + request.mode());
                }
            }

            RunConfigSnapshot configSnapshot = RunConfigSnapshot.fromRequest(request, symbol);
            RunConfigSnapshot resolved = resolveBrokerAccount(request.strategyId(), request.brokerAccountId(), configSnapshot);
            if (isBrokerBackedRun(resolved)) {
                try {
                    Broker broker = brokerFactory.create(resolved);
                    double equity = broker.getAccountState().equity();
                    resolved = resolved.withCapital(equity);
                } catch (Exception e) {
                    log.warn("Failed to retrieve broker account equity for run: {}. Falling back to default or requested capital.", e.getMessage());
                }
            }
            validate(request, resolved);
            RunRecord record = register(resolved);
            start(record.runId());
            return record.runId();
        } finally {
            lock.unlock();
        }
    }

    public Optional<RunRecord> getRun(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    /** Latest completed run for a strategy and mode (by completion time). */
    public Optional<RunRecord> latestCompletedRun(String strategyId, RunMode mode) {
        return runs.values().stream()
            .filter(r -> r.strategyId().equals(strategyId))
            .filter(r -> r.mode() == mode)
            .filter(r -> r.status() == RunRecord.Status.COMPLETED)
            .max(Comparator.comparing(r -> r.completedAt().orElse(r.startedAt())));
    }

    /**
     * Evict completed, failed, or archived runs from the in-memory map
     * if they have been in a terminal state for more than 10 minutes (600 seconds).
     */
    public void pruneTerminalRuns() {
        Instant cutoff = Instant.now().minusSeconds(600);
        java.util.Set<String> toPrune = new java.util.HashSet<>();
        for (RunRecord r : runs.values()) {
            if (r.isTerminal() && r.completedAt().map(t -> t.isBefore(cutoff)).orElse(false)) {
                toPrune.add(r.runId());
            }
        }
        if (!toPrune.isEmpty()) {
            log.info("Pruning {} terminal runs older than 10 minutes from memory: {}", toPrune.size(), toPrune);
            for (String runId : toPrune) {
                runs.remove(runId);
                snapshots.remove(runId);
                dailyDrawdownByRun.remove(runId);
                btOrdersByRun.remove(runId);
                liveOrdersByRun.remove(runId);
                consecutiveTimeDrifts.remove(runId);
            }
        }
        // Evict startup lock entries for strategies that have no active (RUNNING/PAUSED) runs.
        // This prevents the startupLocks map from growing without bound.
        java.util.Set<String> activeStrategyIds = runs.values().stream()
            .filter(r -> r.status() == RunRecord.Status.RUNNING || r.status() == RunRecord.Status.PAUSED)
            .map(RunRecord::strategyId)
            .collect(java.util.stream.Collectors.toSet());
        startupLocks.keySet().removeIf(strategyId -> !activeStrategyIds.contains(strategyId));
    }

    public EventStore eventStore() {
        return eventStore;
    }

    @Override
    public void close() {
        executor.close();
        try {
            alignmentStore.close();
        } catch (Exception e) {
            log.error("Failed to close SqliteTradeAlignmentStore", e);
        }
        try {
            tradeStore.close();
        } catch (Exception e) {
            log.error("Failed to close SqliteTradeStore", e);
        }
        try {
            runRecordStore.close();
        } catch (Exception e) {
            log.error("Failed to close RunRecordStore", e);
        }
    }

    public com.martinfou.trading.backtest.persistence.SqliteTradeStore tradeStore() {
        return tradeStore;
    }

    public RunRecordStore runRecordStore() {
        return runRecordStore;
    }

    public List<com.martinfou.trading.core.Trade> getTrades(String runId) {
        Optional<RunRecord> recordOpt = getRun(runId);
        if (recordOpt.isPresent()) {
            RunRecord targetRecord = recordOpt.get();
            String strategyId = targetRecord.strategyId();
            RunMode mode = targetRecord.mode();

            List<RunRecord> siblingRuns = runRecordStore.listAll().stream()
                .filter(r -> java.util.Objects.equals(strategyId, r.strategyId())
                    && mode == r.mode()
                    && java.util.Objects.equals(targetRecord.symbol(), r.symbol()))
                .toList();

            List<com.martinfou.trading.core.Trade> cumulativeTrades = new ArrayList<>();
            for (RunRecord sibling : siblingRuns) {
                List<com.martinfou.trading.core.Trade> siblingTrades = tradeStore.getTrades(sibling.runId());
                if (siblingTrades.isEmpty()) {
                    siblingTrades = com.martinfou.trading.backtest.persistence.TradeReconstructor.reconstruct(eventStore.replayAll(sibling.runId()));
                }
                cumulativeTrades.addAll(siblingTrades);
            }
            // Sort by entry time
            cumulativeTrades.sort(Comparator.comparing(
                com.martinfou.trading.core.Trade::entryTime,
                Comparator.nullsLast(Instant::compareTo)
            ));
            return cumulativeTrades;
        }

        List<com.martinfou.trading.core.Trade> list = tradeStore.getTrades(runId);
        if (list.isEmpty()) {
            return com.martinfou.trading.backtest.persistence.TradeReconstructor.reconstruct(eventStore.replayAll(runId));
        }
        return list;
    }

    private void executeRun(
        String runId,
        RunConfigSnapshot configSnapshot,
        List<Bar> bars,
        double capital,
        RunRecord record
    ) {
        org.slf4j.MDC.put("runId", runId);
        org.slf4j.MDC.put("strategyId", configSnapshot.strategyId());
        org.slf4j.MDC.put("stale", "false");
        try {
            RunRecord before = snapshotRecord(record);
            try {
            RunMode runMode = RunMode.valueOf(configSnapshot.mode().toUpperCase());
            com.martinfou.trading.backtest.BacktestResult result;
            // Captures a failure message from OandaStreamingExecutor when the stop was error-driven.
            // Null for operator-initiated stops and all non-OANDA-streaming paths.
            String oandaStreamError = null;
            if (configSnapshot.resolvedExecutionLabel().isOandaBroker() && System.getProperty("trading.bridge.test") == null) {
                try (Broker broker = brokerFactory.create(configSnapshot)) {
                    Strategy strategy = StrategyCatalog.create(
                        configSnapshot.strategyId(), configSnapshot.symbol(), configSnapshot.quantity());
                    RunRiskContext riskContext = new RunRiskContext(
                        new RiskEngine(),
                        (run, cfg, m, check) -> {
                            if (record.status() == RunRecord.Status.RUNNING) {
                                RunRecord pausedBefore = snapshotRecord(record);
                                record.markPaused();
                                notifyTransition(pausedBefore, record, RunTransition.PAUSE);
                            }
                        },
                        metrics -> dailyDrawdownByRun.put(runId, metrics));

                    String accountId = configSnapshot.brokerAccountId();
                    com.martinfou.trading.data.oanda.OandaRestClient restClient = null;
                    var oandaCreds = brokerAccountRegistry.credentials(accountId);
                    if (oandaCreds.isPresent()) {
                        var creds = oandaCreds.get();
                        restClient = new com.martinfou.trading.data.oanda.HttpOandaRestClient(
                            creds.apiToken(), creds.accountId(), creds.restUrl());
                    } else {
                        restClient = new com.martinfou.trading.data.oanda.StubOandaRestClient();
                    }

                    var creds = oandaCreds.orElse(null);
                    var streamClient = new com.martinfou.trading.data.oanda.OandaStreamingClient(
                        creds != null ? creds.apiToken() : "",
                        creds != null ? creds.accountId() : "",
                        creds == null || creds.restUrl().contains("practice")
                    );

                    OandaStreamingExecutor oandaExecutor = new OandaStreamingExecutor(
                        runId,
                        record,
                        configSnapshot,
                        strategy,
                        broker,
                        restClient,
                        eventStore,
                        killSwitchRegistry,
                        streamClient,
                        riskContext,
                        () -> this.list(null).stream()
                            .filter(r -> r.status() == RunRecord.Status.RUNNING)
                            .map(RunRecord::symbol)
                            .toList()
                    );

                    activeExecutors.put(runId, oandaExecutor);
                    oandaExecutor.start();

                    while (record.status() == RunRecord.Status.RUNNING) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            break;
                        }
                        if (!oandaExecutor.isActive()) {
                            break;
                        }
                    }
                    oandaExecutor.stop();
                    oandaStreamError = oandaExecutor.getPendingFailure();
                    activeExecutors.remove(runId);

                    double finalEquity = broker.getAccountState().equity();
                    result = BacktestResult.builder()
                        .initialCapital(capital)
                        .finalEquity(finalEquity)
                        .totalTrades(oandaExecutor.getFilledCount())
                        .totalReturnPct(capital > 0 ? (finalEquity - capital) / capital * 100.0 : 0.0)
                        .build();
                }
            } else if (isBrokerBackedRun(configSnapshot)) {
                try (Broker broker = brokerFactory.create(configSnapshot)) {
                    Strategy strategy = StrategyCatalog.create(
                        configSnapshot.strategyId(), configSnapshot.symbol(), configSnapshot.quantity());
                    RunRiskContext riskContext = new RunRiskContext(
                        new RiskEngine(),
                        (run, cfg, mode, check) -> {
                            if (record.status() == RunRecord.Status.RUNNING) {
                                RunRecord pausedBefore = snapshotRecord(record);
                                record.markPaused();
                                notifyTransition(pausedBefore, record, RunTransition.PAUSE);
                            }
                        },
                        metrics -> dailyDrawdownByRun.put(runId, metrics));
                    result = BrokerRunExecutor.execute(
                        runId, configSnapshot, bars, capital, strategy, broker, eventStore, killSwitchRegistry, null, riskContext,
                        () -> this.list(null).stream()
                            .filter(r -> r.status() == RunRecord.Status.RUNNING)
                            .map(RunRecord::symbol)
                            .toList());
                }
            } else {
                var context = RunLauncher.create(
                    runId,
                    configSnapshot.strategyId(),
                    configSnapshot.symbol(),
                    runMode,
                    bars,
                    capital,
                    configSnapshot,
                    eventStore);
                result = context.run();
            }
            requireTerminalEvent(runId, RunEventType.RUN_ENDED);
            if (oandaStreamError != null) {
                // Error-driven stop from OandaStreamingExecutor: drive FAILED through RunManager's
                // state machine so all transition listeners are properly notified.
                record.noteEventAt(latestEventTimestamp(runId).orElse(Instant.now()));
                if (record.status() == RunRecord.Status.RUNNING || record.status() == RunRecord.Status.PAUSED) {
                    record.markFailed(oandaStreamError);
                }
                notifyTransition(before, record, RunTransition.FAIL);
            } else if (record.status() == RunRecord.Status.RUNNING) {
                record.noteEventAt(latestEventTimestamp(runId).orElse(Instant.now()));
                record.markCompleted(BacktestResultPayload.toEndedPayload(result));
                notifyTransition(before, record, RunTransition.COMPLETE);
            } else if (record.status() == RunRecord.Status.PAUSED) {
                record.noteEventAt(latestEventTimestamp(runId).orElse(Instant.now()));
            }
        } catch (RuntimeException e) {
            log.error("Run {} failed with runtime exception", runId, e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (hasTerminalEvent(runId, RunEventType.ERROR)) {
                latestEventTimestamp(runId).ifPresent(record::noteEventAt);
            }
            if (record.status() == RunRecord.Status.RUNNING || record.status() == RunRecord.Status.PAUSED) {
                record.markFailed(msg);
                notifyTransition(before, record, RunTransition.FAIL);
            }
            }
        } finally {
            org.slf4j.MDC.remove("runId");
            org.slf4j.MDC.remove("strategyId");
            org.slf4j.MDC.remove("stale");
        }
    }

    private static RunRecord snapshotRecord(RunRecord record) {
        return record;
    }

    private void notifyTransition(RunRecord before, RunRecord after, RunTransition cause) {
        if (after != null) {
            try {
                runRecordStore.save(after);
            } catch (Exception e) {
                log.error("Failed to persist run record " + after.runId() + " on transition " + cause, e);
            }
        }
        for (RunTransitionListener listener : listeners) {
            listener.onTransition(before, after, cause);
        }
    }

    private RunRecord requireRun(String runId) {
        RunRecord record = runs.get(runId);
        if (record == null) {
            throw new IllegalArgumentException("Unknown run: " + runId);
        }
        return record;
    }

    static List<Bar> loadBars(RunConfigSnapshot config, Integer limit, Instant to) throws IOException {
        String mode = config.mode();
        if (mode != null && (mode.equalsIgnoreCase("PAPER") || mode.equalsIgnoreCase("LIVE"))) {
            try {
                String accountId = config.brokerAccountId();
                var registry = BrokerAccountRegistry.loadDefault();
                var oandaCreds = registry.credentials(accountId);
                if (oandaCreds.isPresent()) {
                    var creds = oandaCreds.get();
                    String tf = config.strategyTimeframe();
                    if (tf == null || tf.isBlank()) {
                        tf = "H1";
                    } else {
                        tf = tf.toUpperCase();
                    }
                    com.martinfou.trading.data.OandaPriceClient priceClient = new com.martinfou.trading.data.OandaPriceClient(
                        creds.apiToken(), creds.accountId(), creds.restUrl().contains("practice")
                    );
                    int fetchCount = (limit != null && limit > 0) ? limit : 500;
                    log.info("Fetching last {} live {} bars from OANDA for symbol {} (to: {})...", fetchCount, tf, config.symbol(), to);
                    return priceClient.getCandlesBefore(config.symbol(), tf, fetchCount, to);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch live candles from OANDA: {}. Falling back to default bars.", e.getMessage());
            }
        }
        if (config.barsSourceType() == null) {
            throw new IllegalArgumentException("barsSourceType is required in config snapshot");
        }

        String sourceType = config.barsSourceType();
        if (System.getProperty("trading.bridge.test") == null && sourceType != null && !sourceType.equalsIgnoreCase("sample")) {
            Path barsDir = RuntimeDataPaths.defaultBarsDirectory();
            Path csvDir = RuntimeDataPaths.defaultDukascopyDirectory();
            try {
                String symbol = config.symbol();
                int currentYear = java.time.LocalDate.now().getYear();

                var availability = com.martinfou.trading.data.HistoricalDataCatalog.availability(symbol, barsDir, csvDir);
                boolean emptyCatalog = availability.years().isEmpty();

                // 1. Download current year if catalog is completely empty
                if (emptyCatalog) {
                    lockAndDownload(symbol, currentYear, config.strategyTimeframe(), barsDir, csvDir);
                }

                // 2. Download requested year(s) if missing
                if (sourceType.equalsIgnoreCase("year") && config.barsSourceYear() != null && !config.barsSourceYear().isBlank()) {
                    String yearSpec = config.barsSourceYear().trim();
                    if (yearSpec.contains("-") && yearSpec.matches("\\d{4}-\\d{4}")) {
                        String[] parts = yearSpec.split("-");
                        int start = Integer.parseInt(parts[0]);
                        int end = Integer.parseInt(parts[1]);
                        for (int y = start; y <= end; y++) {
                            lockAndDownload(symbol, y, config.strategyTimeframe(), barsDir, csvDir);
                        }
                    } else if (!yearSpec.equalsIgnoreCase("all")) {
                        try {
                            int yearToDownload = Integer.parseInt(yearSpec);
                            lockAndDownload(symbol, yearToDownload, config.strategyTimeframe(), barsDir, csvDir);
                        } catch (NumberFormatException ignored) {}
                    }
                } else {
                    lockAndDownload(symbol, currentYear, config.strategyTimeframe(), barsDir, csvDir);
                }
            } catch (Exception e) {
                log.warn("Failed to check or download historical data: {}", e.getMessage());
            }
        }

        var source = new BarSourceResolver.BarsSource(
            config.barsSourceType(),
            config.barsSourceCount(),
            config.barsSourceYear(),
            config.barsSourcePath());
        List<Bar> allBars = BarSourceResolver.load(source, config.symbol());
        if (to != null) {
            allBars = allBars.stream().filter(b -> b.timestamp().isBefore(to)).toList();
        }
        if (limit != null && limit > 0 && limit < allBars.size()) {
            allBars = allBars.subList(allBars.size() - limit, allBars.size());
        }
        return allBars;
    }

    private static void downloadYearSync(String symbol, int year, String tf) throws IOException {
        String pair = symbol.replace("_", "").toLowerCase(java.util.Locale.ROOT);
        String tfLower = tf.toLowerCase(java.util.Locale.ROOT);
        Path csvDir = RuntimeDataPaths.defaultDukascopyDirectory();
        Path barsDir = RuntimeDataPaths.defaultBarsDirectory();

        DukascopyDownloader downloader = new DukascopyDownloader();
        log.info("Starting Java-native download for pair {} year {} tf {}", pair, year, tfLower);
        Path downloadedCsv = downloader.download(pair, year, tfLower, csvDir);

        log.info("Converting downloaded CSV {} to binary bars...", downloadedCsv);
        BarStore store = new BarStore(symbol, tf.toUpperCase(java.util.Locale.ROOT) + "_" + year, barsDir);
        store.writeFromCSV(downloadedCsv);
        log.info("Successfully completed Java-native download and conversion for {} year {}", symbol, year);
    }

    private static boolean isYearAvailable(String symbol, int year, Path barsDir, Path csvDir) {
        try {
            var availability = com.martinfou.trading.data.HistoricalDataCatalog.availability(symbol, barsDir, csvDir);
            return availability.years().contains(year);
        } catch (Exception e) {
            return false;
        }
    }

    private static void lockAndDownload(String symbol, int year, String tf, Path barsDir, Path csvDir) throws IOException {
        if (isYearAvailable(symbol, year, barsDir, csvDir)) {
            return;
        }
        String lockKey = symbol + "-" + year;
        var lock = downloadLocks.computeIfAbsent(lockKey, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            // Double check under lock
            if (!isYearAvailable(symbol, year, barsDir, csvDir)) {
                String timeframe = tf;
                if (timeframe == null || timeframe.isBlank()) {
                    timeframe = "H1";
                }
                System.out.println("No bars available for strategy symbol " + symbol + " and year " + year + ". Downloading data... This will take a while.");
                log.info("No bars available for strategy symbol {} and year {}. Downloading data... This will take a while.", symbol, year);
                downloadYearSync(symbol, year, timeframe);
            }
        } finally {
            lock.unlock();
        }
    }

    private void requireTerminalEvent(String runId, RunEventType expected) {
        if (!hasTerminalEvent(runId, expected)) {
            throw new IllegalStateException(
                "Run " + runId + " finished without " + expected + " in event store");
        }
    }

    private boolean hasTerminalEvent(String runId, RunEventType type) {
        return eventStore.replayAll(runId).stream().anyMatch(e -> e.type() == type);
    }

    private Optional<Instant> latestEventTimestamp(String runId) {
        List<RunEvent> events = eventStore.replayAll(runId);
        if (events.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(events.getLast().timestamp());
    }

    private void validateSnapshot(RunConfigSnapshot config) {
        if (config.strategyId() == null || config.strategyId().isBlank()) {
            throw new IllegalArgumentException("strategyId is required");
        }
        if (!StrategyCatalog.contains(config.strategyId())) {
            throw new IllegalArgumentException("Unknown strategy: " + config.strategyId());
        }
        if (config.mode() == null || config.mode().isBlank()) {
            throw new IllegalArgumentException("mode is required");
        }
        RunMode mode;
        try {
            mode = RunMode.valueOf(config.mode().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid mode: " + config.mode());
        }
        validateExecutionLabelForMode(mode, config.resolvedExecutionLabel());
        validateBrokerCredentials(config);
    }

    private static boolean isBrokerBackedRun(RunConfigSnapshot config) {
        return config.resolvedExecutionLabel().isBrokerBacked();
    }

    private void validateBrokerCredentials(RunConfigSnapshot config) {
        if (!requireOandaCredentials) {
            return;
        }
        ExecutionLabel label = config.resolvedExecutionLabel();
        if (!label.isBrokerBacked()) {
            return;
        }
        assertBrokerAccountRouting(config.strategyId(), config.brokerAccountId());
        brokerAccountRegistry.requireConfigured(config.brokerAccountId());
    }

    private RunConfigSnapshot resolveBrokerAccount(
        String strategyId,
        String requestedAccountId,
        RunConfigSnapshot config
    ) {
        String deploymentAccountId = deploymentStore
            .flatMap(store -> store.get(strategyId))
            .map(DeploymentRecord::brokerAccountId)
            .orElse(null);
        String resolved = requestedAccountId != null && !requestedAccountId.isBlank()
            ? requestedAccountId
            : deploymentAccountId;
        assertBrokerAccountRouting(strategyId, resolved);
        return config.withBrokerAccountId(resolved);
    }

    private void assertBrokerAccountRouting(String strategyId, String requestedAccountId) {
        if (deploymentStore.isEmpty()) {
            if (requestedAccountId != null && !requestedAccountId.isBlank()) {
                brokerAccountRegistry.requireKnown(requestedAccountId);
            }
            return;
        }
        String deploymentAccountId = deploymentStore.get()
            .get(strategyId)
            .map(DeploymentRecord::brokerAccountId)
            .orElse(null);
        BrokerAccountRegistry.assertRoutingAllowed(requestedAccountId, deploymentAccountId);
        brokerAccountRegistry.requireKnown(BrokerAccountRegistry.resolveId(requestedAccountId));
    }

    private void validate(StartRunRequest request, RunConfigSnapshot configSnapshot) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.strategyId() == null || request.strategyId().isBlank()) {
            throw new IllegalArgumentException("strategyId is required");
        }
        if (!StrategyCatalog.contains(request.strategyId())) {
            throw new IllegalArgumentException("Unknown strategy: " + request.strategyId());
        }
        if (request.mode() == null || request.mode().isBlank()) {
            throw new IllegalArgumentException("mode is required");
        }
        if (request.capital() != null && request.capital() <= 0) {
            throw new IllegalArgumentException("capital must be positive");
        }
        if (request.lotSize() != null && request.lotSize() <= 0) {
            throw new IllegalArgumentException("lotSize must be positive");
        }
        try {
            RunMode.valueOf(request.mode().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid mode: " + request.mode());
        }
        RunMode mode = RunMode.valueOf(request.mode().toUpperCase());
        validateExecutionLabelForMode(mode, configSnapshot.resolvedExecutionLabel());
        validateBrokerCredentials(configSnapshot);

        if (!Boolean.TRUE.equals(request.force()) && (mode == RunMode.PAPER || mode == RunMode.LIVE)) {
            String strategyId = configSnapshot.strategyId();
            String symbol = configSnapshot.symbol();
            String accountId = configSnapshot.resolvedBrokerAccountId();

            boolean duplicate = runs.values().stream()
                .filter(r -> r.status() == RunRecord.Status.RUNNING || r.status() == RunRecord.Status.PAUSED)
                .filter(r -> r.mode() == mode)
                .filter(r -> r.strategyId().equalsIgnoreCase(strategyId))
                .filter(r -> r.symbol().equalsIgnoreCase(symbol))
                .anyMatch(r -> {
                    String existingAccount = r.configSnapshot() != null
                        ? BrokerAccountRegistry.resolveId((String) r.configSnapshot().get("brokerAccountId"))
                        : BrokerAccountRegistry.DEFAULT_ID;
                    return existingAccount.equalsIgnoreCase(accountId);
                });

            if (duplicate) {
                throw new IllegalArgumentException("Strategy " + strategyId + " is already running on account " + accountId + " for symbol " + symbol);
            }
        }
    }

    private static void validateExecutionLabelForMode(RunMode mode, ExecutionLabel label) {
        if (label == ExecutionLabel.PAPER_OANDA && mode != RunMode.PAPER) {
            throw new IllegalArgumentException("executionLabel PAPER_OANDA requires mode PAPER");
        }
        if (label == ExecutionLabel.PAPER_IBKR && mode != RunMode.PAPER) {
            throw new IllegalArgumentException("executionLabel PAPER_IBKR requires mode PAPER");
        }
        if (label.isLiveBroker() && mode != RunMode.LIVE) {
            throw new IllegalArgumentException("executionLabel " + label.name() + " requires mode LIVE");
        }
        if (mode == RunMode.LIVE && !label.isLiveBroker()) {
            throw new IllegalArgumentException("LIVE mode requires executionLabel LIVE_OANDA or LIVE_IBKR");
        }
    }

    /** Downsample an equity curve to max N points for frontend display. */
    static List<Double> sampleEquityCurve(List<Double> curve, int maxPoints) {
        if (curve == null || curve.isEmpty()) return List.of();
        if (curve.size() <= maxPoints) return List.copyOf(curve);
        List<Double> sampled = new ArrayList<>(maxPoints);
        double step = (double) (curve.size() - 1) / (maxPoints - 1);
        for (int i = 0; i < maxPoints; i++) {
            int index = Math.min((int) Math.round(i * step), curve.size() - 1);
            sampled.add(curve.get(index));
        }
        return sampled;
    }

    private final class ReconcilingEventStore implements EventStore {
        private final EventStore delegate;

        public ReconcilingEventStore(EventStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public long append(String runId, RunEvent event) {
            long sequence = delegate.append(runId, event);
            processEventForReconciliation(runId, event, true);
            if (event.type() == RunEventType.FILL) {
                try {
                    List<com.martinfou.trading.core.Trade> reconstructed = com.martinfou.trading.backtest.persistence.TradeReconstructor.reconstruct(delegate.replayAll(runId));
                    tradeStore.deleteForRun(runId);
                    tradeStore.insertAll(runId, reconstructed);
                } catch (Exception e) {
                    log.error("Failed to update trades for run on append: " + runId, e);
                }
            }
            return sequence;
        }

        @Override
        public List<RunEvent> query(String runId, long afterSequence, int limit) {
            return delegate.query(runId, afterSequence, limit);
        }

        @Override
        public long count(String runId) {
            return delegate.count(runId);
        }

        @Override
        public List<RunEvent> replayAll(String runId) {
            return delegate.replayAll(runId);
        }

        @Override
        public List<StoredRunEvent> queryWithSequence(String runId, long afterSequence, int limit) {
            return delegate.queryWithSequence(runId, afterSequence, limit);
        }

        @Override
        public void publishEphemeral(String runId, RunEvent event) {
            delegate.publishEphemeral(runId, event);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private void loadHistoricalOrders(String runId) {
        List<com.martinfou.trading.core.Order> btOrders = btOrdersByRun.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>());
        List<com.martinfou.trading.core.Order> liveOrders = liveOrdersByRun.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>());
        btOrders.clear();
        liveOrders.clear();
        for (RunEvent event : eventStore.replayAll(runId)) {
            processEventForReconciliation(runId, event, false);
        }
        runReconciliation(runId, false);
        
        try {
            List<com.martinfou.trading.core.Trade> reconstructed = com.martinfou.trading.backtest.persistence.TradeReconstructor.reconstruct(eventStore.replayAll(runId));
            tradeStore.deleteForRun(runId);
            tradeStore.insertAll(runId, reconstructed);
        } catch (Exception e) {
            log.error("Failed to reconstruct and persist trades for run: " + runId, e);
        }
    }

    private void processEventForReconciliation(String runId, RunEvent event, boolean triggerActions) {
        if (event.mode() == null || (!event.mode().equalsIgnoreCase("PAPER") && !event.mode().equalsIgnoreCase("LIVE"))) {
            return;
        }

        if (event.type() == RunEventType.ORDER_SUBMITTED) {
            Map<String, Object> payload = event.payload();
            if (payload == null) return;
            try {
                String symbol = String.valueOf(payload.get("symbol"));
                String sideStr = String.valueOf(payload.get("side"));
                com.martinfou.trading.core.Order.Side side = com.martinfou.trading.core.Order.Side.valueOf(sideStr);
                double quantity = toDouble(payload.get("quantity"));
                double price = toDouble(payload.get("price"));
                String correlationId = payload.containsKey("correlationId") && payload.get("correlationId") != null 
                    ? String.valueOf(payload.get("correlationId")) 
                    : null;
                String orderId = payload.containsKey("orderId") && payload.get("orderId") != null 
                    ? String.valueOf(payload.get("orderId")) 
                    : null;
                
                com.martinfou.trading.core.Order btOrder = new com.martinfou.trading.core.Order(symbol, side, com.martinfou.trading.core.Order.Type.MARKET, quantity, price)
                    .withCorrelationId(correlationId)
                    .withStatus(com.martinfou.trading.core.Order.Status.FILLED)
                    .withFilledAt(event.timestamp());
                if (orderId != null) {
                    btOrder.withId(orderId);
                }
                
                btOrdersByRun.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(btOrder);
            } catch (Exception e) {
                log.error("Failed to parse ORDER_SUBMITTED event for reconciliation", e);
            }
        } else if (event.type() == RunEventType.FILL) {
            Map<String, Object> payload = event.payload();
            if (payload == null) return;
            try {
                String symbol = String.valueOf(payload.get("symbol"));
                String sideStr = String.valueOf(payload.get("side"));
                com.martinfou.trading.core.Order.Side side = com.martinfou.trading.core.Order.Side.valueOf(sideStr);
                double quantity = toDouble(payload.get("quantity"));
                double price = toDouble(payload.get("price"));
                String correlationId = payload.containsKey("correlationId") && payload.get("correlationId") != null 
                    ? String.valueOf(payload.get("correlationId")) 
                    : null;
                String orderId = payload.containsKey("orderId") && payload.get("orderId") != null 
                    ? String.valueOf(payload.get("orderId")) 
                    : null;
                
                com.martinfou.trading.core.Order liveOrder = new com.martinfou.trading.core.Order(symbol, side, com.martinfou.trading.core.Order.Type.MARKET, quantity, price)
                    .withCorrelationId(correlationId)
                    .withStatus(com.martinfou.trading.core.Order.Status.FILLED)
                    .withFilledAt(event.timestamp());
                if (orderId != null) {
                    liveOrder.withId(orderId);
                }
                
                liveOrdersByRun.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(liveOrder);
                
                // Trigger reconciliation check!
                if (triggerActions) {
                    runReconciliation(runId, triggerActions);
                }
            } catch (Exception e) {
                log.error("Failed to parse FILL event for reconciliation", e);
            }
        }
    }

    private void runReconciliation(String runId, boolean triggerActions) {
        List<com.martinfou.trading.core.Order> btOrders = btOrdersByRun.get(runId);
        List<com.martinfou.trading.core.Order> liveOrders = liveOrdersByRun.get(runId);
        if ((btOrders == null || btOrders.isEmpty()) && (liveOrders == null || liveOrders.isEmpty())) {
            return;
        }

        List<com.martinfou.trading.core.Order> btList = btOrders != null ? btOrders : List.of();
        List<com.martinfou.trading.core.Order> liveList = liveOrders != null ? liveOrders : List.of();

        try {
            com.martinfou.trading.backtest.reconciliation.TradeReconciler reconciler = new com.martinfou.trading.backtest.reconciliation.TradeReconciler();
            com.martinfou.trading.backtest.reconciliation.ReconciliationConfig config = com.martinfou.trading.backtest.reconciliation.ReconciliationConfig.DEFAULT;
            
            List<com.martinfou.trading.backtest.reconciliation.ReconciliationAnomaly> anomalies = reconciler.reconcile(btList, liveList, config);
            
            // Clear previous anomalies for this run and persist newly detected ones
            alignmentStore.deleteForRun(runId);
            if (!anomalies.isEmpty()) {
                alignmentStore.insertAll(runId, anomalies);
            }
            
            if (!triggerActions) {
                return;
            }

            // Check consecutive TIME_DRIFT and logic anomalies (MISSING_LIVE / GHOST_LIVE)
            boolean hasLogicAnomaly = false;
            boolean hasTimeDriftOnLatest = false;
            
            for (com.martinfou.trading.backtest.reconciliation.ReconciliationAnomaly anomaly : anomalies) {
                if (anomaly.type() == com.martinfou.trading.backtest.reconciliation.ReconciliationAnomaly.AnomalyType.MISSING_LIVE ||
                    anomaly.type() == com.martinfou.trading.backtest.reconciliation.ReconciliationAnomaly.AnomalyType.GHOST_LIVE) {
                    hasLogicAnomaly = true;
                }
            }
            
            if (!liveOrders.isEmpty()) {
                com.martinfou.trading.core.Order latestLive = liveOrders.getLast();
                for (com.martinfou.trading.backtest.reconciliation.ReconciliationAnomaly anomaly : anomalies) {
                    if (anomaly.type() == com.martinfou.trading.backtest.reconciliation.ReconciliationAnomaly.AnomalyType.TIME_DRIFT &&
                        java.util.Objects.equals(latestLive.id(), anomaly.orderId())) {
                        hasTimeDriftOnLatest = true;
                        break;
                    }
                }
            }
            
            if (hasLogicAnomaly) {
                log.error("CRITICAL: Logic discrepancy detected for run {} (MISSING_LIVE or GHOST_LIVE). Triggering kill-switch and liquidating open positions.", runId);
                stop(runId, true);
            } else if (hasTimeDriftOnLatest) {
                int count = consecutiveTimeDrifts.merge(runId, 1, Integer::sum);
                log.warn("Execution TIME_DRIFT detected for run {} (consecutive count: {})", runId, count);
                if (count >= 3) {
                    log.error("CRITICAL: 3 consecutive TIME_DRIFT anomalies detected for run {}. Autopausing strategy.", runId);
                    pause(runId);
                }
            } else {
                consecutiveTimeDrifts.put(runId, 0);
            }
        } catch (Exception e) {
            log.error("Error during real-time trade reconciliation for run " + runId, e);
        }
    }

    private static double toDouble(Object obj) {
        if (obj == null) {
            return 0.0;
        }
        if (obj instanceof Number) {
            return ((Number) obj).doubleValue();
        }
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
