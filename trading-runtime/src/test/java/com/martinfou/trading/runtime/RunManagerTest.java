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
}
