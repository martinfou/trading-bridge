package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CmeGlobexMarketCalendarTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Test
    void testSundayOpenAndSaturdayClose() {
        // Saturday 12:00 PM ET -> Closed
        ZonedDateTime satNoon = ZonedDateTime.of(2024, 3, 16, 12, 0, 0, 0, NY);
        assertFalse(CmeGlobexMarketCalendar.isTradingTime(satNoon.toInstant()));

        // Sunday 2:00 PM ET -> Closed
        ZonedDateTime sunAfternoon = ZonedDateTime.of(2024, 3, 17, 14, 0, 0, 0, NY);
        assertFalse(CmeGlobexMarketCalendar.isTradingTime(sunAfternoon.toInstant()));

        // Sunday 6:00 PM ET (18:00) -> Open (Globex open)
        ZonedDateTime sunOpen = ZonedDateTime.of(2024, 3, 17, 18, 0, 0, 0, NY);
        assertTrue(CmeGlobexMarketCalendar.isTradingTime(sunOpen.toInstant()));
        assertEquals(CmeGlobexMarketCalendar.SessionType.ETH, CmeGlobexMarketCalendar.classifySession(sunOpen.toInstant()));
    }

    @Test
    void testRthClassification() {
        // Monday 10:00 AM ET -> RTH
        ZonedDateTime monRth = ZonedDateTime.of(2024, 3, 18, 10, 0, 0, 0, NY);
        assertTrue(CmeGlobexMarketCalendar.isTradingTime(monRth.toInstant()));
        assertTrue(CmeGlobexMarketCalendar.isRth(monRth.toInstant()));
        assertFalse(CmeGlobexMarketCalendar.isEth(monRth.toInstant()));
        assertEquals(CmeGlobexMarketCalendar.SessionType.RTH, CmeGlobexMarketCalendar.classifySession(monRth.toInstant()));

        // Monday 3:59 PM ET -> RTH
        ZonedDateTime monLateRth = ZonedDateTime.of(2024, 3, 18, 15, 59, 0, 0, NY);
        assertTrue(CmeGlobexMarketCalendar.isRth(monLateRth.toInstant()));

        // Monday 4:05 PM ET -> ETH
        ZonedDateTime monPostClose = ZonedDateTime.of(2024, 3, 18, 16, 5, 0, 0, NY);
        assertTrue(CmeGlobexMarketCalendar.isEth(monPostClose.toInstant()));
    }

    @Test
    void testDailyMaintenanceHalt() {
        // Tuesday 5:30 PM ET (17:30) -> Daily Maintenance Halt
        ZonedDateTime tueHalt = ZonedDateTime.of(2024, 3, 19, 17, 30, 0, 0, NY);
        assertFalse(CmeGlobexMarketCalendar.isTradingTime(tueHalt.toInstant()));
        assertEquals(CmeGlobexMarketCalendar.SessionType.CLOSED, CmeGlobexMarketCalendar.classifySession(tueHalt.toInstant()));

        // Tuesday 6:00 PM ET (18:00) -> Reopened
        ZonedDateTime tueReopen = ZonedDateTime.of(2024, 3, 19, 18, 0, 0, 0, NY);
        assertTrue(CmeGlobexMarketCalendar.isTradingTime(tueReopen.toInstant()));
    }

    @Test
    void testFridayClose() {
        // Friday 4:55 PM ET -> Open
        ZonedDateTime friOpen = ZonedDateTime.of(2024, 3, 22, 16, 55, 0, 0, NY);
        assertTrue(CmeGlobexMarketCalendar.isTradingTime(friOpen.toInstant()));

        // Friday 5:00 PM ET -> Weekly Close
        ZonedDateTime friClose = ZonedDateTime.of(2024, 3, 22, 17, 0, 0, 0, NY);
        assertFalse(CmeGlobexMarketCalendar.isTradingTime(friClose.toInstant()));
    }
}
