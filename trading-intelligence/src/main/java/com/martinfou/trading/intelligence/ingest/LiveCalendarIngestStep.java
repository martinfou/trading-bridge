package com.martinfou.trading.intelligence.ingest;

import com.martinfou.trading.data.ForexFactoryScraper;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Live calendar ingest via {@link ForexFactoryScraper}. */
public final class LiveCalendarIngestStep implements CalendarIngestStep {

    private static final Logger log = LoggerFactory.getLogger(LiveCalendarIngestStep.class);

    private final ForexFactoryScraper scraper;
    private final boolean allowFallback;

    public LiveCalendarIngestStep() {
        this(new ForexFactoryScraper(), false);
    }

    LiveCalendarIngestStep(ForexFactoryScraper scraper, boolean allowFallback) {
        this.scraper = scraper;
        this.allowFallback = allowFallback;
    }

    @Override
    public List<WeeklyIntelBrief.CalendarEventEntry> fetch(LocalDate weekStart) throws Exception {
        List<ForexFactoryScraper.Event> raw = scraper.fetchWeek(weekStart);
        if (raw.isEmpty()) {
            throw new CalendarIngestException("ForexFactory returned no calendar events for week " + weekStart);
        }
        if (!allowFallback && raw.stream().allMatch(LiveCalendarIngestStep::looksLikeFallback)) {
            throw new CalendarIngestException(
                "ForexFactory scrape unavailable — only hardcoded fallback events returned");
        }
        List<WeeklyIntelBrief.CalendarEventEntry> mapped = new ArrayList<>();
        for (ForexFactoryScraper.Event event : raw) {
            mapped.add(new WeeklyIntelBrief.CalendarEventEntry(
                eventId(event),
                event.event(),
                event.currency(),
                event.impact(),
                event.time(),
                "forexfactory"
            ));
        }
        log.info("Calendar ingest: {} events for week starting {}", mapped.size(), weekStart);
        return mapped;
    }

    static String eventId(ForexFactoryScraper.Event event) {
        String slug = (event.currency() + "-" + event.event())
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
        return "ff-" + event.time().toString().substring(0, 10) + "-" + slug;
    }

    private static boolean looksLikeFallback(ForexFactoryScraper.Event event) {
        return event.previous().isEmpty() && event.forecast().isEmpty() && event.actual().isEmpty();
    }
}
