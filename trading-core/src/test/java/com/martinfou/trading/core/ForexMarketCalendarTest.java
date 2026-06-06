package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForexMarketCalendarTest {

    @Test
    void forexSymbols_detected() {
        assertTrue(ForexMarketCalendar.isForexSymbol("EUR_USD"));
        assertFalse(ForexMarketCalendar.isForexSymbol("SPY"));
    }

    @Test
    void weekendUtc_saturdayAndSunday() {
        Instant sat = LocalDate.of(2024, 1, 13).atTime(12, 0).toInstant(ZoneOffset.UTC);
        Instant sun = LocalDate.of(2024, 1, 14).atTime(0, 0).toInstant(ZoneOffset.UTC);
        Instant mon = LocalDate.of(2024, 1, 15).atTime(8, 0).toInstant(ZoneOffset.UTC);
        assertTrue(ForexMarketCalendar.isWeekendUtc(sat));
        assertTrue(ForexMarketCalendar.isWeekendUtc(sun));
        assertFalse(ForexMarketCalendar.isWeekendUtc(mon));
    }

    @Test
    void tradingBar_skipsWeekendForForex() {
        Bar weekday = new Bar("EUR_USD", LocalDate.of(2024, 1, 12).atTime(12, 0).toInstant(ZoneOffset.UTC),
            1.1, 1.1, 1.1, 1.1, 1);
        Bar saturday = new Bar("EUR_USD", LocalDate.of(2024, 1, 13).atTime(12, 0).toInstant(ZoneOffset.UTC),
            1.1, 1.1, 1.1, 1.1, 1);
        assertTrue(ForexMarketCalendar.isTradingBar(weekday));
        assertFalse(ForexMarketCalendar.isTradingBar(saturday));
    }
}
