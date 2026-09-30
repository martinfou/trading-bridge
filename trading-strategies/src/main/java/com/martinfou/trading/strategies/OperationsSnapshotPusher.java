package com.martinfou.trading.strategies;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Pushes the aggregated monitor snapshot to the Hermes web hub over HTTPS.
 *
 * <p>The hub lives on DreamHost and can never reach this machine, so the trader
 * <em>pushes</em> its own operational health instead of being polled. The push is
 * deliberately boring:
 *
 * <ul>
 *   <li>JDK {@link HttpClient}, no new dependency;</li>
 *   <li>10 second timeout, one attempt per monitor cycle;</li>
 *   <li>no retry queue: the next cycle's snapshot supersedes a failed one;</li>
 *   <li>a failure logs a warning and returns false, never throws.</li>
 * </ul>
 *
 * <p>Configuration comes from {@code HERMES_WEB_URL} and {@code HERMES_WEB_TOKEN}.
 * When either is absent the pusher is disabled: {@link #fromEnv()} returns
 * {@code null} and the caller logs a single warning at startup and never pushes.
 */
public final class OperationsSnapshotPusher {

    private static final Logger log = LoggerFactory.getLogger(OperationsSnapshotPusher.class);

    static final String ENV_URL = "HERMES_WEB_URL";
    static final String ENV_TOKEN = "HERMES_WEB_TOKEN";
    static final String SNAPSHOT_PATH = "/api/trading/operations/snapshot";
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final String endpoint;
    private final String token;
    private final HttpClient client;
    private final Duration timeout;

    /**
     * @param endpoint full snapshot URL (scheme + host + path)
     * @param token    bearer token accepted by the hub
     * @param client   HTTP client (injected for tests)
     * @param timeout  per-request timeout
     */
    OperationsSnapshotPusher(String endpoint, String token, HttpClient client, Duration timeout) {
        this.endpoint = endpoint;
        this.token = token;
        this.client = client;
        this.timeout = timeout;
    }

    /**
     * Builds a pusher from the process environment, or {@code null} when the hub
     * URL or token is missing. A {@code null} return means "push disabled".
     */
    public static OperationsSnapshotPusher fromEnv() {
        return fromEnv(System.getenv());
    }

    static OperationsSnapshotPusher fromEnv(Map<String, String> env) {
        String baseUrl = env.get(ENV_URL);
        String token = env.get(ENV_TOKEN);
        if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()) {
            return null;
        }
        String endpoint = baseUrl.endsWith("/")
            ? baseUrl.substring(0, baseUrl.length() - 1)
            : baseUrl;
        endpoint += SNAPSHOT_PATH;
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(DEFAULT_TIMEOUT)
            .build();
        return new OperationsSnapshotPusher(endpoint, token, client, DEFAULT_TIMEOUT);
    }

    /**
     * Pushes one snapshot. Exactly one attempt, never throws: a transport error, a
     * timeout, or a non-2xx response all log a warning and return {@code false}.
     *
     * @return {@code true} when the hub acknowledged (2xx), {@code false} otherwise
     */
    public boolean push(String jsonBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return true;
            }
            log.warn("Operations snapshot push rejected by hub: HTTP {} ({})", status, response.body());
            return false;
        } catch (Exception e) {
            log.warn("Operations snapshot push failed: {}", e.getMessage());
            return false;
        }
    }
}
