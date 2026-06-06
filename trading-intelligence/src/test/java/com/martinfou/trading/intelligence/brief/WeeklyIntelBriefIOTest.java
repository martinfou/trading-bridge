package com.martinfou.trading.intelligence.brief;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeeklyIntelBriefIOTest {

    @Test
    void writeAndRead_roundTrips(@TempDir Path tempDir) throws Exception {
        WeeklyIntelBrief original = new WeeklyIntelBrief(
            Instant.parse("2026-06-06T17:00:00Z"),
            LocalDate.of(2026, 6, 9),
            List.of(new WeeklyIntelBrief.CalendarEventEntry(
                "ff-2026-06-11-us-cpi",
                "US CPI",
                "USD",
                "HIGH",
                Instant.parse("2026-06-11T12:30:00Z"),
                "forexfactory"
            )),
            List.of(),
            List.of(),
            WeeklyIntelBrief.SentimentBlock.empty(),
            List.of(),
            IngestStatus.OK
        );
        Path path = tempDir.resolve("brief.json");
        WeeklyIntelBriefIO.write(original, path);
        WeeklyIntelBrief loaded = WeeklyIntelBriefIO.read(path);
        assertEquals(original, loaded);
    }
}
