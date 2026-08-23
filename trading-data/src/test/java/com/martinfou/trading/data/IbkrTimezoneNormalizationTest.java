package com.martinfou.trading.data;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class IbkrTimezoneNormalizationTest {

    @Test
    void testEdtTimestampParsing() {
        // March 15, 2024 is Daylight Saving Time (EDT, UTC-4)
        // 09:30:00 AM EDT corresponds to 13:30:00 UTC
        Instant instant = IbkrHistoricalDataLoader.parseIbkrTime("20240315 09:30:00");
        assertEquals(Instant.parse("2024-03-15T13:30:00Z"), instant);

        // Compact format
        Instant instantCompact = IbkrHistoricalDataLoader.parseIbkrTime("20240315  09:30:00");
        assertEquals(Instant.parse("2024-03-15T13:30:00Z"), instantCompact);
    }

    @Test
    void testEstTimestampParsing() {
        // January 15, 2024 is Standard Time (EST, UTC-5)
        // 09:30:00 AM EST corresponds to 14:30:00 UTC
        Instant instant = IbkrHistoricalDataLoader.parseIbkrTime("20240115 09:30:00");
        assertEquals(Instant.parse("2024-01-15T14:30:00Z"), instant);
    }

    @Test
    void testExplicitZoneOverride() {
        // When explicitly passing UTC zone
        Instant instantUtc = IbkrHistoricalDataLoader.parseIbkrTime("20240315 09:30:00", ZoneOffset.UTC);
        assertEquals(Instant.parse("2024-03-15T09:30:00Z"), instantUtc);
    }
}
