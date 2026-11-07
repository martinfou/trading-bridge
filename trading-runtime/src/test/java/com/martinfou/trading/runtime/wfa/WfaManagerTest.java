package com.martinfou.trading.runtime.wfa;

import com.martinfou.trading.backtest.persistence.SqliteWfaRunStore;
import com.martinfou.trading.backtest.persistence.WfaRunRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WfaManagerTest {

    @TempDir
    Path tempDir;

    private SqliteWfaRunStore runStore;
    private Path reportsDir;
    private WfaManager manager;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("wfa_test.db");
        reportsDir = tempDir.resolve("reports");
        runStore = new SqliteWfaRunStore(dbPath);
        manager = new WfaManager(runStore, reportsDir);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void testStartWfaRunAndRejectionOfConcurrentRuns() {
        WfaRunRequest request = new WfaRunRequest(
            "LtCrossMomentum",
            "M2K",
            "FUTURES",
            "H1",
            "2024-01-01T00:00:00Z",
            "2024-06-01T00:00:00Z",
            60,
            20,
            false,
            10000.0,
            List.of(
                new ParameterRangeDto("fastPeriod", 5.0, 10.0, 5.0),
                new ParameterRangeDto("slowPeriod", 20.0, 30.0, 10.0)
            )
        );

        String wfaId = manager.startWfaRun(request);
        assertNotNull(wfaId);

        // Attempting to start another run immediately should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> manager.startWfaRun(request));

        // Verify initial progress
        Optional<WfaProgressResponse> progress = manager.getProgress(wfaId);
        assertTrue(progress.isPresent());
        assertEquals("M2K", progress.get().symbol());
        assertEquals("FUTURES", progress.get().assetClass());
    }

    @Test
    void testWfaRunExecutionAndPersistence() throws Exception {
        WfaRunRequest request = new WfaRunRequest(
            "LtCrossMomentum",
            "EUR_USD",
            "FOREX",
            "H1",
            "2024-01-01T00:00:00Z",
            "2024-06-01T00:00:00Z",
            60,
            20,
            false,
            10000.0,
            List.of(
                new ParameterRangeDto("fastPeriod", 5.0, 10.0, 5.0),
                new ParameterRangeDto("slowPeriod", 20.0, 30.0, 10.0)
            )
        );

        String wfaId = manager.startWfaRun(request);
        assertNotNull(wfaId);

        // Wait for background completion (up to 15s)
        long deadline = System.currentTimeMillis() + 15000;
        WfaProgressResponse finalProgress = null;
        while (System.currentTimeMillis() < deadline) {
            var progOpt = manager.getProgress(wfaId);
            if (progOpt.isPresent() && ("COMPLETED".equals(progOpt.get().status()) || "FAILED".equals(progOpt.get().status()))) {
                finalProgress = progOpt.get();
                break;
            }
            Thread.sleep(200);
        }

        assertNotNull(finalProgress, "WFA job did not finish in time");
        assertEquals("COMPLETED", finalProgress.status(), "Job failed: " + finalProgress.errorMessage());

        // Verify stored in SQLite
        Optional<WfaRunRecord> recordOpt = runStore.findById(wfaId);
        assertTrue(recordOpt.isPresent());
        assertEquals("COMPLETED", recordOpt.get().status());
        assertNotNull(recordOpt.get().completedAt());

        // Verify report JSON file was written
        Optional<String> reportJson = manager.getReportJson(wfaId);
        assertTrue(reportJson.isPresent());
        assertTrue(reportJson.get().contains("oosSharpe"));

        // Verify summary list
        List<WfaSummaryResponse> list = manager.listRuns(10);
        assertFalse(list.isEmpty());
        assertEquals(wfaId, list.get(0).wfaId());
    }

    @Test
    void testWfaParameterInspectorAndGeneratedStrategyExecution() throws Exception {
        var ltParams = WfaParameterInspector.inspect("LtCrossMomentum");
        assertFalse(ltParams.isEmpty());
        assertTrue(ltParams.stream().anyMatch(p -> "fastPeriod".equals(p.name())));

        var mgcParams = WfaParameterInspector.inspect("MgcM30Breakout49205");
        assertFalse(mgcParams.isEmpty());

        WfaRunRequest request = new WfaRunRequest(
            "MgcM30Breakout49205",
            "MGC",
            "FUTURES",
            "M30",
            "2024-01-01T00:00:00Z",
            "2024-06-01T00:00:00Z",
            60,
            20,
            false,
            10000.0,
            List.of(
                new ParameterRangeDto("period1", 10.0, 20.0, 10.0),
                new ParameterRangeDto("period2", 30.0, 50.0, 20.0)
            )
        );

        String wfaId = manager.startWfaRun(request);
        long deadline = System.currentTimeMillis() + 15000;
        WfaProgressResponse finalProgress = null;
        while (System.currentTimeMillis() < deadline) {
            var progOpt = manager.getProgress(wfaId);
            if (progOpt.isPresent() && ("COMPLETED".equals(progOpt.get().status()) || "FAILED".equals(progOpt.get().status()))) {
                finalProgress = progOpt.get();
                break;
            }
            Thread.sleep(200);
        }

        assertNotNull(finalProgress);
        assertEquals("COMPLETED", finalProgress.status(), "Job failed: " + finalProgress.errorMessage());
    }
}
