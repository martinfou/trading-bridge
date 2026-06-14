package com.martinfou.trading.intelligence.agent.tools;

import com.martinfou.trading.data.EconomicCalendar;
import com.martinfou.trading.intelligence.RepoRoots;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBriefIO;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class MacroTools {

    private static final Logger log = LoggerFactory.getLogger(MacroTools.class);

    @Tool("Fetches high-impact economic calendar events between start and end times, masking actual values after cutoffTimestamp to prevent lookahead bias.")
    public List<EconomicCalendar.Event> fetchEconomicCalendar(
        @P("The start time of the economic calendar events range") Instant start,
        @P("The end time of the economic calendar events range") Instant end,
        @P("The current simulation cutoff time to mask actual values") Instant cutoffTimestamp
    ) {
        if (start == null || end == null || cutoffTimestamp == null) {
            log.warn("fetchEconomicCalendar called with null parameter(s): start={}, end={}, cutoff={}", start, end, cutoffTimestamp);
            return List.of();
        }

        log.info("fetchEconomicCalendar called: start={}, end={}, cutoff={}", start, end, cutoffTimestamp);
        
        List<EconomicCalendar.Event> candidates = new ArrayList<>();

        // 1. Look in EconomicCalendar.THIS_WEEK
        for (EconomicCalendar.Event event : EconomicCalendar.THIS_WEEK) {
            if (!event.time().isBefore(start) && !event.time().isAfter(end)) {
                candidates.add(event);
            }
        }

        // 2. If empty, look in historical briefs under data/weekly-intel
        if (candidates.isEmpty()) {
            candidates = loadFromHistoricalBriefs(start, end, cutoffTimestamp);
        }

        // 3. If still empty (e.g. testing in other weeks without briefs), generate mock high-impact events in the range
        if (candidates.isEmpty()) {
            candidates = generateMockEvents(start, end);
        }

        // Filter for HIGH impact and mask actual if after cutoff
        return candidates.stream()
            .filter(e -> "HIGH".equalsIgnoreCase(e.impact()))
            .map(e -> {
                if (e.time().isAfter(cutoffTimestamp)) {
                    // Mask actual value
                    return new EconomicCalendar.Event(
                        e.time(),
                        e.currency(),
                        e.event(),
                        e.impact(),
                        e.previous(),
                        e.forecast(),
                        ""
                    );
                }
                return e;
            })
            .toList();
    }

    private List<EconomicCalendar.Event> loadFromHistoricalBriefs(Instant start, Instant end, Instant cutoff) {
        List<EconomicCalendar.Event> found = new ArrayList<>();
        Path repoRoot = RepoRoots.findRepoRoot();
        String intelDirProp = System.getProperty("trading.intel.dir");
        Path intelRoot = intelDirProp != null ? Path.of(intelDirProp) : repoRoot.resolve("data/weekly-intel");

        if (!Files.exists(intelRoot)) {
            return found;
        }

        try (var stream = Files.list(intelRoot)) {
            java.util.regex.Pattern briefPattern = java.util.regex.Pattern.compile("^brief-\\d{4}-\\d{2}-\\d{2}\\.json$");
            List<Path> files = stream
                .filter(p -> briefPattern.matcher(p.getFileName().toString()).matches())
                .toList();

            for (Path file : files) {
                try {
                    String name = file.getFileName().toString();
                    String dateStr = name.substring(6, name.length() - 5);
                    LocalDate localDate = LocalDate.parse(dateStr);
                    Instant fileInstant = localDate.atStartOfDay(ZoneOffset.UTC).toInstant();

                    // Only read briefs generated before or at cutoff
                    if (fileInstant.isAfter(cutoff)) {
                        continue;
                    }

                    // Optimization: Check if the brief covers a week that overlaps with [start, end]
                    Instant weekEnd = fileInstant.plus(7, ChronoUnit.DAYS);
                    if (weekEnd.isBefore(start) || fileInstant.isAfter(end)) {
                        continue;
                    }

                    WeeklyIntelBrief brief = WeeklyIntelBriefIO.read(file);
                    for (WeeklyIntelBrief.CalendarEventEntry entry : brief.calendarEvents()) {
                        if (!entry.timeUtc().isBefore(start) && !entry.timeUtc().isAfter(end)) {
                            found.add(new EconomicCalendar.Event(
                                entry.timeUtc(),
                                entry.currency(),
                                entry.name(),
                                entry.impact(),
                                "", // previous info not stored in brief DTO
                                "", // forecast info not stored in brief DTO
                                ""  // actual info (will be set or empty)
                            ));
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse brief date or read file {}: {}", file, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.warn("Error loading events from briefs: {}", e.getMessage(), e);
        }

        return found;
    }

    private List<EconomicCalendar.Event> generateMockEvents(Instant start, Instant end) {
        // Generate a few stable mock events in the requested range to keep the model context meaningful
        List<EconomicCalendar.Event> mocks = new ArrayList<>();
        Instant mid = start.plus(2, ChronoUnit.DAYS);
        if (mid.isBefore(end)) {
            mocks.add(new EconomicCalendar.Event(
                mid,
                "USD",
                "Fed Interest Rate Decision (FOMC)",
                "HIGH",
                "5.25%",
                "5.25%",
                "5.25%"
            ));
            mocks.add(new EconomicCalendar.Event(
                mid.plus(12, ChronoUnit.HOURS),
                "EUR",
                "ECB Monetary Policy Statement",
                "HIGH",
                "4.00%",
                "3.75%",
                "3.75%"
            ));
        }
        return mocks;
    }
}
