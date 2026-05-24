package com.martinfou.trading.runtime;

/**
 * Starts the HTTP control plane on {@code CONTROL_PLANE_PORT} (default 8080).
 */
public final class ControlPlaneMain {

    private ControlPlaneMain() {}

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("CONTROL_PLANE_PORT", "8080"));
        RuntimeStores.Bundle stores = RuntimeStores.sqliteWithBroadcast(EventStoreConfig.defaults());
        RunManager runManager = new RunManager(stores.eventStore());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            runManager.close();
            stores.eventStore().close();
        }));

        ControlPlaneServer server = new ControlPlaneServer(runManager, stores.hub(), port);
        System.out.println("Control plane listening on http://localhost:" + server.port());
        System.out.println("WebSocket runs: ws://localhost:" + server.port() + "/ws/runs/{runId}");
    }
}
