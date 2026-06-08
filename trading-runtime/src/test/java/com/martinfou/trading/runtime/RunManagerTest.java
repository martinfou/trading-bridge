package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.broker.FakeBroker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunManagerTest {

    @Test
    void startRun_persistsEventsToStore() throws Exception {
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

            RunRecord record = waitForCompletion(manager, runId);
            assertEquals(RunRecord.Status.COMPLETED, record.status());
            assertEquals(2, stores.eventStore().count(runId));
            assertFalse(record.configHash().isBlank());
            var events = stores.eventStore().replayAll(runId);
            assertEquals(runId, events.getFirst().runId());
            assertEquals(runId, events.getLast().runId());
            assertTrue(events.getFirst().payload().containsKey("configSnapshot"));
            assertTrue(events.getFirst().payload().containsKey("configHash"));
        }
    }

    @Test
    void startRun_withExecutionCosts_persistsInConfigSnapshot() throws Exception {
        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {

            String runId = manager.startRun(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                new BarSourceResolver.BarsSource("sample", 300, null),
                100_000.0,
                5.0,
                0.0001,
                null));

            RunRecord record = waitForCompletion(manager, runId);
            assertEquals(RunRecord.Status.COMPLETED, record.status());

            @SuppressWarnings("unchecked")
            var snapshot = (java.util.Map<String, Object>) stores.eventStore()
                .replayAll(runId).getFirst().payload().get("configSnapshot");
            assertEquals(5.0, snapshot.get("commissionPerTrade"));
            assertEquals(0.0001, snapshot.get("slippagePct"));

            var ended = stores.eventStore().replayAll(runId).getLast().payload();
            assertTrue(ended.containsKey("totalCommission") || ended.containsKey("totalSlippage"));
        }
    }

    @Test
    void startRun_liveOanda_routesOrdersThroughBrokerOnWorker() throws Exception {
        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(
                 stores.eventStore(),
                 config -> new FakeBroker(config.capital() != null ? config.capital() : 100_000.0))) {

            String runId = manager.startRun(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "LIVE",
                new BarSourceResolver.BarsSource("sample", 300, null),
                100_000.0,
                null,
                null,
                null,
                ExecutionLabel.LIVE_OANDA.name()));

            RunRecord record = waitForCompletion(manager, runId);
            assertEquals(RunRecord.Status.COMPLETED, record.status());

            var events = stores.eventStore().replayAll(runId);
            assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.ORDER_SUBMITTED));
            assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.FILL));
            assertEquals("LIVE", events.getFirst().mode());
            assertTrue(events.getFirst().payload().containsKey("executionLabel"));
        }
    }

    @Test
    void startRun_rejectsUnknownStrategy() {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            assertThrows(IllegalArgumentException.class, () -> manager.startRun(
                new RunManager.StartRunRequest(
                    "NoSuchStrategy",
                    "EUR_USD",
                    "BACKTEST",
                    new BarSourceResolver.BarsSource("sample", 100, null),
                    null,
                    null,
                    null,
                    null)));
        }
    }

    private static RunRecord waitForCompletion(RunManager manager, String runId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            RunRecord record = manager.getRun(runId).orElseThrow();
            if (record.status() != RunRecord.Status.RUNNING) {
                return record;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("timeout waiting for run " + runId);
    }

    @Test
    void recoverRuns_restoresInMemoryAndRestartsBrokerRuns() throws Exception {
        try (EventStore store = EventStores.inMemory()) {
            // 1. A completed backtest run
            String completedRunId = "run-completed";
            java.util.Map<String, Object> completedConfig = java.util.Map.of(
                "strategyId", "LondonOpenRangeBreakout",
                "symbol", "EUR_USD",
                "mode", "BACKTEST",
                "barsSourceType", "sample",
                "barsSourceCount", 10,
                "capital", 100000.0,
                "executionLabel", "BACKTEST"
            );
            store.append(completedRunId, new com.martinfou.trading.backtest.events.RunEvent(
                com.martinfou.trading.backtest.events.RunEvent.SCHEMA_VERSION,
                RunEventType.RUN_STARTED,
                java.time.Instant.parse("2026-06-08T00:00:00Z"),
                completedRunId,
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                java.util.Map.of("configSnapshot", completedConfig)
            ));
            store.append(completedRunId, new com.martinfou.trading.backtest.events.RunEvent(
                com.martinfou.trading.backtest.events.RunEvent.SCHEMA_VERSION,
                RunEventType.RUN_ENDED,
                java.time.Instant.parse("2026-06-08T00:01:00Z"),
                completedRunId,
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                java.util.Map.of()
            ));

            // 2. An interrupted backtest run (should be marked failed)
            String interruptedBacktestId = "run-interrupted-backtest";
            java.util.Map<String, Object> interruptedConfig = java.util.Map.of(
                "strategyId", "LondonOpenRangeBreakout",
                "symbol", "EUR_USD",
                "mode", "BACKTEST",
                "barsSourceType", "sample",
                "barsSourceCount", 10,
                "capital", 100000.0,
                "executionLabel", "BACKTEST"
            );
            store.append(interruptedBacktestId, new com.martinfou.trading.backtest.events.RunEvent(
                com.martinfou.trading.backtest.events.RunEvent.SCHEMA_VERSION,
                RunEventType.RUN_STARTED,
                java.time.Instant.parse("2026-06-08T00:00:00Z"),
                interruptedBacktestId,
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                java.util.Map.of("configSnapshot", interruptedConfig)
            ));

            // 3. An active broker-backed run (should be auto-restarted)
            String activeBrokerRunId = "run-active-broker";
            java.util.Map<String, Object> brokerConfig = java.util.Map.of(
                "strategyId", "LondonOpenRangeBreakout",
                "symbol", "EUR_USD",
                "mode", "LIVE",
                "barsSourceType", "sample",
                "barsSourceCount", 10,
                "capital", 100000.0,
                "executionLabel", "LIVE_OANDA"
            );
            store.append(activeBrokerRunId, new com.martinfou.trading.backtest.events.RunEvent(
                com.martinfou.trading.backtest.events.RunEvent.SCHEMA_VERSION,
                RunEventType.RUN_STARTED,
                java.time.Instant.now(),
                activeBrokerRunId,
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "LIVE",
                java.util.Map.of("configSnapshot", brokerConfig)
            ));

            // 4. A stale broker-backed run (should NOT be auto-restarted, marked failed)
            String staleBrokerRunId = "run-stale-broker";
            java.util.Map<String, Object> staleBrokerConfig = java.util.Map.of(
                "strategyId", "LondonOpenRangeBreakout",
                "symbol", "USD_JPY",
                "mode", "LIVE",
                "barsSourceType", "sample",
                "barsSourceCount", 10,
                "capital", 100000.0,
                "executionLabel", "LIVE_OANDA"
            );
            store.append(staleBrokerRunId, new com.martinfou.trading.backtest.events.RunEvent(
                com.martinfou.trading.backtest.events.RunEvent.SCHEMA_VERSION,
                RunEventType.RUN_STARTED,
                java.time.Instant.parse("2026-06-08T00:00:00Z"),
                staleBrokerRunId,
                "LondonOpenRangeBreakout",
                "USD_JPY",
                "LIVE",
                java.util.Map.of("configSnapshot", staleBrokerConfig)
            ));

            // 5. Two duplicate broker-backed runs (only the newer one should be restarted, older marked failed)
            String duplicateOlderId = "run-duplicate-older";
            java.util.Map<String, Object> dupConfig = java.util.Map.of(
                "strategyId", "LondonOpenRangeBreakout",
                "symbol", "GBP_USD",
                "mode", "LIVE",
                "barsSourceType", "sample",
                "barsSourceCount", 10,
                "capital", 100000.0,
                "executionLabel", "LIVE_OANDA"
            );
            store.append(duplicateOlderId, new com.martinfou.trading.backtest.events.RunEvent(
                com.martinfou.trading.backtest.events.RunEvent.SCHEMA_VERSION,
                RunEventType.RUN_STARTED,
                java.time.Instant.now().minusSeconds(10),
                duplicateOlderId,
                "LondonOpenRangeBreakout",
                "GBP_USD",
                "LIVE",
                java.util.Map.of("configSnapshot", dupConfig)
            ));

            String duplicateNewerId = "run-duplicate-newer";
            store.append(duplicateNewerId, new com.martinfou.trading.backtest.events.RunEvent(
                com.martinfou.trading.backtest.events.RunEvent.SCHEMA_VERSION,
                RunEventType.RUN_STARTED,
                java.time.Instant.now(),
                duplicateNewerId,
                "LondonOpenRangeBreakout",
                "GBP_USD",
                "LIVE",
                java.util.Map.of("configSnapshot", dupConfig)
            ));

            // Now instantiate a new RunManager with the same store
            try (RunManager manager = new RunManager(
                store,
                config -> new FakeBroker(100_000.0))) {

                // Recover the runs
                manager.recoverRuns();

                // Verify 1: completed run is COMPLETED
                RunRecord record1 = manager.getRun(completedRunId).orElseThrow();
                assertEquals(RunRecord.Status.COMPLETED, record1.status());

                // Verify 2: interrupted backtest run is FAILED
                RunRecord record2 = manager.getRun(interruptedBacktestId).orElseThrow();
                assertEquals(RunRecord.Status.FAILED, record2.status());
                assertTrue(record2.errorMessage().orElse("").contains("Interrupted by server restart"));

                // Verify 3: active broker run is recovered and restarted (it eventually completes via FakeBroker)
                RunRecord record3 = waitForCompletion(manager, activeBrokerRunId);
                assertEquals(RunRecord.Status.COMPLETED, record3.status());

                // Verify 4: stale broker run is NOT restarted, marked FAILED with stale message
                RunRecord record4 = manager.getRun(staleBrokerRunId).orElseThrow();
                assertEquals(RunRecord.Status.FAILED, record4.status());
                assertTrue(record4.errorMessage().orElse("").contains("stale"));

                // Verify 5: newer duplicate run restarts, older is marked FAILED with duplicate message
                RunRecord recordDupOlder = manager.getRun(duplicateOlderId).orElseThrow();
                assertEquals(RunRecord.Status.FAILED, recordDupOlder.status());
                assertTrue(recordDupOlder.errorMessage().orElse("").contains("duplicate"));

                RunRecord recordDupNewer = waitForCompletion(manager, duplicateNewerId);
                assertEquals(RunRecord.Status.COMPLETED, recordDupNewer.status());
            }
        }
    }
}
