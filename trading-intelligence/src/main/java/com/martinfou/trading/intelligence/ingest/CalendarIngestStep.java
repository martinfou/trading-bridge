package com.martinfou.trading.intelligence.ingest;

import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;

import java.time.LocalDate;
import java.util.List;

@FunctionalInterface
public interface CalendarIngestStep {
    List<WeeklyIntelBrief.CalendarEventEntry> fetch(LocalDate weekStart) throws Exception;
}
