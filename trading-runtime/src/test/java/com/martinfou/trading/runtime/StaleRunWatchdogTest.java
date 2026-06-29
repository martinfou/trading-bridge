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
            
            List<RunRecord> activeRuns = runManager.list(null);
            assertTrue(activeRuns.size() > 1, "Expected new run to be registered");
            
            RunRecord newRun = activeRuns.stream()
                .filter(r -> !r.runId().equals("old-run-id"))
                .findFirst()
                .orElse(null);
            
            assertNotNull(newRun, "Expected new run to exist");
            assertEquals("LondonOpenRangeBreakout", newRun.strategyId());
            assertEquals("EUR_USD", newRun.symbol());
            assertTrue(newRun.status() == RunRecord.Status.RUNNING || newRun.status() == RunRecord.Status.CREATED);
        }
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
