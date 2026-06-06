package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.intelligence.compile.CompileManifest;
import com.martinfou.trading.intelligence.compile.CompileManifestIO;
import com.martinfou.trading.intelligence.deploy.ControlPlaneHttpClient;
import com.martinfou.trading.intelligence.paths.WeeklyBuilderPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyBuilderServiceTest {

    private WeeklyBuilderService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void status_ignoresGitkeepPlaceholders(@TempDir Path repo) throws Exception {
        WeeklyBuilderPaths.ensureLayout(repo);
        Files.writeString(WeeklyBuilderPaths.pending(repo).resolve(".gitkeep"), "");
        Files.writeString(WeeklyBuilderPaths.compiling(repo).resolve(".gitkeep"), "");
        Files.writeString(WeeklyBuilderPaths.compiled(repo).resolve(".gitkeep"), "");
        Files.writeString(WeeklyBuilderPaths.failed(repo).resolve(".gitkeep"), "");

        service = new WeeklyBuilderService(new InMemoryEventStore(), repo);
        Map<String, Object> status = service.status();

        assertEquals(0, status.get("pendingCount"), status.toString());
        assertEquals(0, status.get("compilingCount"), status.toString());
        assertEquals(0, status.get("compiledCount"), status.toString());
        assertEquals(0, status.get("failedBundleCount"), status.toString());
    }

    @Test
    void status_readsRootLevelPlanFailureReason(@TempDir Path repo) throws Exception {
        WeeklyBuilderPaths.ensureLayout(repo);
        Files.writeString(
            WeeklyBuilderPaths.failed(repo).resolve("reason-test.json"),
            "{\"detail\":\"Missing DEEPSEEK_API_KEY\",\"reason\":\"llm-error\"}");

        service = new WeeklyBuilderService(new InMemoryEventStore(), repo);
        Map<String, Object> status = service.status();

        assertEquals("Missing DEEPSEEK_API_KEY", status.get("lastFailureReason"));
        assertEquals(1, status.get("failedBundleCount"));
    }

    @Test
    void status_reportsFolderCounts(@TempDir Path repo) throws Exception {
        Files.createDirectories(WeeklyBuilderPaths.pending(repo));
        Files.writeString(WeeklyBuilderPaths.pending(repo).resolve("weekly-plan-2026-W23.json"), "{}");
        Files.createDirectories(WeeklyBuilderPaths.compiled(repo));

        service = new WeeklyBuilderService(new InMemoryEventStore(), repo);
        Map<String, Object> status = service.status();

        assertEquals(1, status.get("pendingCount"));
        assertEquals(0, status.get("compiledCount"));
        assertFalse((Boolean) status.get("planProcessing"));
    }

    @Test
    void status_readsCompiledManifestMetadata(@TempDir Path repo) throws Exception {
        WeeklyBuilderPaths.ensureLayout(repo);
        Path bundleDir = WeeklyBuilderPaths.compiled(repo).resolve("2026-W23");
        Files.createDirectories(bundleDir);
        CompileManifest manifest = new CompileManifest(
            "2026-W23",
            "corr-1",
            Instant.parse("2026-06-06T17:00:00Z"),
            Instant.parse("2026-06-09T00:00:00Z"),
            Instant.parse("2026-06-13T21:00:00Z"),
            List.of(new CompileManifest.StrategyEntry("NoTradeWeek", "NoTradeWeekStrategy", "T8", "EUR_USD")),
            "weekly-plan-2026-W23.json",
            CompileManifest.ORIGIN_AI,
            new CompileManifest.RiskSnapshot(0.01, 100_000.0));
        CompileManifestIO.mapper().writerWithDefaultPrettyPrinter()
            .writeValue(bundleDir.resolve("manifest.json").toFile(), manifest);

        service = new WeeklyBuilderService(new InMemoryEventStore(), repo);
        Map<String, Object> status = service.status();

        assertEquals("2026-W23", status.get("lastWeekId"));
        assertEquals(List.of("T8"), status.get("templates"));
        assertEquals("2026-06-09T00:00:00Z", status.get("validFrom"));
        assertEquals("2026-06-13T21:00:00Z", status.get("validUntil"));
    }

    @Test
    void triggerDeployAsync_emitsWeeklyBuilderEvent(@TempDir Path repo) throws Exception {
        WeeklyBuilderPaths.ensureLayout(repo);
        Path bundleDir = WeeklyBuilderPaths.compiled(repo).resolve("2026-W23");
        Files.createDirectories(bundleDir);
        CompileManifest manifest = new CompileManifest(
            "2026-W23",
            "corr-deploy",
            Instant.parse("2026-06-06T17:00:00Z"),
            Instant.parse("2026-06-09T00:00:00Z"),
            Instant.parse("2026-06-13T21:00:00Z"),
            List.of(new CompileManifest.StrategyEntry("NoTradeWeek", "NoTradeWeekStrategy", "T8", "EUR_USD")),
            "weekly-plan-2026-W23.json",
            CompileManifest.ORIGIN_AI,
            new CompileManifest.RiskSnapshot(0.01, 100_000.0));
        CompileManifestIO.mapper().writerWithDefaultPrettyPrinter()
            .writeValue(bundleDir.resolve("manifest.json").toFile(), manifest);

        InMemoryEventStore store = new InMemoryEventStore();
        service = new WeeklyBuilderService(
            store,
            repo,
            Clock.fixed(Instant.parse("2026-06-06T18:00:00Z"), ZoneOffset.UTC),
            Executors.newSingleThreadExecutor(),
            "http://127.0.0.1:1",
            new ControlPlaneHttpClient("http://127.0.0.1:1"));

        assertTrue(service.triggerDeployAsync().accepted());
        awaitDeployComplete(service);

        List<RunEvent> events = store.replayAll("weekly-builder");
        assertFalse(events.isEmpty());
        assertEquals(RunEventType.WEEKLY_BUILDER_EVENT, events.getFirst().type());
        assertEquals("deploy", events.getFirst().payload().get("step"));
        assertTrue(service.status().containsKey("lastDeployRun"));
    }

    @Test
    void triggerPlanAsync_rejectsWhenAlreadyRunning(@TempDir Path repo) throws Exception {
        java.util.concurrent.atomic.AtomicReference<Runnable> heldTask = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ExecutorService holdingExecutor = new java.util.concurrent.AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                heldTask.set(command);
            }

            @Override
            public void shutdown() {}

            @Override
            public java.util.List<Runnable> shutdownNow() {
                return java.util.List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
                return true;
            }
        };

        service = new WeeklyBuilderService(
            new InMemoryEventStore(),
            repo,
            Clock.systemUTC(),
            holdingExecutor,
            "http://127.0.0.1:1",
            new ControlPlaneHttpClient("http://127.0.0.1:1"));

        assertTrue(service.triggerPlanAsync().accepted());
        assertFalse(service.triggerPlanAsync().accepted());
        Runnable pending = heldTask.get();
        if (pending != null) {
            pending.run();
        }
        holdingExecutor.shutdown();
    }

    private static void awaitDeployComplete(WeeklyBuilderService service) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (!(Boolean) service.status().get("deployProcessing")) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }
}
