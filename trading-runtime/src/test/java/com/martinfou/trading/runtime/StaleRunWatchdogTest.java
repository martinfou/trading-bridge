package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StaleRunWatchdogTest {

    @Test
    void testCheckStaleRuns_restartsStaleRun() throws Exception {
        var store = new InMemoryEventStore();
        var runManager = new RunManager(store);

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

        String runId = "old-run-id";
        RunRecord record = runManager.restoreRun(runId, config);
        record.markRunning();
        
        Instant checkTime = Instant.parse("2026-06-24T11:00:00Z"); // Wednesday (market open)
        Clock fixedClock = Clock.fixed(checkTime, java.time.ZoneOffset.UTC);
        
        Instant lastEventAt = checkTime.minus(Duration.ofSeconds(200));
        record.noteEventAt(lastEventAt);

        var summaryService = new ControlSummaryService(runManager, 120, fixedClock);

        try (var watchdog = new StaleRunWatchdog(runManager, summaryService, fixedClock)) {
            watchdog.reconnectTimeoutMs = 10;
            watchdog.reconnectCheckIntervalMs = 5;
            watchdog.checkStaleRuns();
            
            assertEquals(RunRecord.Status.COMPLETED, record.status(), "Expected old run to be COMPLETED");
            
            // register() is synchronous inside checkStaleRuns(), but poll with a bounded wait so the
            // test stays correct if the restart ever becomes asynchronous. The observable condition is
            // the appearance of a run whose id differs from the stale one.
            RunRecord newRun = awaitNewRun(runManager, runId);
            
            assertNotNull(newRun, "Expected new run to be registered after the restart");
            assertEquals("LondonOpenRangeBreakout", newRun.strategyId());
            assertEquals("EUR_USD", newRun.symbol());
            // A successful restart marks the new run RUNNING before the backtest is submitted on a worker
            // thread. A 500-bar backtest can already have COMPLETED by the time we observe it, so both are
            // valid outcomes; CREATED means start() silently failed and FAILED means the backtest crashed,
            // either of which this assertion must still catch.
            assertTrue(newRun.status() == RunRecord.Status.RUNNING || newRun.status() == RunRecord.Status.COMPLETED,
                "Expected new run to be RUNNING or COMPLETED, but was " + newRun.status());
        }
    }

    private static RunRecord awaitNewRun(RunManager runManager, String staleRunId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            for (RunRecord r : runManager.list(null)) {
                if (!r.runId().equals(staleRunId)) {
                    return r;
                }
            }
            Thread.sleep(10);
        }
        return null;
    }

    @Test
    void testCheckStaleRuns_skipsStaleCheckWhenMarketClosed() throws Exception {
        var store = new InMemoryEventStore();
        var runManager = new RunManager(store);

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
            "PAPER_OANDA",
            "acct1"
        );

        String runId = "closed-market-run-id";
        RunRecord record = runManager.restoreRun(runId, config);
        record.markRunning();
        
        // Stale run: last event was 200 seconds ago
        Instant lastEventAt = Instant.parse("2026-06-20T11:00:00Z"); // Saturday morning
        record.noteEventAt(lastEventAt);

        // Saturday morning (market is closed)
        Instant checkTime = Instant.parse("2026-06-20T11:03:20Z");
        Clock fixedClock = Clock.fixed(checkTime, java.time.ZoneOffset.UTC);

        var summaryService = new ControlSummaryService(runManager, 120, fixedClock);

        try (var watchdog = new StaleRunWatchdog(runManager, summaryService, fixedClock)) {
            watchdog.checkStaleRuns();
            
            // Should skip the stale check and NOT complete the run
            assertEquals(RunRecord.Status.RUNNING, record.status(), "Expected run to remain RUNNING because market is closed");
            
            List<RunRecord> activeRuns = runManager.list(null);
            assertEquals(1, activeRuns.size(), "Expected no new run to be registered");
        }
    }

    @Test
    void testCheckStaleRuns_reconnectsBrokerFirst() throws Exception {
        var store = new InMemoryEventStore();
        var runManager = new TrackingRunManager(store);

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

        String runId = "stale-run-id";
        RunRecord record = runManager.restoreRun(runId, config);
        record.markRunning();
        
        Instant checkTime = Instant.parse("2026-06-24T11:00:00Z"); // Wednesday
        Clock fixedClock = Clock.fixed(checkTime, java.time.ZoneOffset.UTC);
        
        Instant lastEventAt = checkTime.minus(Duration.ofSeconds(200));
        record.noteEventAt(lastEventAt);

        var summaryService = new ControlSummaryService(runManager, 120, fixedClock);

        try (var watchdog = new StaleRunWatchdog(runManager, summaryService, fixedClock)) {
            watchdog.reconnectTimeoutMs = 10;
            watchdog.reconnectCheckIntervalMs = 5;
            watchdog.checkStaleRuns();
            
            assertTrue(runManager.reconnectCalled, "Expected reconnectBroker to be called");
            assertEquals("stale-run-id", runManager.reconnectedRunId);
        }
    }

    @Test
    void testCheckStaleRuns_throttlesRestarts() throws Exception {
        var store = new InMemoryEventStore();
        var runManager = new RunManager(store);

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

        String runId = "throttled-run-id";
        RunRecord record = runManager.restoreRun(runId, config);
        record.markRunning();
        
        // 3 restarts already occurred within the last hour
        Instant checkTime = Instant.parse("2026-06-24T11:00:00Z");
        record.setRestartCount(3, checkTime.minus(Duration.ofMinutes(10)));
        
        Instant lastEventAt = checkTime.minus(Duration.ofSeconds(200));
        record.noteEventAt(lastEventAt);

        Clock fixedClock = Clock.fixed(checkTime, java.time.ZoneOffset.UTC);
        var summaryService = new ControlSummaryService(runManager, 120, fixedClock);

        try (var watchdog = new StaleRunWatchdog(runManager, summaryService, fixedClock)) {
            watchdog.reconnectTimeoutMs = 10;
            watchdog.reconnectCheckIntervalMs = 5;
            watchdog.checkStaleRuns();
            
            // Should skip restart because it is throttled
            assertEquals(RunRecord.Status.RUNNING, record.status(), "Expected run to remain RUNNING because it is throttled");
            assertEquals(1, runManager.list(null).size(), "Expected no new run to be registered");
        }
    }


    static class TrackingRunManager extends RunManager {
        boolean reconnectCalled = false;
        String reconnectedRunId = null;

        TrackingRunManager(EventStore eventStore) {
            super(eventStore);
        }

        @Override
        public void reconnectBroker(String runId) {
            this.reconnectCalled = true;
            this.reconnectedRunId = runId;
        }
    }
}
