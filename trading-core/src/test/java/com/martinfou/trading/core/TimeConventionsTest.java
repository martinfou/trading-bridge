package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class TimeConventionsTest {

    @Test
    void parseOandaTimestamp_withZ() {
        Instant t = TimeConventions.parseOandaTimestamp("2026-05-20T14:30:00.000000000Z");
        assertEquals(Instant.parse("2026-05-20T14:30:00Z"), t);
    }

    @Test
    void parseOandaTimestamp_withoutZ_treatedAsUtc() {
        Instant t = TimeConventions.parseOandaTimestamp("2026-05-20T14:30:00");
        assertEquals(Instant.parse("2026-05-20T14:30:00Z"), t);
    }

    @Test
    void parseOandaTimestamp_fractionalWithOffset() {
        Instant t = TimeConventions.parseOandaTimestamp("2026-05-20T14:30:00.123-04:00");
        assertEquals(Instant.parse("2026-05-20T18:30:00.123Z"), t);
    }

    @Test
    void parseCsvTimestamp_withZ() {
        Instant t = TimeConventions.parseCsvTimestamp("2024-01-01T00:00:00Z");
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), t);
    }

    @Test
    void toDisplayString_null_returnsPlaceholder() {
        assertEquals("n/a", TimeConventions.toDisplayString(null));
    }

    @Test
    void eventAt_convertsReleaseZoneToUtc() {
        // RBA 2026-05-19 05:30 Sydney → 2026-05-18T19:30:00Z
        Instant t = TimeConventions.eventAt(2026, 5, 19, 5, 30, ZoneId.of("Australia/Sydney"));
        assertEquals(Instant.parse("2026-05-18T19:30:00Z"), t);
    }

    @Test
    void csvLocalAsUtc() {
        Instant t = TimeConventions.csvLocalAsUtc("2024-01-01T00:00:00");
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), t);
    }

    @Test
    void toDisplayString_usesTorontoZone() {
        String s = TimeConventions.toDisplayString(Instant.parse("2026-05-20T14:00:00Z"));
        assertTrue(s.contains("EDT") || s.contains("EST"));
    }
}
