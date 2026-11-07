package com.martinfou.trading.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages client-server connection settings and authenticated HTTP calls to a remote
 * Trading Bridge Core instance using a base URL and API Key ({@code Authorization: Bearer}
 * and {@code X-API-Key}).
 */
public final class RemoteCoreClient {

    private static final Logger log = LoggerFactory.getLogger(RemoteCoreClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final long CACHE_TTL_MS = 6_000L;

    private static final Object LOCK = new Object();
    private static volatile long cachedAtMs = 0L;
    private static volatile String cachedKey = "";
    private static volatile RemoteTelemetrySnapshot cachedSnapshot = new RemoteTelemetrySnapshot(List.of(), List.of());

    private final Path configFile;
    private final HttpClient httpClient;

    public record RemoteCoreSettings(
        boolean enabled,
        String url,
        String apiKey,
        String serverApiKey
    ) {
        public boolean isConfigured() {
            return enabled && url != null && !url.isBlank();
        }
    }

    public record RemoteTelemetrySnapshot(
        List<Map<String, Object>> ibkrTrades,
        List<Map<String, Object>> laneRuns
    ) {
    }

    public RemoteCoreClient() {
        this(resolveDefaultConfigFile());
    }

    public RemoteCoreClient(Path configFile) {
        this.configFile = configFile;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();
    }

    private static Path resolveDefaultConfigFile() {
        String dataDir = System.getenv("TRADING_BRIDGE_DATA_DIR");
        if (dataDir != null && !dataDir.isBlank()) {
            return Paths.get(dataDir).resolve("remote-core.json");
        }
        return Paths.get("data/runtime/remote-core.json");
    }

    public RemoteCoreSettings loadSettings() {
        String envUrl = firstNonBlank(
            System.getenv("REMOTE_CORE_URL"),
            System.getProperty("trading.remoteCore.url", "")
        );
        String envApiKey = firstNonBlank(
            System.getenv("REMOTE_CORE_API_KEY"),
            System.getProperty("trading.remoteCore.apiKey", "")
        );
        String envServerKey = firstNonBlank(
            System.getenv("TRADING_BRIDGE_API_KEY"),
            System.getenv("CONTROL_PLANE_API_KEY"),
            System.getProperty("trading.control.apiKey", "")
        );

        if (configFile != null && Files.isRegularFile(configFile)) {
            try {
                JsonNode node = MAPPER.readTree(Files.readString(configFile, StandardCharsets.UTF_8));
                String fileUrl = normalizeUrl(node.path("url").asText(""));
                boolean enabled = node.path("enabled").asBoolean(!fileUrl.isBlank());
                String fileApiKey = node.path("apiKey").asText("").trim();
                String fileServerKey = node.path("serverApiKey").asText("").trim();
                return new RemoteCoreSettings(
                    enabled,
                    !fileUrl.isBlank() ? fileUrl : normalizeUrl(envUrl),
                    !fileApiKey.isBlank() ? fileApiKey : envApiKey,
                    !fileServerKey.isBlank() ? fileServerKey : envServerKey
                );
            } catch (Exception e) {
                log.debug("Failed to read remote-core.json {}: {}", configFile, e.getMessage());
            }
        }

        String normEnvUrl = normalizeUrl(envUrl);
        return new RemoteCoreSettings(
            !normEnvUrl.isBlank(),
            normEnvUrl,
            envApiKey,
            envServerKey
        );
    }

    public RemoteCoreSettings saveSettings(
        Boolean enabled,
        String url,
        String apiKey,
        boolean clearApiKey,
        String serverApiKey,
        boolean clearServerApiKey
    ) throws IOException {
        RemoteCoreSettings current = loadSettings();
        String normUrl = url != null ? normalizeUrl(url) : current.url();
        boolean nextEnabled = enabled != null ? enabled : !normUrl.isBlank();

        String nextApiKey;
        if (clearApiKey) {
            nextApiKey = "";
        } else if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("****")) {
            nextApiKey = apiKey.trim();
        } else {
            nextApiKey = current.apiKey() != null ? current.apiKey() : "";
        }

        String nextServerKey;
        if (clearServerApiKey) {
            nextServerKey = "";
        } else if (serverApiKey != null && !serverApiKey.isBlank() && !serverApiKey.startsWith("****")) {
            nextServerKey = serverApiKey.trim();
        } else {
            nextServerKey = current.serverApiKey() != null ? current.serverApiKey() : "";
        }

        RemoteCoreSettings updated = new RemoteCoreSettings(nextEnabled, normUrl, nextApiKey, nextServerKey);
        if (configFile != null) {
            if (configFile.getParent() != null) {
                Files.createDirectories(configFile.getParent());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("enabled", updated.enabled());
            payload.put("url", updated.url());
            payload.put("apiKey", updated.apiKey());
            payload.put("serverApiKey", updated.serverApiKey());
            Files.writeString(configFile, MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8);
        }
        invalidateCache();
        return updated;
    }

    public Map<String, Object> toMaskedStatusMap() {
        RemoteCoreSettings s = loadSettings();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", s.enabled());
        map.put("url", s.url() != null ? s.url() : "");
        boolean hasKey = s.apiKey() != null && !s.apiKey().isBlank();
        map.put("apiKeyConfigured", hasKey);
        map.put("apiKeyMasked", maskSecret(s.apiKey()));
        boolean hasServerKey = s.serverApiKey() != null && !s.serverApiKey().isBlank();
        map.put("serverApiKeyConfigured", hasServerKey);
        map.put("serverApiKeyMasked", maskSecret(s.serverApiKey()));
        return map;
    }

    public boolean verifyIncomingRequestApiKey(String authHeader, String xApiKeyHeader) {
        RemoteCoreSettings s = loadSettings();
        String expected = s.serverApiKey();
        if (expected == null || expected.isBlank()) {
            return true; // No server API key required
        }
        String bearer = "";
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            bearer = authHeader.substring(7).trim();
        }
        String custom = xApiKeyHeader != null ? xApiKeyHeader.trim() : "";
        return expected.equals(bearer) || expected.equals(custom);
    }

