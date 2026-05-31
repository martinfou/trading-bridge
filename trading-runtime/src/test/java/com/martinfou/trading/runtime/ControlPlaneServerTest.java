package com.martinfou.trading.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneServerTest {

    private RuntimeStores.Bundle stores;
    private RunManager runManager;
    private PromoteService promoteService;
    private KillSwitchService killSwitchService;
    private ControlPlaneServer server;
    private HttpClient http;

    @BeforeEach
    void setUp() {
        stores = RuntimeStores.inMemoryWithBroadcast();
        runManager = new RunManager(stores.eventStore(), stores.deploymentStore());
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
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.close();
        runManager.close();
        stores.close();
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
        assertTrue(runResponse.body().contains("\"executionLabel\":\"BACKTEST\""));
        assertTrue(runResponse.body().contains("\"totalTrades\""));

        HttpResponse<String> events = get("/api/runs/" + runId + "/events?limit=10");
        assertEquals(200, events.statusCode());
        assertTrue(events.body().contains("\"sequence\""));
        assertTrue(events.body().contains("RUN_STARTED"));
        assertTrue(events.body().contains("RUN_ENDED"));
    }

    @Test
    void events_invalidAfterSequence_returns400() throws Exception {
        HttpResponse<String> created = post("/api/runs", """
            {
              "strategyId": "LondonOpenRangeBreakout",
              "symbol": "EUR_USD",
              "mode": "BACKTEST",
              "barsSource": { "type": "sample", "count": 100 }
            }
            """);
        String runId = extractJsonField(created.body(), "runId");
        waitForCompletion(runId, Duration.ofSeconds(10));

        HttpResponse<String> bad = get("/api/runs/" + runId + "/events?afterSequence=not-a-number");
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("Invalid numeric parameter"));
    }

    @Test
    void webSocket_replaysRunEvents() throws Exception {
        String body = """
            {
              "strategyId": "LondonOpenRangeBreakout",
              "symbol": "EUR_USD",
              "mode": "BACKTEST",
              "barsSource": { "type": "sample", "count": 500 }
            }
            """;
        HttpResponse<String> created = post("/api/runs", body);
        String runId = extractJsonField(created.body(), "runId");
        waitForCompletion(runId, Duration.ofSeconds(10));

        List<String> messages = new ArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        WebSocket ws = http.newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:" + server.port() + "/ws/runs/" + runId), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    messages.add(data.toString());
                    if (messages.size() >= 2) {
                        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "ok");
                    }
                    return WebSocket.Listener.super.onText(webSocket, data, last);
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    done.complete(null);
                    return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                }
            }).join();

        done.get(5, TimeUnit.SECONDS);
        ws.abort();

        assertEquals(2, messages.size());
        assertTrue(messages.stream().anyMatch(m -> m.contains("RUN_STARTED")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("RUN_ENDED")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"executionLabel\":\"BACKTEST\"")));
    }

    @Test
    void promote_toPaper_afterBacktest_succeeds() throws Exception {
        String runId = startSampleBacktest();
        waitForCompletion(runId, Duration.ofSeconds(10));

        HttpResponse<String> response = post(
            "/api/strategies/LondonOpenRangeBreakout/promote",
            "{\"targetMode\":\"PAPER\",\"runId\":\"" + runId + "\"}");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"promoted\":true"));
        assertTrue(response.body().contains("\"mode\":\"PAPER\""));
        assertTrue(response.body().contains("\"executionLabel\":\"PAPER_STUB\""));

        HttpResponse<String> strategies = get("/api/strategies");
        assertTrue(strategies.body().contains("\"deployedMode\":\"PAPER\""));
        assertTrue(strategies.body().contains("\"executionLabel\":\"PAPER_STUB\""));
    }

    @Test
    void deployments_returnsExecutionLabel() throws Exception {
        String runId = startSampleBacktest();
        waitForCompletion(runId, Duration.ofSeconds(10));
        post("/api/strategies/LondonOpenRangeBreakout/promote",
            "{\"targetMode\":\"PAPER\",\"runId\":\"" + runId + "\"}");

        HttpResponse<String> response = get("/api/strategies/LondonOpenRangeBreakout/deployments");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"executionLabel\":\"PAPER_STUB\""));
    }

    @Test
    void export_includesExecutionLabelMetadata() throws Exception {
        String runId = startSampleBacktest();
        waitForCompletion(runId, Duration.ofSeconds(10));

        HttpResponse<String> response = get("/api/runs/" + runId + "/export");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"type\":\"EVIDENCE_METADATA\""));
        assertTrue(response.body().contains("\"executionLabel\":\"BACKTEST\""));
    }

    @Test
    void export_html_returnsSelfContainedReport() throws Exception {
        String runId = startSampleBacktest();
        waitForCompletion(runId, Duration.ofSeconds(10));

        HttpResponse<String> response = get("/api/runs/" + runId + "/export?format=html");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("text/html"));
        assertTrue(response.body().startsWith("<!DOCTYPE html>"));
        assertTrue(response.body().contains("DISCLAIMER"));
        assertFalse(response.body().contains("cdn.jsdelivr.net"));
    }

    @Test
    void brokerAccounts_masksSecrets() throws Exception {
        HttpResponse<String> response = get("/api/broker-accounts");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"accounts\""));
        assertFalse(response.body().toLowerCase().contains("api_token"));
        assertFalse(response.body().contains("OANDA_API"));
    }

    @Test
    void promoteReadiness_returnsStructuredAssessment() throws Exception {
        String runId = startSampleBacktest();
        waitForCompletion(runId, Duration.ofSeconds(10));

        HttpResponse<String> response = get("/api/strategies/LondonOpenRangeBreakout/promote-readiness");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"schemaVersion\":1"));
        assertTrue(response.body().contains("\"targetMode\":\"PAPER\""));
        assertTrue(response.body().contains("\"gates\""));
        assertTrue(response.body().contains("\"reconciliation\""));
        assertTrue(response.body().contains("\"killSwitchActive\""));
    }

    @Test
    void promote_toLive_fromStub_returns422() throws Exception {
        String runId = startSampleBacktest();
        waitForCompletion(runId, Duration.ofSeconds(10));
        post("/api/strategies/LondonOpenRangeBreakout/promote",
            "{\"targetMode\":\"PAPER\",\"runId\":\"" + runId + "\"}");

        HttpResponse<String> response = post(
            "/api/strategies/LondonOpenRangeBreakout/promote",
            "{\"targetMode\":\"LIVE\"}");
        assertEquals(422, response.statusCode());
        assertTrue(response.body().toLowerCase().contains("stub does not count"));
    }

    @Test
    void kill_recordsOperatorActionInExport() throws Exception {
        stores.deploymentStore().save(new DeploymentRecord(
            "LondonOpenRangeBreakout",
            com.martinfou.trading.backtest.RunMode.PAPER,
            Instant.parse("2024-01-01T00:00:00Z"),
            "run-bt",
            List.of(),
            ExecutionLabel.PAPER_OANDA));

        RunConfigSnapshot config = new RunConfigSnapshot(
            "LondonOpenRangeBreakout",
            "EUR_USD",
            "LIVE",
            "sample",
            300,
            null,
            100_000.0,
            null,
            null,
            ExecutionLabel.LIVE_OANDA.name());
        RunRecord run = runManager.register(config);
        run.markRunning();

        HttpResponse<String> killResponse = post(
            "/api/strategies/LondonOpenRangeBreakout/kill",
            "{\"actor\":\"martin\",\"reason\":\"manual halt\"}");
        assertEquals(202, killResponse.statusCode());
        assertTrue(killResponse.body().contains("\"killed\":true"));
        assertTrue(killResponse.body().contains(run.runId()));

        HttpResponse<String> export = get("/api/runs/" + run.runId() + "/export");
        assertEquals(200, export.statusCode());
        assertTrue(export.body().contains("OPERATOR_ACTION"));
        assertTrue(export.body().contains("\"actor\":\"martin\""));
        assertTrue(export.body().contains("manual halt"));
    }

    @Test
    void kill_beforeRunStart_blocksOrders() throws Exception {
        KillSwitchRegistry registry = new KillSwitchRegistry();
        RunManager brokerManager = new RunManager(
            stores.eventStore(),
            config -> new com.martinfou.trading.broker.FakeBroker(100_000.0),
            registry);
        KillSwitchService brokerKill = new KillSwitchService(
            brokerManager, stores.deploymentStore(), registry);
        ControlPlaneServer brokerServer = new ControlPlaneServer(
            brokerManager, stores.hub(), promoteService, brokerKill, 0);

        try {
            stores.deploymentStore().save(new DeploymentRecord(
                "LondonOpenRangeBreakout",
                com.martinfou.trading.backtest.RunMode.LIVE,
                Instant.parse("2024-01-01T00:00:00Z"),
                "run-bt",
                List.of(),
                ExecutionLabel.LIVE_OANDA));

            registry.kill("LondonOpenRangeBreakout");

            String runId = brokerManager.startRun(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "LIVE",
                new BarSourceResolver.BarsSource("sample", 300, null),
                100_000.0,
                null,
                null,
                ExecutionLabel.LIVE_OANDA.name()));

            waitForManagerCompletion(brokerManager, runId, Duration.ofSeconds(10));

            var events = brokerManager.eventStore().replayAll(runId);
            assertTrue(events.stream().noneMatch(e -> e.type() == com.martinfou.trading.backtest.events.RunEventType.FILL));
            assertTrue(events.stream().anyMatch(e -> e.type() == com.martinfou.trading.backtest.events.RunEventType.REJECT));
        } finally {
            brokerServer.close();
            brokerManager.close();
        }
    }

    @Test
    void promote_toPaper_withoutRun_returns422() throws Exception {
        HttpResponse<String> response = post(
            "/api/strategies/LondonOpenRangeBreakout/promote",
            "{\"targetMode\":\"PAPER\"}");
        assertEquals(422, response.statusCode());
        assertTrue(response.body().contains("\"promoted\":false"));
    }

    private String startSampleBacktest() throws Exception {
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
        return extractJsonField(created.body(), "runId");
    }

    @Test
    void controlSummary_returnsSchemaAndExecutionLabels() throws Exception {
        String runId = startSampleBacktest();
        waitForCompletion(runId, Duration.ofSeconds(10));

        HttpResponse<String> response = get("/control/summary");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"schemaVersion\":1"));
        assertTrue(response.body().contains("\"executionLabel\":\"BACKTEST\""));
        assertTrue(response.body().contains("\"executionLabelCatalog\""));
        assertTrue(response.body().contains("\"executionLabelMeta\""));
        assertTrue(response.body().contains("\"displayName\":\"Backtest\""));
        assertTrue(response.body().contains("\"freshness\""));
        assertTrue(response.body().contains("\"signals\""));
        assertTrue(response.body().contains(runId));
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

    private static RunRecord waitForManagerCompletion(
        RunManager manager,
        String runId,
        Duration timeout
    ) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            RunRecord record = manager.getRun(runId).orElseThrow();
            if (record.status() != RunRecord.Status.RUNNING) {
                return record;
            }
            Thread.sleep(25);
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
