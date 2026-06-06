package com.martinfou.trading.intelligence.time;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeekBoundsTest {

    @Test
    void nextWeekMonday_fromFriday() {
        // 2026-06-05 is Friday; target week Mon 2026-06-08
        assertEquals(LocalDate.of(2026, 6, 8), WeekBounds.nextWeekMonday(LocalDate.of(2026, 6, 5)));
    }

    @Test
    void nextWeekMonday_fromSaturday() {
        assertEquals(LocalDate.of(2026, 6, 8), WeekBounds.nextWeekMonday(LocalDate.of(2026, 6, 6)));
    }

    @Test
    void weekId_isoFormat() {
        assertEquals("2026-W24", WeekBounds.weekId(LocalDate.of(2026, 6, 8)));
    }

    @Test
    void parseWeekStart_roundTripsWeekId() {
        LocalDate monday = LocalDate.of(2026, 6, 8);
        assertEquals(monday, WeekBounds.parseWeekStart("2026-W24"));
        assertEquals("2026-W24", WeekBounds.weekId(monday));
    }
}