    public static void invalidateCache() {
        synchronized (LOCK) {
            cachedAtMs = 0L;
            cachedKey = "";
            cachedSnapshot = new RemoteTelemetrySnapshot(List.of(), List.of());
        }
    }

    public RemoteTelemetrySnapshot fetchRemoteTelemetry(String venueId, String maskedAccountId, boolean forceRefresh) {
        RemoteCoreSettings settings = loadSettings();
        if (!settings.isConfigured()) {
            return new RemoteTelemetrySnapshot(List.of(), List.of());
        }
        String key = settings.url() + "|" + (venueId != null ? venueId : "") + "|" + (maskedAccountId != null ? maskedAccountId : "");
        if (!forceRefresh) {
            synchronized (LOCK) {
                long age = System.currentTimeMillis() - cachedAtMs;
                if (key.equals(cachedKey) && age >= 0 && age < CACHE_TTL_MS) {
                    return cachedSnapshot;
                }
            }
        }

        RemoteTelemetrySnapshot fetched = queryRemoteCore(settings.url(), settings.apiKey(), venueId, maskedAccountId);
        synchronized (LOCK) {
            cachedKey = key;
            cachedAtMs = System.currentTimeMillis();
            cachedSnapshot = fetched;
        }
        return fetched;
    }

    public Map<String, Object> testConnection(String overrideUrl, String overrideApiKey) {
        RemoteCoreSettings saved = loadSettings();
        String targetUrl = normalizeUrl(overrideUrl != null && !overrideUrl.isBlank() ? overrideUrl : saved.url());
        String targetKey = (overrideApiKey != null && !overrideApiKey.isBlank() && !overrideApiKey.startsWith("****"))
            ? overrideApiKey.trim()
            : saved.apiKey();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", targetUrl);
        if (targetUrl.isBlank()) {
            out.put("success", false);
            out.put("message", "Remote Core URL is required.");
            return out;
        }

        try {
            String fullUrl = targetUrl + "/api/telemetry/lanes";
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET();
            applyAuthHeaders(reqBuilder, targetKey);

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                out.put("success", false);
                out.put("statusCode", resp.statusCode());
                out.put("message", "Authentication rejected by Remote Core (HTTP " + resp.statusCode() + "). Check your API Key.");
                return out;
            }

            RemoteTelemetrySnapshot snapshot = queryRemoteCore(targetUrl, targetKey, "ibkr-paper", "");
            if (resp.statusCode() >= 200 && resp.statusCode() < 300 || !snapshot.ibkrTrades().isEmpty() || !snapshot.laneRuns().isEmpty()) {
                out.put("success", true);
                out.put("ibkrTradesCount", snapshot.ibkrTrades().size());
                out.put("laneRunsCount", snapshot.laneRuns().size());
                out.put("message", String.format(
                    "Connected to Remote Core (%d IBKR trades, %d strategy runs)",
                    snapshot.ibkrTrades().size(),
                    snapshot.laneRuns().size()
                ));
                return out;
            }

            // Try fallback health check on /api/control/summary
            HttpRequest.Builder fallbackReq = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl + "/api/control/summary"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET();
            applyAuthHeaders(fallbackReq, targetKey);
            HttpResponse<String> fallbackResp = httpClient.send(fallbackReq.build(), HttpResponse.BodyHandlers.ofString());
            if (fallbackResp.statusCode() >= 200 && fallbackResp.statusCode() < 300) {
                out.put("success", true);
                out.put("ibkrTradesCount", snapshot.ibkrTrades().size());
                out.put("laneRunsCount", snapshot.laneRuns().size());
                out.put("message", "Connected to Remote Core (/api/control/summary OK, " + snapshot.ibkrTrades().size() + " IBKR trades)");
                return out;
            }

            out.put("success", false);
            out.put("statusCode", resp.statusCode());
            out.put("message", "Remote Core returned HTTP " + resp.statusCode());
            return out;
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "Connection failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    private RemoteTelemetrySnapshot queryRemoteCore(String baseUrl, String apiKey, String venueId, String maskedAccountId) {
        String normUrl = normalizeUrl(baseUrl);
        if (normUrl.isBlank()) {
            return new RemoteTelemetrySnapshot(List.of(), List.of());
        }
        String vId = venueId != null && !venueId.isBlank() ? venueId : "ibkr-paper";
        String mAcct = maskedAccountId != null ? maskedAccountId : "";

        try {
            String query = "?venueId=" + URLEncoder.encode(vId, StandardCharsets.UTF_8)
                + "&maskedAccountId=" + URLEncoder.encode(mAcct, StandardCharsets.UTF_8);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(normUrl + "/api/telemetry/lanes" + query))
                .timeout(Duration.ofSeconds(4))
                .header("Accept", "application/json")
                .GET();
            applyAuthHeaders(reqBuilder, apiKey);

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300 && resp.body() != null) {
                Map<String, Object> parsed = MAPPER.readValue(resp.body(), Map.class);
                List<Map<String, Object>> trades = extractMapList(parsed.get("ibkrTrades"));
                List<Map<String, Object>> runs = extractMapList(parsed.get("laneRuns"));
                for (Map<String, Object> t : trades) {
                    if (vId != null && !vId.isBlank()) {
                        t.put("venueId", vId);
                    }
                    if (!mAcct.isBlank() && (t.get("maskedAccountId") == null || t.get("maskedAccountId").toString().isBlank())) {
                        t.put("maskedAccountId", mAcct);
                    }
                }
                return new RemoteTelemetrySnapshot(trades, runs);
            }
        } catch (Exception e) {
            log.debug("Remote Core /api/telemetry/lanes query failed on {}: {}", normUrl, e.getMessage());
        }

        // Fallback: query /api/broker-accounts/balances and /api/control/summary if remote server is on older endpoint set
        List<Map<String, Object>> fallbackTrades = new ArrayList<>();
        List<Map<String, Object>> fallbackRuns = new ArrayList<>();
        try {
            HttpRequest.Builder balReq = HttpRequest.newBuilder()
                .uri(URI.create(normUrl + "/api/broker-accounts/balances"))
                .timeout(Duration.ofSeconds(4))
                .header("Accept", "application/json")
                .GET();
            applyAuthHeaders(balReq, apiKey);
            HttpResponse<String> balResp = httpClient.send(balReq.build(), HttpResponse.BodyHandlers.ofString());
            if (balResp.statusCode() >= 200 && balResp.statusCode() < 300 && balResp.body() != null) {
                Map<String, Object> parsed = MAPPER.readValue(balResp.body(), Map.class);
                for (Map<String, Object> b : extractMapList(parsed.get("balances"))) {
                    if ("IBKR".equalsIgnoreCase(String.valueOf(b.get("provider")))) {
                        fallbackTrades.addAll(extractMapList(b.get("trades")));
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore fallback balances failure
        }

        try {
            HttpRequest.Builder sumReq = HttpRequest.newBuilder()
                .uri(URI.create(normUrl + "/api/control/summary"))
                .timeout(Duration.ofSeconds(4))
                .header("Accept", "application/json")
                .GET();
            applyAuthHeaders(sumReq, apiKey);
            HttpResponse<String> sumResp = httpClient.send(sumReq.build(), HttpResponse.BodyHandlers.ofString());
            if (sumResp.statusCode() >= 200 && sumResp.statusCode() < 300 && sumResp.body() != null) {
                Map<String, Object> parsed = MAPPER.readValue(sumResp.body(), Map.class);
                fallbackRuns.addAll(extractMapList(parsed.get("runs")));
            }
        } catch (Exception ignored) {
            // ignore fallback summary failure
        }

        return new RemoteTelemetrySnapshot(fallbackTrades, fallbackRuns);
    }

    private static void applyAuthHeaders(HttpRequest.Builder builder, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            String trimmed = apiKey.trim();
            builder.header("Authorization", "Bearer " + trimmed);
            builder.header("X-API-Key", trimmed);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractMapList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    out.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        }
        return out;
    }

    public static String normalizeUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim().replaceAll("/+$", "");
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!trimmed.regionMatches(true, 0, "http://", 0, 7)
            && !trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            trimmed = "http://" + trimmed;
        }
        return trimmed.replaceAll("/+$", "");
    }

    private static String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        String s = secret.trim();
        if (s.length() <= 4) {
            return "****";
        }
        return "****" + s.substring(s.length() - 4);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }
}
