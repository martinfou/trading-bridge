package com.martinfou.trading.intelligence.deploy;

import com.martinfou.trading.intelligence.compile.CompileManifest;
import com.martinfou.trading.intelligence.compile.CompileManifestIO;
import com.martinfou.trading.intelligence.paths.WeeklyBuilderPaths;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyDeployWatcherTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void run_noTradeWeek_movesToDeployedWithoutBroker(@TempDir Path repo) throws Exception {
        writeBundle(repo, manifest("2026-W23", List.of(
            new CompileManifest.StrategyEntry("NoTradeWeek", "NoTradeWeekStrategy", "T8", "EUR_USD"))));

        WeeklyDeployWatcher watcher = new WeeklyDeployWatcher(repo, unreachableClient());
        WeeklyDeployResult result = watcher.run();

        assertTrue(result.success());
        assertTrue(result.noTradeWeek());
        assertTrue(Files.isRegularFile(
            WeeklyBuilderPaths.deployed(repo).resolve("2026-W23").resolve("manifest.json")));
        assertFalse(Files.exists(WeeklyBuilderPaths.compiled(repo).resolve("2026-W23")));
    }

    @Test
    void run_deploysAllStrategiesOrMovesToFailed(@TempDir Path repo) throws Exception {
        AtomicInteger runPosts = new AtomicInteger();
        String baseUrl = startControlPlane(runPosts);

        writeBundle(repo, manifest("2026-W24", List.of(
            new CompileManifest.StrategyEntry(
                "LondonOpenRangeBreakout", "LondonOpenRangeBreakoutStrategy", "T1", "EUR_USD"))));

        WeeklyDeployWatcher watcher = new WeeklyDeployWatcher(repo, new ControlPlaneHttpClient(baseUrl));
        WeeklyDeployResult result = watcher.run();

        assertTrue(result.success());
        assertFalse(result.noTradeWeek());
        assertEquals(1, result.runIds().size());
        assertEquals(1, runPosts.get());
        assertTrue(Files.isRegularFile(
            WeeklyBuilderPaths.deployed(repo).resolve("2026-W24").resolve("manifest.json")));
    }

    @Test
    void run_controlPlaneFailure_movesToFailed(@TempDir Path repo) throws Exception {
        writeBundle(repo, manifest("2026-W25", List.of(
            new CompileManifest.StrategyEntry(
                "LondonOpenRangeBreakout", "LondonOpenRangeBreakoutStrategy", "T1", "EUR_USD"))));

        WeeklyDeployWatcher watcher = new WeeklyDeployWatcher(repo, unreachableClient());
        WeeklyDeployResult result = watcher.run();

        assertFalse(result.success());
        assertTrue(Files.isRegularFile(
            WeeklyBuilderPaths.failed(repo).resolve("2026-W25").resolve(WeeklyBuilderPaths.REASON_FILE)));
    }

    @Test
    void isNoTradeWeek_detectsEmptyOrT8() {
        var empty = manifest("W1", List.of());
        assertTrue(WeeklyDeployWatcher.isNoTradeWeek(empty));

        var t8 = manifest("W2", List.of(
            new CompileManifest.StrategyEntry("NoTradeWeek", "NoTradeWeekStrategy", "T8", "EUR_USD")));
        assertTrue(WeeklyDeployWatcher.isNoTradeWeek(t8));

        var mixed = manifest("W3", List.of(
            new CompileManifest.StrategyEntry("LondonOpenRangeBreakout", "LondonOpenRangeBreakoutStrategy", "T1", "EUR_USD")));
        assertFalse(WeeklyDeployWatcher.isNoTradeWeek(mixed));
    }

    private static CompileManifest manifest(String weekId, List<CompileManifest.StrategyEntry> strategies) {
        Instant validFrom = Instant.parse("2026-06-09T00:00:00Z");
        Instant validUntil = Instant.parse("2026-06-13T21:00:00Z");
        return new CompileManifest(
            weekId,
            "corr-" + weekId,
            Instant.parse("2026-06-06T17:00:00Z"),
            validFrom,
            validUntil,
            strategies,
            "weekly-plan-" + weekId + ".json",
            CompileManifest.ORIGIN_AI,
            new CompileManifest.RiskSnapshot(0.01, 100_000.0));
    }

    private static void writeBundle(Path repo, CompileManifest manifest) throws IOException {
        WeeklyBuilderPaths.ensureLayout(repo);
        Path bundleDir = WeeklyBuilderPaths.compiled(repo).resolve(manifest.weekId());
        Files.createDirectories(bundleDir);
        CompileManifestIO.mapper().writerWithDefaultPrettyPrinter()
            .writeValue(bundleDir.resolve("manifest.json").toFile(), manifest);
        Files.writeString(bundleDir.resolve("weekly-plan-" + manifest.weekId() + ".json"), "{}");
    }

    private String startControlPlane(AtomicInteger runPosts) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/health", exchange -> writeJson(exchange, 200, "{\"status\":\"ok\"}"));
        server.createContext("/api/runs", exchange -> {
            runPosts.incrementAndGet();
            writeJson(exchange, 202, "{\"runId\":\"run-test-" + runPosts.get() + "\",\"status\":\"RUNNING\"}");
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ControlPlaneHttpClient unreachableClient() {
        return new ControlPlaneHttpClient("http://127.0.0.1:1");
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int code, String json)
        throws IOException {
        byte[] body = json.getBytes();
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
