package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlSummaryServiceTest {

    @Test
    void buildSummary_stubDeployment_emitsInsufficientDriftSignal() {
        try (EventStore store = EventStores.inMemory();
             InMemoryDeploymentStore deploymentStore = new InMemoryDeploymentStore();
             RunManager manager = new RunManager(store, deploymentStore)) {
            deploymentStore.save(new DeploymentRecord(
                "LondonOpenRangeBreakout",
                com.martinfou.trading.backtest.RunMode.PAPER,
                Instant.parse("2024-01-01T00:00:00Z"),
                "run-bt",
                List.of(),
                ExecutionLabel.PAPER_STUB));

            ControlSummaryService service = new ControlSummaryService(manager, deploymentStore);
            Map<String, Object> summary = service.buildSummary();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> drift = (List<Map<String, Object>>)
                ((Map<?, ?>) summary.get("signals")).get("drift");
            assertEquals(1, drift.size());
            assertEquals("INSUFFICIENT", drift.getFirst().get("dataSource"));
            assertEquals("HOLD", drift.getFirst().get("recommendation"));
        }
    }

    @Test
    void buildSummary_emptyRuns() {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            ControlSummaryService service = new ControlSummaryService(manager);

            Map<String, Object> summary = service.buildSummary();

            assertEquals(1, summary.get("schemaVersion"));
            assertTrue(((List<?>) summary.get("runs")).isEmpty());
            assertTrue(((Map<?, ?>) summary.get("signals")).containsKey("drift"));
        }
    }

    @Test
    void buildSummary_includesExecutionLabelAndFreshness() throws Exception {
        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            String runId = manager.startRun(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                new BarSourceResolver.BarsSource("sample", 300, null),
                100_000.0,
                null,
                null,
                null));
            waitForCompletion(manager, runId);

            ControlSummaryService service = new ControlSummaryService(manager);
            Map<String, Object> summary = service.buildSummary();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> runs = (List<Map<String, Object>>) summary.get("runs");
            assertEquals(1, runs.size());
            assertEquals("BACKTEST", runs.getFirst().get("executionLabel"));
            assertTrue(runs.getFirst().containsKey("executionLabelMeta"));
            assertEquals("Backtest",
                ((Map<?, ?>) runs.getFirst().get("executionLabelMeta")).get("displayName"));
            assertTrue(summary.containsKey("executionLabelCatalog"));
            assertFalse((Boolean) runs.getFirst().get("isStale"));
            assertTrue(summary.get("freshness") instanceof Map<?, ?>);
        }
    }

    @Test
    void buildSummary_marksRunningBrokerRunStaleWhenSilent() {
        Instant now = Instant.parse("2024-06-01T01:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            RunConfigSnapshot config = new RunConfigSnapshot(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "LIVE",
                "sample",
                100,
                null,
                100_000.0,
                null,
                null,
                ExecutionLabel.LIVE_OANDA.name());
            RunRecord run = manager.register(config);
            run.markRunning();
            run.noteEventAt(Instant.parse("2024-06-01T00:57:00Z"));

            ControlSummaryService service = new ControlSummaryService(manager, 120, clock);
            Map<String, Object> summary = service.buildSummary();

            @SuppressWarnings("unchecked")
            Map<String, Object> runItem = ((List<Map<String, Object>>) summary.get("runs")).getFirst();
            assertEquals("LIVE_OANDA", runItem.get("executionLabel"));
            assertTrue((Boolean) runItem.get("isStale"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> staleSignals = (List<Map<String, Object>>)
                ((Map<?, ?>) summary.get("signals")).get("stale");
            assertEquals(1, staleSignals.size());
            assertEquals(run.runId(), staleSignals.getFirst().get("runId"));

            @SuppressWarnings("unchecked")
            Map<String, Object> freshness = (Map<String, Object>) summary.get("freshness");
            assertEquals(120L, freshness.get("staleThresholdSeconds"));
            assertEquals(1, freshness.get("staleRunCount"));
        }
    }

    private static void waitForCompletion(RunManager manager, String runId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            RunRecord record = manager.getRun(runId).orElseThrow();
            if (record.status() != RunRecord.Status.RUNNING) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("timeout");
    }
}
