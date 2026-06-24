package com.martinfou.trading.intelligence.ingest;

import com.martinfou.trading.intelligence.brief.IngestStatus;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;
import com.martinfou.trading.intelligence.time.WeekBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class IngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestPipeline.class);

    private final Clock clock;
    private final CalendarIngestStep calendarStep;
    private final CotIngestStep cotStep;
    private final OandaSentimentIngestStep oandaStep;
    private final NewsIngestStep newsStep;
    private final ContradictionDetector contradictionDetector;

    public IngestPipeline() {
        this(
            Clock.systemUTC(),
            new LiveCalendarIngestStep(),
            new LiveCotIngestStep(),
            new LiveOandaSentimentIngestStep(),
            new NewsIngestStep(),
            new ContradictionDetector()
        );
    }

    IngestPipeline(
        Clock clock,
        CalendarIngestStep calendarStep,
        CotIngestStep cotStep,
        OandaSentimentIngestStep oandaStep,
        NewsIngestStep newsStep,
        ContradictionDetector contradictionDetector
    ) {
        this.clock = clock;
        this.calendarStep = calendarStep;
        this.cotStep = cotStep;
        this.oandaStep = oandaStep;
        this.newsStep = newsStep;
        this.contradictionDetector = contradictionDetector;
    }

    /** Test and job wiring with injectable ingest steps. */
    public static IngestPipeline create(
        Clock clock,
        CalendarIngestStep calendarStep,
        CotIngestStep cotStep,
        OandaSentimentIngestStep oandaStep,
        NewsIngestStep newsStep,
        ContradictionDetector contradictionDetector
    ) {
        return new IngestPipeline(clock, calendarStep, cotStep, oandaStep, newsStep, contradictionDetector);
    }

    public WeeklyIntelBrief run() throws Exception {
        return run(WeekBounds.nextWeekMonday(clock));
    }

    /**
     * Runs ingest for the week starting at the specified {@code weekStart}.
     *
     * @throws CalendarIngestException when calendar scrape fails (no LLM handoff)
     */
    public WeeklyIntelBrief run(LocalDate weekStart) throws Exception {
        if (weekStart == null) {
            weekStart = WeekBounds.nextWeekMonday(clock);
        }
        Instant generatedAt = clock.instant();
        boolean partial = false;

        List<WeeklyIntelBrief.CalendarEventEntry> calendar;
        try {
            calendar = calendarStep.fetch(weekStart);
        } catch (CalendarIngestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CalendarIngestException("Calendar ingest failed", ex);
        }
        if (calendar == null || calendar.isEmpty()) {
            throw new CalendarIngestException("Calendar ingest returned no events");
        }

        List<WeeklyIntelBrief.CotSnapshotEntry> cot;
        try {
            cot = cotStep.fetch();
        } catch (Exception ex) {
            log.warn("COT ingest failed — continuing: {}", ex.getMessage());
            cot = List.of();
            partial = true;
        }

        Optional<List<WeeklyIntelBrief.OandaRetailEntry>> oandaOptional = oandaStep.fetchOptional();
        if (oandaOptional == null || oandaOptional.isEmpty()) {
            partial = true;
        }
        List<WeeklyIntelBrief.OandaRetailEntry> oandaRetail = new ArrayList<>();
        if (oandaOptional != null && oandaOptional.isPresent() && oandaOptional.get() != null) {
            for (WeeklyIntelBrief.OandaRetailEntry entry : oandaOptional.get()) {
                if (entry != null) {
                    oandaRetail.add(entry);
                }
            }
        }

        List<WeeklyIntelBrief.NewsItemEntry> news = null;
        try {
            news = newsStep.fetch();
        } catch (Exception ex) {
            log.warn("News ingest failed — continuing: {}", ex.getMessage());
            partial = true;
        }

        List<WeeklyIntelBrief.ContradictionEntry> contradictions = null;
        try {
            contradictions = contradictionDetector.detect(cot, oandaRetail);
        } catch (Exception ex) {
            log.warn("Contradiction detection failed — continuing: {}", ex.getMessage());
            partial = true;
        }

        IngestStatus status = partial ? IngestStatus.PARTIAL : IngestStatus.OK;

        return new WeeklyIntelBrief(
            generatedAt,
            weekStart,
            safeCopy(calendar),
            safeCopy(news),
            safeCopy(cot),
            new WeeklyIntelBrief.SentimentBlock(safeCopy(oandaRetail), List.of()),
            safeCopy(contradictions),
            status
        );
    }

    private <T> List<T> safeCopy(List<T> list) {
        if (list == null) {
            return List.of();
        }
        List<T> copy = new ArrayList<>();
        for (T item : list) {
            if (item != null) {
                copy.add(item);
            }
        }
        return List.copyOf(copy);
    }
}
