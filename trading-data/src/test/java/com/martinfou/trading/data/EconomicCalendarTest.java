package com.martinfou.trading.data;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class EconomicCalendarTest {

    @Test
    void rbaEvent_isUtcAlignedWithSydneyRelease() {
        EconomicCalendar.Event rba = EconomicCalendar.THIS_WEEK.stream()
            .filter(e -> e.event().contains("RBA"))
            .findFirst()
            .orElseThrow();
        assertEquals(Instant.parse("2026-05-18T19:30:00Z"), rba.time());
    }

    @Test
    void highImpactFilter_excludesMedium() {
        long expected = EconomicCalendar.THIS_WEEK.stream()
            .filter(e -> "HIGH".equals(e.impact()))
            .count();
        var high = EconomicCalendar.getHighImpactEvents();
        assertEquals(expected, high.size());
        assertTrue(high.stream().noneMatch(e -> "MEDIUM".equals(e.impact())));
    }

    @Test
    void ukCpi_isLondonTenAmUtc() {
        EconomicCalendar.Event ukCpi = EconomicCalendar.THIS_WEEK.stream()
            .filter(e -> e.event().equals("UK CPI"))
            .findFirst()
            .orElseThrow();
        assertEquals(Instant.parse("2026-05-20T09:00:00Z"), ukCpi.time());
    }

    @Test
    void canadaCpi_isTorontoHalfPastNoonUtc() {
        EconomicCalendar.Event cpi = EconomicCalendar.THIS_WEEK.stream()
            .filter(e -> e.event().contains("Canada CPI"))
            .findFirst()
            .orElseThrow();
        assertEquals(Instant.parse("2026-05-19T16:30:00Z"), cpi.time());
    }

    @Test
    void fomc_isNewYorkSixPmUtc() {
        EconomicCalendar.Event fomc = EconomicCalendar.THIS_WEEK.stream()
            .filter(e -> e.event().contains("FOMC"))
            .findFirst()
            .orElseThrow();
        assertEquals(Instant.parse("2026-05-20T22:00:00Z"), fomc.time());
    }
}
