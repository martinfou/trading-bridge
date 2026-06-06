package com.martinfou.trading.tui;

import com.fasterxml.jackson.core.JsonProcessingException;
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

    public JsonNode weeklyBuilderStatus() throws IOException, InterruptedException {
        return getJson("/api/weekly-builder/status");
    }

    public JsonNode triggerWeeklyPlan() throws IOException, InterruptedException {
        return triggerWeeklyBuilder("/api/weekly-builder/plan");
    }

    public JsonNode triggerWeeklyCompile() throws IOException, InterruptedException {
        return triggerWeeklyBuilder("/api/weekly-builder/compile");
    }

    public JsonNode triggerWeeklyDeploy() throws IOException, InterruptedException {
        return triggerWeeklyBuilder("/api/weekly-builder/deploy");
    }

    private JsonNode triggerWeeklyBuilder(String path) throws IOException, InterruptedException {
        String json = MAPPER.writeValueAsString(Map.of());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
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

    public JsonNode listDataSymbols() throws IOException, InterruptedException {
        return getJson("/api/data/symbols");
    }

    public JsonNode dataAvailability(String symbol) throws IOException, InterruptedException {
        return getJson("/api/data/availability/" + encodePath(symbol));
    }

    JsonNode emptyJsonObject() {
        return MAPPER.createObjectNode();
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

    public JsonNode startBacktest(
        String strategyId,
        String symbol,
        Map<String, Object> barsSource,
        Double capital,
        Double lotSize
    ) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strategyId", strategyId);
        body.put("symbol", symbol);
        body.put("mode", "BACKTEST");
        body.put("barsSource", barsSource);
        body.put("capital", capital != null ? capital : TuiDefaults.STARTING_CAPITAL);
        if (lotSize != null) {
            body.put("lotSize", lotSize);
        }
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
        return parseResponse(response, true);
    }

    private static JsonNode parseResponse(HttpResponse<String> response) throws IOException {
        return parseResponse(response, false);
    }

    private static JsonNode parseResponse(HttpResponse<String> response, boolean allowAccepted)
        throws IOException {
        String body = response.body() == null ? "" : response.body();
        int code = response.statusCode();
        if (code >= 400 && !(allowAccepted && (code == 202 || code == 409))) {
            throw new ControlPlaneException(code, errorMessage(body, code));
        }
        if (body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            if (code >= 400 && !(allowAccepted && (code == 202 || code == 409))) {
                String error = node.has("error") ? node.get("error").asText() : body;
                throw new ControlPlaneException(code, error);
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new IOException("Non-JSON response (HTTP " + code + "): " + summarizeBody(body), e);
        }
    }

    private static String errorMessage(String body, int code) {
        if (body == null || body.isBlank()) {
            return "HTTP " + code;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node.has("error")) {
                return node.get("error").asText();
            }
        } catch (JsonProcessingException ignored) {
            // plain-text error from server (e.g. Javalin 404)
        }
        return summarizeBody(body);
    }

    private static String summarizeBody(String body) {
        String oneLine = body.strip().replaceAll("\\s+", " ");
        return oneLine.length() > 200 ? oneLine.substring(0, 197) + "…" : oneLine;
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
