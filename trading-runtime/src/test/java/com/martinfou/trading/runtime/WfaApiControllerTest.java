package com.martinfou.trading.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.runtime.wfa.*;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WfaApiControllerTest {

    private RuntimeStores.Bundle stores;
    private RunManager runManager;
    private PromoteService promoteService;
    private KillSwitchService killSwitchService;
    private ControlPlaneServer server;
    private HttpClient client;
    private int port;
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            try {
                server.close();
            } catch (Exception ignored) {}
        }
        if (runManager != null) {
            try {
                runManager.close();
            } catch (Exception ignored) {}
        }
    }

    @Test
    void testPostWalkForwardRunAndGetProgress() throws Exception {
        WfaRunRequest request = new WfaRunRequest(
            "LtCrossMomentum",
            "EUR_USD",
            "FOREX",
            "H1",
            "2024-01-01T00:00:00Z",
            "2024-06-01T00:00:00Z",
            60,
            20,
            false,
            10000.0,
            List.of(
                new ParameterRangeDto("fastPeriod", 5.0, 10.0, 5.0),
                new ParameterRangeDto("slowPeriod", 20.0, 30.0, 10.0)
            )
        );

        String jsonBody = MAPPER.writeValueAsString(request);

        // 1. POST /api/runs/walk-forward -> 202 Accepted
        HttpRequest postReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/runs/walk-forward"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();

        HttpResponse<String> postRes = client.send(postReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, postRes.statusCode());
        Map<?, ?> postMap = MAPPER.readValue(postRes.body(), Map.class);
        String wfaId = (String) postMap.get("wfaId");
        assertNotNull(wfaId);

        // 2. GET /api/runs/walk-forward/{id} -> 200 OK
        HttpRequest getProgReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/runs/walk-forward/" + wfaId))
            .GET()
            .build();

        HttpResponse<String> getProgRes = client.send(getProgReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getProgRes.statusCode());
        Map<?, ?> progMap = MAPPER.readValue(getProgRes.body(), Map.class);
        assertEquals(wfaId, progMap.get("wfaId"));

        // 3. GET /api/runs/walk-forward -> 200 OK (List)
        HttpRequest listReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/runs/walk-forward"))
            .GET()
            .build();

        HttpResponse<String> listRes = client.send(listReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listRes.statusCode());
        List<?> list = MAPPER.readValue(listRes.body(), List.class);
        assertFalse(list.isEmpty());
    }

    @Test
    void testGetNonExistentWfaRunReturns404() throws Exception {
        HttpRequest getReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/runs/walk-forward/non-existent-id"))
            .GET()
            .build();

        HttpResponse<String> getRes = client.send(getReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, getRes.statusCode());
    }
}
