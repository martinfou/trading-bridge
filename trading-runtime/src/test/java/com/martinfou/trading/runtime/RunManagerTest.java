package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunManagerTest {

    @Test
    void startRun_persistsEventsToStore() throws Exception {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {

            String runId = manager.startRun(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                new BarSourceResolver.BarsSource("sample", 300, null),
                100_000.0));

            RunRecord record = waitForCompletion(manager, runId);
            assertEquals(RunRecord.Status.COMPLETED, record.status());
            assertEquals(2, store.count(runId));
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
