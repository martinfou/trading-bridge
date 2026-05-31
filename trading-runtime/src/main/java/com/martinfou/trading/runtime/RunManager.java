package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts strategy runs asynchronously and persists {@link RunEvent} records to the {@link EventStore}.
 * Lifecycle-only orchestration — promote, gaps, and deployments are handled by other services.
 */
public final class RunManager implements RunLifecycle, AutoCloseable {

    public record StartRunRequest(
        String strategyId,
        String symbol,
        String mode,
        BarSourceResolver.BarsSource barsSource,
        Double capital,
        Double commissionPerTrade,
        Double slippagePct,
        String executionLabel,
        String brokerAccountId
    ) {
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
            this(strategyId, symbol, mode, barsSource, capital, commissionPerTrade, slippagePct, executionLabel, null);
        }
    }

    private static final double DEFAULT_CAPITAL = 100_000.0;

    private final EventStore eventStore;
    private final BrokerFactory brokerFactory;
    private final boolean requireOandaCredentials;
    private final KillSwitchRegistry killSwitchRegistry;
    private final Optional<DeploymentStore> deploymentStore;
    private final BrokerAccountRegistry brokerAccountRegistry;
    private final Map<String, DailyDrawdownMetrics> dailyDrawdownByRun = new ConcurrentHashMap<>();
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final Map<String, RunConfigSnapshot> snapshots = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RunTransitionListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

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
        this.eventStore = eventStore;
        this.brokerFactory = brokerFactory;
        this.requireOandaCredentials = requireOandaCredentials;
        this.killSwitchRegistry = killSwitchRegistry != null ? killSwitchRegistry : new KillSwitchRegistry();
        this.deploymentStore = deploymentStore != null ? deploymentStore : Optional.empty();
        this.brokerAccountRegistry = brokerAccountRegistry != null
            ? brokerAccountRegistry
            : BrokerAccountRegistry.loadDefault();
    }

    public KillSwitchRegistry killSwitchRegistry() {
        return killSwitchRegistry;
    }

    public Optional<DailyDrawdownMetrics> dailyDrawdownMetrics(String runId) {
        return Optional.ofNullable(dailyDrawdownByRun.get(runId));
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
            List<Bar> bars;
            try {
                bars = loadBars(config);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load bars for run " + runId, e);
            }
            if (bars.isEmpty()) {
                throw new IllegalArgumentException("No bars loaded for run");
            }
            double capital = config.capital() != null ? config.capital() : DEFAULT_CAPITAL;
            record.markRunning();
            notifyTransition(before, record, RunTransition.START);
            RunConfigSnapshot snapshot = config;
            executor.submit(() -> executeRun(runId, snapshot, bars, capital, record));
        } else {
            record.markRunning();
            notifyTransition(before, record, RunTransition.RESUME);
        }
        return record;
    }

    @Override
    public RunRecord stop(String runId) {
        RunRecord record = requireRun(runId);
        RunRecord before = record;
        return switch (record.status()) {
            case CREATED -> {
                record.markFailed("stopped before start");
                notifyTransition(before, record, RunTransition.STOP);
                yield record;
            }
            case RUNNING -> throw new IllegalStateException(
                "Cannot stop run " + runId + " while execution is in progress (Epic 16)");
            case PAUSED -> {
                record.markFailed("stopped while paused");
                notifyTransition(before, record, RunTransition.STOP);
                yield record;
            }
            default -> throw new IllegalStateException(
                "Cannot stop run " + runId + " from status " + record.status());
        };
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
        String symbol = request.symbol() != null && !request.symbol().isBlank()
            ? request.symbol()
            : StrategyCatalog.defaultSymbol(request.strategyId());
        RunConfigSnapshot configSnapshot = RunConfigSnapshot.fromRequest(request, symbol);
        RunConfigSnapshot resolved = resolveBrokerAccount(request.strategyId(), request.brokerAccountId(), configSnapshot);
        validate(request, resolved);
        RunRecord record = register(resolved);
        start(record.runId());
        return record.runId();
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

    public EventStore eventStore() {
        return eventStore;
    }

    @Override
    public void close() {
        executor.close();
    }

    private void executeRun(
        String runId,
        RunConfigSnapshot configSnapshot,
        List<Bar> bars,
        double capital,
        RunRecord record
    ) {
        RunRecord before = snapshotRecord(record);
        try {
            RunMode runMode = RunMode.valueOf(configSnapshot.mode().toUpperCase());
            com.martinfou.trading.backtest.BacktestResult result;
            if (isBrokerBackedRun(configSnapshot)) {
                Broker broker = brokerFactory.create(configSnapshot);
                Strategy strategy = StrategyCatalog.create(configSnapshot.strategyId(), configSnapshot.symbol());
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
                    runId, configSnapshot, bars, capital, strategy, broker, eventStore, killSwitchRegistry, null, riskContext);
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
            if (record.status() == RunRecord.Status.RUNNING) {
                record.noteEventAt(latestEventTimestamp(runId).orElse(Instant.now()));
                record.markCompleted(Map.of(
                    "totalTrades", result.totalTrades(),
                    "totalReturnPct", result.totalReturnPct(),
                    "finalEquity", result.finalEquity(),
                    "maxDrawdownPct", result.maxDrawdownPct()));
                notifyTransition(before, record, RunTransition.COMPLETE);
            } else if (record.status() == RunRecord.Status.PAUSED) {
                record.noteEventAt(latestEventTimestamp(runId).orElse(Instant.now()));
            }
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (hasTerminalEvent(runId, RunEventType.ERROR)) {
                latestEventTimestamp(runId).ifPresent(record::noteEventAt);
            }
            if (record.status() == RunRecord.Status.RUNNING || record.status() == RunRecord.Status.PAUSED) {
                record.markFailed(msg);
                notifyTransition(before, record, RunTransition.FAIL);
            }
        }
    }

    private static RunRecord snapshotRecord(RunRecord record) {
        return record;
    }

    private void notifyTransition(RunRecord before, RunRecord after, RunTransition cause) {
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

    private static List<Bar> loadBars(RunConfigSnapshot config) throws IOException {
        if (config.barsSourceType() == null) {
            throw new IllegalArgumentException("barsSourceType is required in config snapshot");
        }
        Integer year = null;
        if (config.barsSourceYear() != null && !config.barsSourceYear().isBlank()) {
            year = Integer.parseInt(config.barsSourceYear());
        }
        var source = new BarSourceResolver.BarsSource(
            config.barsSourceType(),
            config.barsSourceCount(),
            year);
        return BarSourceResolver.load(source, config.symbol());
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
        try {
            RunMode.valueOf(request.mode().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid mode: " + request.mode());
        }
        RunMode mode = RunMode.valueOf(request.mode().toUpperCase());
        validateExecutionLabelForMode(mode, configSnapshot.resolvedExecutionLabel());
        validateBrokerCredentials(configSnapshot);
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
}
