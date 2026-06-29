package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;

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
             RunManager manager = new RunManager(store, config -> new com.martinfou.trading.broker.FakeBroker(100_000.0))) {
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

    @Test
    void getPositions_brokerBackedRun_returnsEmptyWhenQuerySucceedsWithZeroPositions() {
        var mockBroker = new com.martinfou.trading.broker.Broker() {
            @Override public boolean isConnected() { return true; }
            @Override public void connect() {}
            @Override public void disconnect() {}
            @Override public void reconnect() {}
            @Override public com.martinfou.trading.broker.OrderSubmitResult submitOrder(Order order) { return null; }
            @Override public com.martinfou.trading.broker.OrderSubmitResult cancelOrder(String id) { return null; }
            @Override public List<Position> getPositions() { return List.of(); }
            @Override public com.martinfou.trading.broker.AccountState getAccountState() { return new com.martinfou.trading.broker.AccountState(100000, 100000, "USD"); }
            @Override public void addEventListener(java.util.function.Consumer<com.martinfou.trading.broker.BrokerEvent> l) {}
        };
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {

            manager.brokerAccountRegistry().registerMockBroker("default", mockBroker);

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

            store.append(run.runId(), new RunEvent(
                RunEvent.SCHEMA_VERSION,
                RunEventType.FILL,
                Instant.now(),
                run.runId(),
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "LIVE",
                Map.of("symbol", "EUR_USD", "side", "BUY", "quantity", 1000.0, "price", 1.10)
            ));

            ControlSummaryService service = new ControlSummaryService(manager);
            Map<String, Object> summary = service.buildSummary();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> runs = (List<Map<String, Object>>) summary.get("runs");
            assertEquals(1, runs.size());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> positions = (List<Map<String, Object>>) runs.getFirst().get("positions");
            assertTrue(positions.isEmpty());
        }
    }

    @Test
    void buildSummary_restoredRun_ignoresOldEventsBeforeStartedAt() throws Exception {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store, config -> new com.martinfou.trading.broker.FakeBroker(100_000.0))) {
            
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
            
            String runId = "restored-run-123";
            RunRecord run = manager.restoreRun(runId, config);
            run.markRunning();
            
            // Append an event from 1 hour before the run started (previous session)
            Instant oldEventTime = run.startedAt().minus(java.time.Duration.ofHours(1));
            store.append(run.runId(), new RunEvent(
                RunEvent.SCHEMA_VERSION,
                RunEventType.FILL,
                oldEventTime,
                run.runId(),
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "LIVE",
                Map.of("symbol", "EUR_USD", "side", "BUY", "quantity", 1000.0, "price", 1.10)
            ));

            // Set clock to 30 seconds after the run started
            Instant testNow = run.startedAt().plus(java.time.Duration.ofSeconds(30));
            Clock clock = Clock.fixed(testNow, ZoneOffset.UTC);

            ControlSummaryService service = new ControlSummaryService(manager, 120, clock);
            Map<String, Object> summary = service.buildSummary();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> runs = (List<Map<String, Object>>) summary.get("runs");
            assertEquals(1, runs.size());
            Map<String, Object> runItem = runs.getFirst();
            
            // The run should NOT be stale because the old event is ignored and it uses the startedAt grace period
            assertFalse((Boolean) runItem.get("isStale"));
        }
    }

    @Test
    void testLastTradeAtInSummary() throws Exception {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            RunConfigSnapshot config = new RunConfigSnapshot(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "PAPER",
                "sample",
                100,
                null,
                100_000.0,
                null,
                null,
                ExecutionLabel.PAPER_STUB.name());

            String runId = "test-run-lastTradeAt";
            RunRecord run = manager.restoreRun(runId, config);
            run.markRunning();

            Instant fillTime = Instant.now().minusSeconds(10);
            store.append(runId, new RunEvent(
                RunEvent.SCHEMA_VERSION,
                RunEventType.FILL,
                fillTime,
                runId,
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "PAPER",
                Map.of("symbol", "EUR_USD", "side", "BUY", "quantity", 1000.0, "price", 1.10)
            ));

            ControlSummaryService service = new ControlSummaryService(manager);
            Map<String, Object> summary = service.buildSummary();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> runs = (List<Map<String, Object>>) summary.get("runs");
            assertEquals(1, runs.size());
            Map<String, Object> runItem = runs.getFirst();
            assertEquals(fillTime.toString(), runItem.get("lastTradeAt"));
        }
    }

    @Test
    void testCalculatePnLMetricsAccumulatesRealizedPnLAcrossSiblingRuns() throws Exception {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
             
            RunConfigSnapshot config1 = new RunConfigSnapshot(
                "StratA", "EUR_USD", "PAPER", "sample", 100, null, 100_000.0, null, null, "PAPER_STUB"
            );
            RunRecord run1 = manager.restoreRun("run-1", config1);
            run1.markRunning();
            manager.runRecordStore().save(run1);

            RunConfigSnapshot config2 = new RunConfigSnapshot(
                "StratA", "EUR_USD", "PAPER", "sample", 100, null, 100_000.0, null, null, "PAPER_STUB"
            );
            RunRecord run2 = manager.restoreRun("run-2", config2);
            run2.markRunning();
            manager.runRecordStore().save(run2);

            // Add events for run-1 representing a completed trade (BUY -> SELL, PnL = 5.0)
            store.append("run-1", new RunEvent(
                RunEvent.SCHEMA_VERSION, RunEventType.FILL, Instant.now().minusSeconds(100), "run-1", "StratA", "EUR_USD", "PAPER",
                Map.of("symbol", "EUR_USD", "side", "BUY", "quantity", 10000.0, "price", 1.1000)
            ));
            store.append("run-1", new RunEvent(
                RunEvent.SCHEMA_VERSION, RunEventType.FILL, Instant.now().minusSeconds(80), "run-1", "StratA", "EUR_USD", "PAPER",
                Map.of("symbol", "EUR_USD", "side", "SELL", "quantity", 10000.0, "price", 1.1005)
            ));

            // Add events for run-2 representing a completed trade (SELL -> BUY, PnL = 20.0)
            store.append("run-2", new RunEvent(
                RunEvent.SCHEMA_VERSION, RunEventType.FILL, Instant.now().minusSeconds(50), "run-2", "StratA", "EUR_USD", "PAPER",
                Map.of("symbol", "EUR_USD", "side", "SELL", "quantity", 10000.0, "price", 1.1020)
            ));
            store.append("run-2", new RunEvent(
                RunEvent.SCHEMA_VERSION, RunEventType.FILL, Instant.now().minusSeconds(10), "run-2", "StratA", "EUR_USD", "PAPER",
                Map.of("symbol", "EUR_USD", "side", "BUY", "quantity", 10000.0, "price", 1.1000)
            ));

            ControlSummaryService service = new ControlSummaryService(manager);
            Map<String, Object> summary = service.buildSummary();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> runs = (List<Map<String, Object>>) summary.get("runs");
            
            // Since they share the same strategy, symbol, and mode, they are grouped into a single card.
            assertEquals(1, runs.size());
            Map<String, Object> runItem = runs.getFirst();

            double pnl = ((Number) runItem.get("realizedPnL")).doubleValue();
            assertEquals(25.0, pnl, 0.0001);
        }
    }

    @Test
    void testBuildSummaryGroupsByStrategyDeployment() throws Exception {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
             
            RunConfigSnapshot config1 = new RunConfigSnapshot(
                "StratB", "GBP_USD", "PAPER", "sample", 100, null, 100_000.0, null, null, "PAPER_STUB"
            );
            RunRecord run1 = manager.restoreRun("run-comp", config1);
            run1.markCompleted(Map.of());
            manager.runRecordStore().save(run1);

            RunRecord run2 = manager.restoreRun("run-active", config1);
            run2.markRunning();
            manager.runRecordStore().save(run2);

            ControlSummaryService service = new ControlSummaryService(manager);
            Map<String, Object> summary = service.buildSummary();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> runs = (List<Map<String, Object>>) summary.get("runs");
            
            // Should be exactly 1 card representing the active run
            assertEquals(1, runs.size());
            Map<String, Object> runItem = runs.getFirst();
            assertEquals("run-active", runItem.get("runId"));
            assertEquals("RUNNING", runItem.get("status"));
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
