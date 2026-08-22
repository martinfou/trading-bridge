package com.martinfou.trading.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrokerAccountSummaryApiTest {

    private RuntimeStores.Bundle stores;
    private RunManager runManager;
    private PromoteService promoteService;
    private KillSwitchService killSwitchService;
    private ControlPlaneServer server;
    private HttpClient client;
    private int port;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        System.setProperty("TRADING_BRIDGE_EVENT_STORE", tempDir.resolve("test-events.db").toAbsolutePath().toString());

        stores = RuntimeStores.inMemoryWithBroadcast();
        runManager = new RunManager(
            stores.eventStore(),
            config -> new com.martinfou.trading.broker.FakeBroker(100_000.0),
            stores.deploymentStore(),
            BrokerAccountRegistry.loadDefault()
        );
        promoteService = new PromoteService(
            runManager,
            stores.deploymentStore(),
            PromoteGateThresholds.DEFAULT,
            java.time.Clock.systemUTC(),
            List.of(),
            () -> false);
        killSwitchService = new KillSwitchService(
            runManager,
            stores.deploymentStore(),
            runManager.killSwitchRegistry());

        server = new ControlPlaneServer(runManager, stores.hub(), promoteService, killSwitchService, 0);
        port = server.port();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        System.clearProperty("TRADING_BRIDGE_EVENT_STORE");
    }

    @Test
    void testGetPortfolioMarginsEndpoint() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/portfolio/margins"))
            .GET()
            .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("totalEquity"));
        assertTrue(resp.body().contains("marginUtilizationPct"));
    }
}
