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
}
