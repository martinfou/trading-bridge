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
        
        Instant now = Instant.now();
        Instant lastEventAt = now.minus(Duration.ofSeconds(200));
        record.noteEventAt(lastEventAt);

        var summaryService = new ControlSummaryService(runManager, 120, Clock.systemUTC());

        try (var watchdog = new StaleRunWatchdog(runManager, summaryService)) {
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
}
