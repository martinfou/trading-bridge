package com.martinfou.trading.runtime;

/**
 * Starts the HTTP control plane on {@code CONTROL_PLANE_PORT} (default 8080).
 */
public final class ControlPlaneMain {

    private ControlPlaneMain() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("CONTROL_PLANE_PORT", "8080"));
        EventStoreConfig config = EventStoreConfig.fromRuntimeEnvironment();
        RuntimeDataPaths.ensureDataDirectories();
        RuntimeStores.Bundle stores = RuntimeStores.sqliteWithBroadcast(config);
        RunManager runManager = new RunManager(stores.eventStore(), stores.deploymentStore());
        restoreActiveRuns(runManager, config);
        reconcileCompletedRuns(runManager);
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
        Thread stdinWatcher = new Thread(() -> {
            try {
                int read;
                while ((read = System.in.read()) != -1) {
                    // consume input
                }
                System.out.println("Stdin EOF. Shutting down control plane...");
                System.exit(0);
            } catch (Exception e) {
                System.exit(0);
            }
        });
        stdinWatcher.setDaemon(true);
        stdinWatcher.start();

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
        System.out.println("Control plane listening on http://localhost:" + server.port());
        System.out.println("Event store: " + config.dbPath());
        System.out.println("WebSocket runs: ws://localhost:" + server.port() + "/ws/runs/{runId}");
    }

    static void restoreActiveRuns(RunManager runManager, EventStoreConfig config) {
        try {
            RunRecordStore store = runManager.runRecordStore();
            java.util.List<RunRecord> all = store.listAll();
            for (RunRecord record : all) {
                if (record.status() == RunRecord.Status.RUNNING || record.status() == RunRecord.Status.PAUSED) {
                    try {
                        System.out.println("Restoring active run " + record.runId() + " (" + record.strategyId() + " on " + record.symbol() + ")...");
                        runManager.restoreRun(record);
                        runManager.start(record.runId());
                    } catch (Exception e) {
                        System.err.println("Failed to restore run " + record.runId() + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to restore active runs from database: " + e.getMessage());
            e.printStackTrace();
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
                        System.err.println("Reconciliation warning: Run " + runId + " (" + record.strategyId() + ") has a mismatch between FILL events (" + fillCount + ") and trades count (" + tradeCount + "). Expected " + (2 * tradeCount) + " fills.");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to reconcile completed runs: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
