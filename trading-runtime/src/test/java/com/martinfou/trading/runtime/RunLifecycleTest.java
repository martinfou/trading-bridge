package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLifecycleTest {

    @Test
    void startRun_delegatesToRegisterAndStart() throws Exception {
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
        }
    }

    @Test
    void registerAndStart_matchesStartRun() throws Exception {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {

            RunConfigSnapshot config = new RunConfigSnapshot(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                "sample",
                300,
                null,
                100_000.0,
                null,
                null,
                null);

            RunRecord registered = manager.register(config);
            assertEquals(RunRecord.Status.CREATED, registered.status());

            manager.start(registered.runId());
            RunRecord completed = waitForCompletion(manager, registered.runId());
            assertEquals(RunRecord.Status.COMPLETED, completed.status());
            assertEquals(2, store.count(registered.runId()));
        }
    }

    @Test
    void pause_rejectsCompletedRun() throws Exception {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {

            RunConfigSnapshot config = new RunConfigSnapshot(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                "sample",
                50,
                null,
                100_000.0,
                null,
                null,
                null);

            RunRecord registered = manager.register(config);
            manager.start(registered.runId());
            waitForCompletion(manager, registered.runId());

            assertThrows(IllegalStateException.class, () -> manager.pause(registered.runId()));
        }
    }

    @Test
    void pauseAndResume_whileRunning() throws Exception {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {

            RunConfigSnapshot config = new RunConfigSnapshot(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                "sample",
                5000,
                null,
                100_000.0,
                null,
                null,
                null);

            RunRecord registered = manager.register(config);
            manager.start(registered.runId());

            RunRecord paused = manager.pause(registered.runId());
            assertEquals(RunRecord.Status.PAUSED, paused.status());

            manager.resume(registered.runId());
            waitForCompletion(manager, registered.runId());
        }
    }

    @Test
    void list_filtersByStatus() throws Exception {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {

            RunConfigSnapshot config = new RunConfigSnapshot(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                "sample",
                50,
                null,
                100_000.0,
                null,
                null,
                null);

            RunRecord a = manager.register(config);
            RunRecord b = manager.register(config);

            assertEquals(2, manager.list(RunRecord.Status.CREATED).size());
            assertTrue(manager.list(RunRecord.Status.RUNNING).isEmpty());

            manager.start(a.runId());
            waitForCompletion(manager, a.runId());

            assertTrue(manager.list(RunRecord.Status.COMPLETED).stream()
                .anyMatch(r -> r.runId().equals(a.runId())));
            assertEquals(1, manager.list(RunRecord.Status.CREATED).size());
            assertEquals(b.runId(), manager.list(RunRecord.Status.CREATED).getFirst().runId());
        }
    }

    @Test
    void stop_beforeStart_marksFailed() {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {

            RunConfigSnapshot config = new RunConfigSnapshot(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                "sample",
                50,
                null,
                100_000.0,
                null,
                null,
                null);

            RunRecord registered = manager.register(config);
            RunRecord stopped = manager.stop(registered.runId());

            assertEquals(RunRecord.Status.FAILED, stopped.status());
            assertTrue(stopped.errorMessage().orElse("").contains("stopped"));
        }
    }

    @Test
    void archive_afterCompletion() throws Exception {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {

            RunConfigSnapshot config = new RunConfigSnapshot(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                "sample",
                50,
                null,
                100_000.0,
                null,
                null,
                null);

            RunRecord registered = manager.register(config);
            manager.start(registered.runId());
            waitForCompletion(manager, registered.runId());

            RunRecord archived = manager.archive(registered.runId());
            assertEquals(RunRecord.Status.ARCHIVED, archived.status());
        }
    }

    @Test
    void transitionListener_notifiedOnRegisterStartAndComplete() throws Exception {
        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {

            List<RunTransition> causes = new CopyOnWriteArrayList<>();
            manager.addTransitionListener((before, after, cause) -> causes.add(cause));

            RunConfigSnapshot config = new RunConfigSnapshot(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                "sample",
                50,
                null,
                100_000.0,
                null,
                null,
                null);

            RunRecord registered = manager.register(config);
            manager.start(registered.runId());
            waitForCompletion(manager, registered.runId());

            assertTrue(causes.contains(RunTransition.REGISTER));
            assertTrue(causes.contains(RunTransition.START));
            assertTrue(causes.contains(RunTransition.COMPLETE));
        }
    }

    private static RunRecord waitForCompletion(RunManager manager, String runId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            RunRecord record = manager.getRun(runId).orElseThrow();
            if (record.status() == RunRecord.Status.COMPLETED
                || record.status() == RunRecord.Status.FAILED
                || record.status() == RunRecord.Status.ARCHIVED) {
                return record;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("timeout waiting for run " + runId);
    }
}
