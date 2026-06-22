package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.broker.FakeBroker;
import java.time.Instant;
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

    @Test
    void startRun_rejectsDuplicateActiveRun() throws Exception {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            
            var request = new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "PAPER",
                new BarSourceResolver.BarsSource("sample", 50, null),
                10_000.0,
                1.0,
                0.0,
                0.0,
                ExecutionLabel.PAPER_STUB.name(),
                "default"
            );
            RunConfigSnapshot configSnapshot = new RunConfigSnapshot(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "PAPER",
                "sample",
                50,
                null,
                null,
                10_000.0,
                null,
                0.0,
                0.0,
                ExecutionLabel.PAPER_STUB.name(),
                "default",
                "H1",
                "H1",
                5.0,
                5.0,
                10.0
            );
            RunRecord record = manager.register(configSnapshot);
            record.markRunning();

            assertThrows(IllegalArgumentException.class, () -> manager.startRun(
                new RunManager.StartRunRequest(
                    "LondonOpenRangeBreakout",
                    "EUR_USD",
                    "PAPER",
                    new BarSourceResolver.BarsSource("sample", 50, null),
                    10_000.0,
                    1.0,
                    0.0,
                    0.0,
                    ExecutionLabel.PAPER_STUB.name(),
                    "default"
                )
            ));
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
    @org.junit.jupiter.api.Disabled("Disabled to prevent unit tests from relying on external network data sources (Dukascopy)")
    void startRun_triggersJavaNativeDownloadAndConvertsSuccessfully() throws Exception {
        String original = System.getProperty("trading.bridge.test");
        System.clearProperty("trading.bridge.test");
        try {
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("data/historical/bars/EUR_USD_H1_2025.bars"));
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("data/historical/dukascopy/eurusd-h1-bid-2025-01-01-2025-12-31.csv"));

            try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
                 RunManager manager = new RunManager(stores.eventStore())) {

                String runId = manager.startRun(new RunManager.StartRunRequest(
                    "LondonOpenRangeBreakout",
                    "EUR_USD",
                    "BACKTEST",
                    new BarSourceResolver.BarsSource("year", 0, 2025),
                    100_000.0,
                    null,
                    null,
                    null,
                    null));

                RunRecord record = null;
                for (int i = 0; i < 600; i++) {
                    record = manager.getRun(runId).orElseThrow();
                    if (record.status() != RunRecord.Status.RUNNING) {
                        break;
                    }
                    Thread.sleep(100);
                }
                assertEquals(RunRecord.Status.COMPLETED, record.status());

                assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of("data/historical/bars/EUR_USD_H1_2025.bars")));
                assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of("data/historical/dukascopy/eurusd-h1-bid-2025-01-01-2025-12-31.csv")));
            }
        } finally {
            if (original != null) {
                System.setProperty("trading.bridge.test", original);
            }
        }
    }

    @Test
    void testReconciliation_consecutiveTimeDrifts_pausesStrategy() throws Exception {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            
            // Register an active PAPER run
            RunConfigSnapshot config = RunConfigSnapshot.fromRequest(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "PAPER",
                new BarSourceResolver.BarsSource("sample", 50, null),
                10_000.0,
                1.0,
                0.0,
                0.0,
                ExecutionLabel.PAPER_STUB.name(),
                "default"
            ), "EUR_USD");
            
            RunRecord record = manager.register(config);
            record.markRunning();
            
            String runId = record.runId();
            Instant baseTime = Instant.parse("2026-06-20T12:00:00Z");
            
            // Submit and fill 3 time-drifted orders
            for (int i = 0; i < 3; i++) {
                String orderId = "order-" + i;
                String correlationId = "corr-" + i;
                
                // ORDER_SUBMITTED
                var subPayload = new java.util.LinkedHashMap<String, Object>();
                subPayload.put("orderId", orderId);
                subPayload.put("symbol", "EUR_USD");
                subPayload.put("side", "BUY");
                subPayload.put("quantity", 1000.0);
                subPayload.put("price", 1.0850);
                subPayload.put("correlationId", correlationId);
                
                manager.eventStore().append(runId, new com.martinfou.trading.backtest.events.RunEvent(
                    com.martinfou.trading.backtest.events.RunEvent.SCHEMA_VERSION,
                    RunEventType.ORDER_SUBMITTED,
                    baseTime.plusSeconds(i * 60),
                    runId,
                    "LondonOpenRangeBreakout",
                    "EUR_USD",
                    "PAPER",
                    subPayload
                ));
                
                // FILL (with 10 seconds time drift)
                var fillPayload = new java.util.LinkedHashMap<String, Object>();
                fillPayload.put("orderId", orderId);
                fillPayload.put("symbol", "EUR_USD");
                fillPayload.put("side", "BUY");
                fillPayload.put("quantity", 1000.0);
                fillPayload.put("price", 1.0850);
                fillPayload.put("correlationId", correlationId);
                
                manager.eventStore().append(runId, new com.martinfou.trading.backtest.events.RunEvent(
                    com.martinfou.trading.backtest.events.RunEvent.SCHEMA_VERSION,
                    RunEventType.FILL,
                    baseTime.plusSeconds(i * 60 + 10), // 10s delay > 5s maxTimeDriftSeconds
                    runId,
                    "LondonOpenRangeBreakout",
                    "EUR_USD",
                    "PAPER",
                    fillPayload
                ));
            }
            
            // ReconcilingEventStore inside manager should intercept, detect 3 consecutive time drifts, and pause!
            assertEquals(RunRecord.Status.PAUSED, record.status());
        }
    }

    @Test
    void testReconciliation_logicDiscrepancy_terminatesStrategy() throws Exception {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            
            // Register an active PAPER run
            RunConfigSnapshot config = RunConfigSnapshot.fromRequest(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "PAPER",
                new BarSourceResolver.BarsSource("sample", 50, null),
                10_000.0,
                1.0,
                0.0,
                0.0,
                ExecutionLabel.PAPER_STUB.name(),
                "default"
            ), "EUR_USD");
            
            RunRecord record = manager.register(config);
            record.markRunning();
            
            String runId = record.runId();
            Instant baseTime = Instant.parse("2026-06-20T12:00:00Z");
            
            // 1. ORDER_SUBMITTED (Backtest order generated)
            var subPayload = new java.util.LinkedHashMap<String, Object>();
            subPayload.put("orderId", "order-1");
            subPayload.put("symbol", "EUR_USD");
            subPayload.put("side", "BUY");
            subPayload.put("quantity", 1000.0);
            subPayload.put("price", 1.0850);
            subPayload.put("correlationId", "corr-1");
            
            manager.eventStore().append(runId, new com.martinfou.trading.backtest.events.RunEvent(
                com.martinfou.trading.backtest.events.RunEvent.SCHEMA_VERSION,
                RunEventType.ORDER_SUBMITTED,
                baseTime,
                runId,
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "PAPER",
                subPayload
            ));
            
            // 2. FILL of a GHOST trade (an order present in live but missing correlation/order in backtest)
            var ghostPayload = new java.util.LinkedHashMap<String, Object>();
            ghostPayload.put("orderId", "ghost-order");
            ghostPayload.put("symbol", "EUR_USD");
            ghostPayload.put("side", "SELL");
            ghostPayload.put("quantity", 1000.0);
            ghostPayload.put("price", 1.0855);
            ghostPayload.put("correlationId", "ghost-corr");
            
            manager.eventStore().append(runId, new com.martinfou.trading.backtest.events.RunEvent(
                com.martinfou.trading.backtest.events.RunEvent.SCHEMA_VERSION,
                RunEventType.FILL,
                baseTime.plusSeconds(5),
                runId,
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "PAPER",
                ghostPayload
            ));
            
            // ReconcilingEventStore inside manager should intercept, detect GHOST_LIVE anomaly, and trigger kill-switch
            assertTrue(record.status() == RunRecord.Status.COMPLETED || record.status() == RunRecord.Status.FAILED);
        }
    }

    @Test
    void testPruneTerminalRuns() throws Exception {
        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {

            var config = new RunConfigSnapshot(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "PAPER",
                "sample",
                500,
                null,
                1000.0,
                0.07,
                1e-4,
                "PAPER_STUB",
                "acct1"
            );

            // 1. One currently RUNNING (should NOT be pruned)
            RunRecord run1 = manager.register(config);
            run1.markRunning();

            // 2. One completed recently, e.g. 5 minutes ago (should NOT be pruned)
            RunRecord run2 = manager.register(config);
            run2.markCompleted(java.util.Map.of());
            setCompletedAt(run2, Instant.now().minusSeconds(300)); // 5 minutes ago

            // 3. One completed a long time ago, e.g. 15 minutes ago (should be pruned)
            RunRecord run3 = manager.register(config);
            run3.markCompleted(java.util.Map.of());
            setCompletedAt(run3, Instant.now().minusSeconds(900)); // 15 minutes ago

            // Verify they are all in the manager initially
            assertTrue(manager.getRun(run1.runId()).isPresent());
            assertTrue(manager.getRun(run2.runId()).isPresent());
            assertTrue(manager.getRun(run3.runId()).isPresent());

            // Run pruning
            manager.pruneTerminalRuns();

            // Verify status
            assertTrue(manager.getRun(run1.runId()).isPresent(), "Running run should not be pruned");
            assertTrue(manager.getRun(run2.runId()).isPresent(), "Recently completed run should not be pruned");
            assertFalse(manager.getRun(run3.runId()).isPresent(), "Old completed run should be pruned");
        }
    }

    private void setCompletedAt(RunRecord record, Instant instant) throws Exception {
        java.lang.reflect.Field field = RunRecord.class.getDeclaredField("completedAt");
        field.setAccessible(true);
        field.set(record, instant);
    }
}
