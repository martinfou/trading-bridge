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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            runManager.close();
            historicalDataService.close();
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

    private static void restoreActiveRuns(RunManager runManager, EventStoreConfig config) {
        String dbPath = config.dbPath().toString();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            String sql = """
                SELECT DISTINCT run_id, json_line 
                FROM events 
                WHERE json_extract(json_line, '$.type') = 'RUN_STARTED' 
                  AND (json_extract(json_line, '$.mode') = 'PAPER' OR json_extract(json_line, '$.mode') = 'LIVE')
                  AND run_id NOT IN (
                      SELECT run_id 
                      FROM events 
                      WHERE json_extract(json_line, '$.type') = 'RUN_ENDED' 
                         OR json_extract(json_line, '$.type') = 'ERROR'
                  )
                """;
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql);
                 java.sql.ResultSet rs = stmt.executeQuery()) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                while (rs.next()) {
                    String runId = rs.getString("run_id");
                    String jsonLine = rs.getString("json_line");
                    try {
                        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(jsonLine);
                        com.fasterxml.jackson.databind.JsonNode snapshotNode = root.path("payload").path("configSnapshot");
                        if (!snapshotNode.isMissingNode()) {
                            RunConfigSnapshot snapshot = mapper.treeToValue(snapshotNode, RunConfigSnapshot.class);
                            System.out.println("Restoring active run " + runId + " (" + snapshot.strategyId() + " on " + snapshot.symbol() + ")...");
                            runManager.restoreRun(runId, snapshot);
                            runManager.start(runId);
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to restore run " + runId + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to restore active runs from database: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
