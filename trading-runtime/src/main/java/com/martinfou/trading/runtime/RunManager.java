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
public final class RunManager implements RunLifecycle, AutoCloseable {

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
        Double weeklyLossLimitPct
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
            String brokerAccountId
        ) {
            this(strategyId, symbol, mode, barsSource, capital, lotSize, commissionPerTrade, slippagePct,
                executionLabel, brokerAccountId, null, null, null, null, null);
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
                executionLabel, null, null, null, null, null, null);
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
                executionLabel, null, null, null, null, null, null);
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
    private final Map<String, RunConfigSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, AutoCloseable> activeExecutors = new ConcurrentHashMap<>();
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

    public BrokerAccountRegistry brokerAccountRegistry() {
        return brokerAccountRegistry;
    }

    public Optional<DailyDrawdownMetrics> dailyDrawdownMetrics(String runId) {
        return Optional.ofNullable(dailyDrawdownByRun.get(runId));
    }

    public Optional<AutoCloseable> getActiveExecutor(String runId) {
        return Optional.ofNullable(activeExecutors.get(runId));
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
        return record;
    }

    @Override
    public RunRecord stop(String runId) {
        return stop(runId, false);
    }

    public RunRecord stop(String runId, boolean liquidate) {
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
            if (configSnapshot.resolvedExecutionLabel().isOandaBroker() && System.getProperty("trading.bridge.test") == null) {
                Broker broker = brokerFactory.create(configSnapshot);
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
                    configSnapshot,
                    strategy,
                    broker,
                    restClient,
                    eventStore,
                    killSwitchRegistry,
                    streamClient,
                    riskContext
                );

                activeExecutors.put(runId, oandaExecutor);
                oandaExecutor.start();

                while (record.status() == RunRecord.Status.RUNNING) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                oandaExecutor.stop();
                activeExecutors.remove(runId);

                double finalEquity = broker.getAccountState().equity();
                result = BacktestResult.builder()
                    .initialCapital(capital)
                    .finalEquity(finalEquity)
                    .totalTrades(0)
                    .totalReturnPct(capital > 0 ? (finalEquity - capital) / capital * 100.0 : 0.0)
                    .build();
            } else if (isBrokerBackedRun(configSnapshot)) {
                Broker broker = brokerFactory.create(configSnapshot);
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
}
