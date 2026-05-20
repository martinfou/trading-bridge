package com.martinfou.trading.monitor;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP health-check server.
 * <p>
 * Exposes {@code GET /health} as JSON on a configurable port (default 9090).
 * Uses Java's built-in {@code com.sun.net.httpserver.HttpServer} — zero external dependencies.
 * <p>
 * Intended to run on EVERY machine in the fleet (backtest, paper, live, dashboard)
 * so the Central Health Monitor can poll all machines every 5 minutes.
 *
 * <pre>{@code
 * // Start with defaults
 * HealthServer server = new HealthServer();
 * server.start();
 *
 * // Or with custom config
 * HealthServerConfig config = new HealthServerConfig("paper-vps", 9091);
 * HealthServer server = new HealthServer(config, new SystemMetrics());
 * server.start();
 * }</pre>
 */
public class HealthServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HealthServer.class);

    private final HttpServer server;
    private final HealthServerConfig config;
    private final SystemMetrics metrics;
    private final Instant startedAt;
    private volatile String activeStrategies = "[]";
    private volatile String lastTradeTimestamp = "";
    private volatile int errors24h = 0;
    private volatile String oandaApiStatus = "unknown";
    private volatile String deploymentId = "";

    // Override injected in tests
    private final HealthResponseFormatter formatter;

    /**
     * Creates a HealthServer with default config (port 9090, auto-detected hostname).
     */
    public HealthServer() throws IOException {
        this(new HealthServerConfig(), new SystemMetrics());
    }

    /**
     * Creates a HealthServer with custom config and metrics collector.
     */
    public HealthServer(HealthServerConfig config, SystemMetrics metrics) throws IOException {
        this.config = config;
        this.metrics = metrics;
        this.startedAt = Instant.now();
        this.formatter = new HealthResponseFormatter();
        this.server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        this.server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "health-server");
            t.setDaemon(true);
            return t;
        }));
        this.server.createContext("/health", this::handleHealth);
        this.server.createContext("/healthz", this::handleHealthz);
    }

    // ---- Public API ---- //

    /**
     * Starts the server in a background daemon thread.
     */
    public void start() {
        server.start();
        log.info("HealthServer started on port {} (machine: {})", config.port(), config.machineName());
    }

    /**
     * Gracefully stops the server (waits up to 2 seconds).
     */
    @Override
    public void close() {
        log.info("HealthServer shutting down...");
        server.stop(2);
    }

    /**
     * Returns the actual port the server is listening on.
     * When bound to port 0, the OS assigns an ephemeral port.
     */
    public int getPort() {
        return server.getAddress().getPort();
    }

    // ---- External state setters (called by the trading engine) ---- //

    public void setActiveStrategies(String jsonArray) { this.activeStrategies = jsonArray; }
    public void setLastTradeTimestamp(String iso) { this.lastTradeTimestamp = iso; }
    public void setErrors24h(int count) { this.errors24h = count; }
    public void setOandaApiStatus(String status) { this.oandaApiStatus = status; }
    public void setDeploymentId(String id) { this.deploymentId = id; }

    // ---- HTTP Handlers ---- //

    private void handleHealth(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String json = buildHealthResponse();
            respond(exchange, 200, json);
        } catch (Exception e) {
            log.error("Health endpoint error", e);
            respond(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
    }

    /**
     * Simple liveness probe — returns HTTP 200 with minimal content.
     */
    private void handleHealthz(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "OK");
    }

    // ---- Response Building ---- //

    String buildHealthResponse() {
        String uptime = formatUptime(Duration.between(startedAt, Instant.now()));
        String gitCommit = metrics.getGitCommit();

        return formatter.format(
                config.machineName(),
                uptime,
                gitCommit,
                config.version(),
                activeStrategies,
                lastTradeTimestamp,
                errors24h,
                oandaApiStatus,
                metrics.getCpuPercent(),
                metrics.getMemoryPercent(),
                metrics.getDiskPercent(),
                deploymentId
        );
    }

    // ---- Helpers ---- //

    private void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String formatUptime(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutes() % 60;
        long seconds = d.getSeconds() % 60;
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }
}
