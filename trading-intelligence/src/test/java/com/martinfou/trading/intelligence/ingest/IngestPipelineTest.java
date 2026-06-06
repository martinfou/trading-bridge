package com.martinfou.trading.intelligence.ingest;

import com.martinfou.trading.intelligence.brief.IngestStatus;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IngestPipelineTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-06-05T17:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void run_okWhenCalendarAndOandaPresent() throws Exception {
        IngestPipeline pipeline = new IngestPipeline(
            FIXED_CLOCK,
            week -> List.of(sampleEvent()),
            () -> List.of(new WeeklyIntelBrief.CotSnapshotEntry("EUR", 50, 50, null, LocalDate.of(2026, 6, 3))),
            () -> Optional.of(List.of(new WeeklyIntelBrief.OandaRetailEntry("EUR_USD", 55, 45, "Mixed"))),
            new NewsIngestStep(),
            new ContradictionDetector()
        );

        WeeklyIntelBrief brief = pipeline.run();
        assertEquals(IngestStatus.OK, brief.ingestStatus());
        assertEquals(LocalDate.of(2026, 6, 8), brief.weekStart());
        assertEquals(1, brief.calendarEvents().size());
    }

    @Test
    void run_partialWhenOandaMissing() throws Exception {
        IngestPipeline pipeline = new IngestPipeline(
            FIXED_CLOCK,
            week -> List.of(sampleEvent()),
            () -> List.of(),
            Optional::empty,
            new NewsIngestStep(),
            new ContradictionDetector()
        );

        WeeklyIntelBrief brief = pipeline.run();
        assertEquals(IngestStatus.PARTIAL, brief.ingestStatus());
        assertTrue(brief.sentiment().oandaRetail().isEmpty());
    }

    @Test
    void run_failsWhenCalendarThrows() {
        IngestPipeline pipeline = new IngestPipeline(
            FIXED_CLOCK,
            week -> { throw new CalendarIngestException("scrape down"); },
            List::of,
            Optional::empty,
            new NewsIngestStep(),
            new ContradictionDetector()
        );

        assertThrows(CalendarIngestException.class, pipeline::run);
    }

    @Test
    void run_failsWhenCalendarEmpty() {
        IngestPipeline pipeline = new IngestPipeline(
            FIXED_CLOCK,
            week -> List.of(),
            List::of,
            Optional::empty,
            new NewsIngestStep(),
            new ContradictionDetector()
        );

        assertThrows(CalendarIngestException.class, pipeline::run);
    }

    @Test
    void run_detectsContradiction() throws Exception {
        IngestPipeline pipeline = new IngestPipeline(
            FIXED_CLOCK,
            week -> List.of(sampleEvent()),
            () -> List.of(new WeeklyIntelBrief.CotSnapshotEntry("EUR", 70, 30, null, LocalDate.of(2026, 6, 3))),
            () -> Optional.of(List.of(new WeeklyIntelBrief.OandaRetailEntry("EUR_USD", 75, 25, "Bullish"))),
            new NewsIngestStep(),
            new ContradictionDetector()
        );

        WeeklyIntelBrief brief = pipeline.run();
        assertFalse(brief.contradictions().isEmpty());
        assertEquals("EUR_USD", brief.contradictions().getFirst().pairs().getFirst());
    }

    private static WeeklyIntelBrief.CalendarEventEntry sampleEvent() {
        return new WeeklyIntelBrief.CalendarEventEntry(
            "ff-2026-06-11-us-cpi",
            "US CPI",
            "USD",
            "HIGH",
            Instant.parse("2026-06-11T12:30:00Z"),
            "forexfactory"
        );
    }
}
