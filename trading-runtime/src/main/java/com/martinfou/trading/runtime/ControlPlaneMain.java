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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            runManager.close();
            stores.close();
        }));

        ControlPlaneServer server = new ControlPlaneServer(
            runManager, stores.hub(), promoteService, killSwitchService, port);
        System.out.println("Control plane listening on http://localhost:" + server.port());
        System.out.println("Event store: " + config.dbPath());
        System.out.println("WebSocket runs: ws://localhost:" + server.port() + "/ws/runs/{runId}");
    }
}
