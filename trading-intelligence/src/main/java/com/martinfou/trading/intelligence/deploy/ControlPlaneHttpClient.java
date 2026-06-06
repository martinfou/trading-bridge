package com.martinfou.trading.intelligence.deploy;

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

/** Minimal HTTP client for weekly deploy POST /api/runs (mirrors trading-tui {@code ControlPlaneClient}). */
public final class ControlPlaneHttpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;

    public ControlPlaneHttpClient(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    ControlPlaneHttpClient(String baseUrl, HttpClient http) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
    }

    public static ControlPlaneHttpClient fromEnvironment() {
        String url = System.getenv("CONTROL_PLANE_URL");
        if (url == null || url.isBlank()) {
            url = "http://localhost:" + System.getenv().getOrDefault("CONTROL_PLANE_PORT", "8080");
        }
        return new ControlPlaneHttpClient(url);
    }

    public JsonNode health() throws IOException, InterruptedException {
        return getJson("/api/health");
    }

    public JsonNode startPaperRun(
        String strategyId,
        String symbol,
        double lotSize,
        double capital
    ) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strategyId", strategyId);
        body.put("symbol", symbol);
        body.put("mode", "PAPER");
        body.put("executionLabel", "PAPER_OANDA");
        body.put("lotSize", lotSize);
        body.put("capital", capital);
        body.put("barsSource", Map.of("type", "sample", "count", 500));
        return postJson("/api/runs", body);
    }

    private JsonNode getJson(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(15))
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

    private static JsonNode parseResponse(HttpResponse<String> response) throws IOException {
        String body = response.body() == null ? "" : response.body();
        int code = response.statusCode();
        if (code >= 400) {
            throw new ControlPlaneException(code, errorMessage(body, code));
        }
        if (body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
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
            // plain-text error
        }
        return summarizeBody(body);
    }

    private static String summarizeBody(String body) {
        String oneLine = body.strip().replaceAll("\\s+", " ");
        return oneLine.length() > 200 ? oneLine.substring(0, 197) + "…" : oneLine;
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
