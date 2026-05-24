package com.martinfou.trading.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneServerTest {

    private EventStore eventStore;
    private RunManager runManager;
    private ControlPlaneServer server;
    private HttpClient http;

    @BeforeEach
    void setUp() {
        eventStore = EventStores.inMemory();
        runManager = new RunManager(eventStore);
        server = new ControlPlaneServer(runManager, 0);
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.close();
        runManager.close();
        eventStore.close();
    }

    @Test
    void health_returnsOk() throws Exception {
        HttpResponse<String> response = get("/api/health");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"ok\""));
        assertTrue(response.body().contains(ControlPlaneServer.VERSION));
    }

    @Test
    void strategies_listsCatalog() throws Exception {
        HttpResponse<String> response = get("/api/strategies");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("LondonOpenRangeBreakout"));
    }

    @Test
    void postRun_sampleBacktest_completesWithEvents() throws Exception {
        String body = """
            {
              "strategyId": "LondonOpenRangeBreakout",
              "symbol": "EUR_USD",
              "mode": "BACKTEST",
              "barsSource": { "type": "sample", "count": 500 }
            }
            """;
        HttpResponse<String> created = post("/api/runs", body);
        assertEquals(202, created.statusCode());
        String runId = extractJsonField(created.body(), "runId");

        RunRecord record = waitForCompletion(runId, Duration.ofSeconds(10));
        assertEquals(RunRecord.Status.COMPLETED, record.status());

        HttpResponse<String> runResponse = get("/api/runs/" + runId);
        assertEquals(200, runResponse.statusCode());
        assertTrue(runResponse.body().contains("\"status\":\"COMPLETED\""));
        assertTrue(runResponse.body().contains("\"totalTrades\""));

        HttpResponse<String> events = get("/api/runs/" + runId + "/events?limit=10");
        assertEquals(200, events.statusCode());
        assertTrue(events.body().contains("\"sequence\""));
        assertTrue(events.body().contains("RUN_STARTED"));
        assertTrue(events.body().contains("RUN_ENDED"));
    }

    @Test
    void getRun_unknownId_returns404() throws Exception {
        HttpResponse<String> response = get("/api/runs/no-such-run");
        assertEquals(404, response.statusCode());
    }

    private RunRecord waitForCompletion(String runId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            RunRecord record = runManager.getRun(runId).orElseThrow();
            if (record.status() != RunRecord.Status.RUNNING) {
                return record;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Run did not complete in time: " + runId);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + server.port() + path))
            .GET()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + server.port() + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String extractJsonField(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) {
            throw new IllegalArgumentException("Field not found: " + field + " in " + json);
        }
        start += needle.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
