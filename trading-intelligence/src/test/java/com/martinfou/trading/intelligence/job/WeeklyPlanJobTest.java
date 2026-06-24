package com.martinfou.trading.intelligence.job;

import com.martinfou.trading.intelligence.brief.IngestStatus;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;
import com.martinfou.trading.intelligence.ingest.CalendarIngestException;
import com.martinfou.trading.intelligence.ingest.IngestPipeline;
import com.martinfou.trading.intelligence.llm.StubLlmClient;
import com.martinfou.trading.intelligence.paths.WeeklyBuilderPaths;
import com.martinfou.trading.intelligence.plan.WeeklyPlanIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WeeklyPlanJobTest {

    @TempDir
    Path tempDir;

    @Test
    void run_writesApprovedPlanToPending() throws Exception {
        setupLayout(tempDir);
        IngestPipeline pipeline = stubPipeline();
        StubLlmClient llm = stubLlmApproved("T4", "EUR_USD");
        Clock clock = Clock.fixed(Instant.parse("2026-06-06T17:00:00Z"), ZoneOffset.UTC);

        WeeklyPlanJob job = new WeeklyPlanJob(tempDir, pipeline, llm, clock);
        WeeklyPlanJob.Result result = job.run();

        assertEquals(WeeklyPlanJob.Result.Status.APPROVED, result.status());
        Path pendingPlan = WeeklyPlanIO.planPath(WeeklyBuilderPaths.pending(tempDir), "2026-W24");
        assertTrue(Files.exists(pendingPlan));
        assertEquals("T4", WeeklyPlanIO.read(pendingPlan).picks().getFirst().templateId());
    }

    @Test
    void run_calendarFailureDoesNotWritePending() throws Exception {
        setupLayout(tempDir);
        IngestPipeline pipeline = IngestPipeline.create(
            Clock.systemUTC(),
            week -> { throw new CalendarIngestException("down"); },
            List::of,
            Optional::empty,
            new com.martinfou.trading.intelligence.ingest.NewsIngestStep(),
            new com.martinfou.trading.intelligence.ingest.ContradictionDetector()
        );

        WeeklyPlanJob job = new WeeklyPlanJob(tempDir, pipeline, stubLlmApproved("T8", null));
        WeeklyPlanJob.Result result = job.run();

        assertEquals(WeeklyPlanJob.Result.Status.CALENDAR_FAILED, result.status());
        try (var stream = Files.list(WeeklyBuilderPaths.pending(tempDir))) {
            assertTrue(stream.findAny().isEmpty());
        }
    }

    @Test
    void run_calendarFailureWithNullMessageDoesNotThrowNpe() throws Exception {
        setupLayout(tempDir);
        IngestPipeline pipeline = IngestPipeline.create(
            Clock.systemUTC(),
            week -> { throw new CalendarIngestException(null); },
            List::of,
            Optional::empty,
            new com.martinfou.trading.intelligence.ingest.NewsIngestStep(),
            new com.martinfou.trading.intelligence.ingest.ContradictionDetector()
        );

        WeeklyPlanJob job = new WeeklyPlanJob(tempDir, pipeline, stubLlmApproved("T8", null));
        WeeklyPlanJob.Result result = job.run();

        assertEquals(WeeklyPlanJob.Result.Status.CALENDAR_FAILED, result.status());
        Path failedDir = WeeklyBuilderPaths.failed(tempDir);
        assertTrue(Files.exists(failedDir));
        try (var stream = Files.list(failedDir)) {
            assertTrue(stream.findAny().isPresent());
        }
    }

    private static void setupLayout(Path repoRoot) throws Exception {
        WeeklyBuilderPaths.ensureLayout(repoRoot);
    }

    private static IngestPipeline stubPipeline() {
        return IngestPipeline.create(
            Clock.fixed(Instant.parse("2026-06-06T17:00:00Z"), ZoneOffset.UTC),
            week -> List.of(new WeeklyIntelBrief.CalendarEventEntry(
                "ff-2026-06-11-us-cpi", "US CPI", "USD", "HIGH",
                Instant.parse("2026-06-11T12:30:00Z"), "forexfactory")),
            List::of,
            Optional::empty,
            new com.martinfou.trading.intelligence.ingest.NewsIngestStep(),
            new com.martinfou.trading.intelligence.ingest.ContradictionDetector()
        );
    }

    private static StubLlmClient stubLlmApproved(String templateId, String pair) {
        String pairJson = pair == null ? "null" : "\"" + pair + "\"";
        String directionJson = pair == null ? "null" : "\"LONG\"";
        String plannerJson = """
            {
              "weekId": "2026-W24",
              "picks": [{
                "templateId": "%s",
                "pair": %s,
                "direction": %s,
                "params": {},
                "sources": ["ff-2026-06-11-us-cpi"],
                "rationale": "test"
              }],
              "briefRef": "brief-2026-06-06.json",
              "riskEnvelopeSnapshot": {
                "maxPicks": 3,
                "maxLotSize": 0.01,
                "maxWeeklyDrawdownPct": 5.0,
                "whitelistPairs": ["EUR_USD","GBP_USD","USD_JPY","GBP_JPY","AUD_USD","USD_CAD"]
              }
            }
            """.formatted(templateId, pairJson, directionJson);
        String reviewerJson = plannerJson.replace(
            "\"riskEnvelopeSnapshot\"",
            "\"reviewerStatus\": \"APPROVED\",\n  \"riskEnvelopeSnapshot\""
        );
        return new StubLlmClient(plannerJson, reviewerJson);
    }
}
