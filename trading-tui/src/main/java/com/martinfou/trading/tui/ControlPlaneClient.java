package com.martinfou.trading.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thin HTTP client for the Java control plane (Story 13.6). */
public final class ControlPlaneClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;

    public ControlPlaneClient(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    ControlPlaneClient(String baseUrl, HttpClient http) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
    }

    public static ControlPlaneClient fromEnvironment() {
        String url = System.getenv("CONTROL_PLANE_URL");
        if (url == null || url.isBlank()) {
            url = "http://localhost:" + System.getenv().getOrDefault("CONTROL_PLANE_PORT", "8080");
        }
        return new ControlPlaneClient(url);
    }

    public JsonNode sqBridgeStatus() throws IOException, InterruptedException {
        return getJson("/api/sq-bridge/status");
    }

    public JsonNode processSqInbox() throws IOException, InterruptedException {
        String json = MAPPER.writeValueAsString(Map.of());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/sq-bridge/process-inbox"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return parseProcessInboxResponse(response);
    }

    public JsonNode health() throws IOException, InterruptedException {
        return getJson("/api/health");
    }

    public JsonNode listStrategies() throws IOException, InterruptedException {
        return getJson("/api/strategies");
    }

    public JsonNode controlSummary() throws IOException, InterruptedException {
        return getJson("/api/control/summary");
    }

    public JsonNode promoteReadiness(String strategyId) throws IOException, InterruptedException {
        return getJson("/api/strategies/" + encodePath(strategyId) + "/promote-readiness");
    }

    public JsonNode getRun(String runId) throws IOException, InterruptedException {
        return getJson("/api/runs/" + encodePath(runId));
    }

    public JsonNode listEvents(String runId, long afterSequence, int limit)
        throws IOException, InterruptedException {
        return getJson("/api/runs/" + encodePath(runId) + "/events?afterSequence="
            + afterSequence + "&limit=" + limit);
    }

    public JsonNode startBacktest(String strategyId, String symbol, int barCount)
        throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strategyId", strategyId);
        body.put("symbol", symbol);
        body.put("mode", "BACKTEST");
        body.put("barsSource", Map.of("type", "sample", "count", barCount));
        body.put("capital", 100_000.0);
        return postJson("/api/runs", body);
    }

    public JsonNode promote(String strategyId, String targetMode, String runId)
        throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetMode", targetMode);
        if (runId != null && !runId.isBlank()) {
            body.put("runId", runId);
        }
        return postJson("/api/strategies/" + encodePath(strategyId) + "/promote", body);
    }

    public JsonNode kill(String strategyId, String actor, String reason)
        throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
            "actor", actor != null && !actor.isBlank() ? actor : "tui",
            "reason", reason != null && !reason.isBlank() ? reason : "operator kill from TUI");
        return postJson("/api/strategies/" + encodePath(strategyId) + "/kill", body);
    }

    public String baseUrl() {
        return baseUrl;
    }

    private JsonNode getJson(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return parseResponse(response);
    }

    private JsonNode postJson(String path, Object body) throws IOException, InterruptedException {
        String json = MAPPER.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return parseResponse(response);
    }

    private static JsonNode parseProcessInboxResponse(HttpResponse<String> response) throws IOException {
        JsonNode node = MAPPER.readTree(response.body());
        int code = response.statusCode();
        if (code == 202 || code == 409) {
            return node;
        }
        if (code >= 400) {
            String error = node.has("error") ? node.get("error").asText() : response.body();
            throw new ControlPlaneException(code, error);
        }
        return node;
    }

    private static JsonNode parseResponse(HttpResponse<String> response) throws IOException {
        JsonNode node = MAPPER.readTree(response.body());
        if (response.statusCode() >= 400) {
            String error = node.has("error") ? node.get("error").asText() : response.body();
            throw new ControlPlaneException(response.statusCode(), error);
        }
        return node;
    }

    private static String encodePath(String segment) {
        return segment.replace(" ", "%20");
    }

    static final class ControlPlaneException extends IOException {
        private final int statusCode;

        ControlPlaneException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }
}
