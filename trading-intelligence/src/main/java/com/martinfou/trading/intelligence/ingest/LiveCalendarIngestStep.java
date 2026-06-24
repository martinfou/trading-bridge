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
        if (raw == null || raw.isEmpty()) {
            throw new CalendarIngestException("ForexFactory returned no calendar events for week " + weekStart);
        }
        if (!allowFallback && raw.stream().allMatch(e -> e != null && looksLikeFallback(e))) {
            throw new CalendarIngestException(
                "ForexFactory scrape unavailable — only hardcoded fallback events returned");
        }
        List<WeeklyIntelBrief.CalendarEventEntry> mapped = new ArrayList<>();
        for (ForexFactoryScraper.Event event : raw) {
            if (event == null) {
                continue;
            }
            mapped.add(new WeeklyIntelBrief.CalendarEventEntry(
                eventId(event),
                event.event() != null ? event.event() : "Unknown",
                event.currency() != null ? event.currency() : "Unknown",
                event.impact() != null ? event.impact() : "Unknown",
                event.time(),
                "forexfactory"
            ));
        }
        log.info("Calendar ingest: {} events for week starting {}", mapped.size(), weekStart);
        return mapped;
    }

    static String eventId(ForexFactoryScraper.Event event) {
        if (event == null) {
            return "ff-unknown";
        }
        String currency = event.currency() != null ? event.currency() : "unknown";
        String name = event.event() != null ? event.event() : "unknown";
        String slug = (currency + "-" + name)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");

        String timeStr = "unknown-date";
        if (event.time() != null) {
            String ts = event.time().toString();
            if (ts.length() >= 10) {
                timeStr = ts.substring(0, 10);
            } else {
                timeStr = ts;
            }
        }
        return "ff-" + timeStr + "-" + slug;
    }

    private static boolean looksLikeFallback(ForexFactoryScraper.Event event) {
        return event.previous().isEmpty() && event.forecast().isEmpty() && event.actual().isEmpty();
    }
}
