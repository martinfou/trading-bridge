package com.martinfou.trading.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the HTTP control plane on {@code CONTROL_PLANE_PORT} (default 8080).
 */
public final class ControlPlaneMain {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneMain.class);

    private ControlPlaneMain() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("CONTROL_PLANE_PORT", "8080"));
        EventStoreConfig config = EventStoreConfig.fromRuntimeEnvironment();
        RuntimeDataPaths.ensureDataDirectories();
        RuntimeStores.Bundle stores = RuntimeStores.sqliteWithBroadcast(config);
        RunManager runManager = new RunManager(stores.eventStore(), stores.deploymentStore());
        PromoteGateThresholds thresholds = PromoteGateThresholds.loadDefault();
        PromoteService promoteService = new PromoteService(
            runManager,
            stores.deploymentStore(),
            thresholds,
            java.time.Clock.systemUTC(),
            ValidationModules.loadDefault());
        KillSwitchService killSwitchService = new KillSwitchService(
            runManager,
            stores.deploymentStore(),
            runManager.killSwitchRegistry());

        HistoricalDataService historicalDataService = new HistoricalDataService();
        historicalDataService.startWeeklyScheduler();

        DailyReconciliationService dailyReconciliationService = new DailyReconciliationService(runManager);
        dailyReconciliationService.start();

        DriftSignalService driftSignalService = new DriftSignalService(runManager, stores.deploymentStore());
        DriftReporter driftReporter = new DriftReporter(driftSignalService);
        driftReporter.start();

        // Stdin watcher daemon thread: exits JVM if standard input closes (EOF) to prevent zombie processes
        // Skip starting this if running in background without a TTY (System.console() == null) or DISABLE_STDIN_WATCHER is true
        if (System.console() != null && !"true".equalsIgnoreCase(System.getenv("DISABLE_STDIN_WATCHER"))) {
            Thread stdinWatcher = new Thread(() -> {
                try {
                    int read;
                    while ((read = System.in.read()) != -1) {
                        // consume input
                    }
                    log.info("Stdin EOF. Shutting down control plane...");
                    System.exit(0);
                } catch (Exception e) {
                    System.exit(0);
                }
            });
            stdinWatcher.setDaemon(true);
            stdinWatcher.start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            runManager.close();
            historicalDataService.close();
            dailyReconciliationService.close();
            driftReporter.stop();
            stores.close();
        }));

        ControlPlaneServer server = new ControlPlaneServer(
            runManager,
            stores.hub(),
            promoteService,
            killSwitchService,
            new ControlSummaryService(runManager, stores.deploymentStore()),
            new SqBridgeService(stores.eventStore()),
            new WeeklyBuilderService(stores.eventStore()),
            historicalDataService,
            port
        );
        log.info("Control plane listening on http://localhost:{}", server.port());
        log.info("Event store: {}", config.dbPath());
        log.info("WebSocket runs: ws://localhost:{}/ws/runs/{{}}", server.port(), "runId");

        Thread.ofVirtual().start(() -> {
            try {
                runManager.setReconciliationState(RunManager.ReconciliationState.IN_PROGRESS);
                restoreActiveRuns(runManager, config);
                reconcileCompletedRuns(runManager);
                runManager.setReconciliationState(RunManager.ReconciliationState.COMPLETED);
            } catch (Exception e) {
                runManager.setReconciliationState(RunManager.ReconciliationState.FAILED);
                log.error("Failed to reconcile runs on startup", e);
            }
        });
    }

    static void restoreActiveRuns(RunManager runManager, EventStoreConfig config) {
        try {
            RunRecordStore store = runManager.runRecordStore();
            java.util.List<RunRecord> all = store.listAll();
            for (RunRecord record : all) {
                if (record.status() == RunRecord.Status.RUNNING || record.status() == RunRecord.Status.PAUSED) {
                    try {
                        log.info("Restoring active run {} ({} on {})...", record.runId(), record.strategyId(), record.symbol());
                        runManager.restoreRun(record);
                        runManager.start(record.runId());
                    } catch (Exception e) {
                        log.error("Failed to restore run {}", record.runId(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to restore active runs from database", e);
        }
    }

    static void reconcileCompletedRuns(RunManager runManager) {
        try {
            RunRecordStore store = runManager.runRecordStore();
            java.util.List<RunRecord> all = store.listAll();
            for (RunRecord record : all) {
                if (record.status() == RunRecord.Status.COMPLETED) {
                    String runId = record.runId();
                    long fillCount = runManager.eventStore().replayAll(runId).stream()
                        .filter(e -> e.type() == com.martinfou.trading.backtest.events.RunEventType.FILL)
                        .count();
                    long tradeCount = runManager.tradeStore().getTrades(runId).size();
                    if (fillCount != 2 * tradeCount) {
                        log.warn("Reconciliation warning: Run {} ({}) has a mismatch between FILL events ({}) and trades count ({}). Expected {} fills.", runId, record.strategyId(), fillCount, tradeCount, 2 * tradeCount);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to reconcile completed runs", e);
        }
    }
}
