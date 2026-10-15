package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class CmeFuturesCalendarTest {

    @Test
    void testQuarterlyExpiryAndRolloverDates() {
        // March 2024: 3rd Friday is March 15, 2024. Rollover (T-10) is March 5, 2024.
        LocalDate marExpiry = CmeFuturesCalendar.expiryDate(2024, 3);
        assertEquals(LocalDate.of(2024, 3, 15), marExpiry);
        LocalDate marRollover = CmeFuturesCalendar.rolloverDate(2024, 3);
        assertEquals(LocalDate.of(2024, 3, 5), marRollover);

        // June 2024: 3rd Friday is June 21, 2024. Rollover (T-10) is June 11, 2024.
        LocalDate junExpiry = CmeFuturesCalendar.expiryDate(2024, 6);
        assertEquals(LocalDate.of(2024, 6, 21), junExpiry);
        LocalDate junRollover = CmeFuturesCalendar.rolloverDate(2024, 6);
        assertEquals(LocalDate.of(2024, 6, 11), junRollover);
    }

    @Test
    void testActiveContractResolution() {
        // February 20, 2024 -> before March rollover (March 5) -> MESH24
        var specFeb = CmeFuturesCalendar.activeContract("MES", LocalDate.of(2024, 2, 20));
        assertEquals("MESH24", specFeb.contractCode());
        assertEquals(CmeFuturesCalendar.QuarterMonth.MARCH, specFeb.quarterMonth());

        // March 6, 2024 -> after March rollover (March 5) -> rolls into MESM24
        var specMarPost = CmeFuturesCalendar.activeContract("MES", LocalDate.of(2024, 3, 6));
        assertEquals("MESM24", specMarPost.contractCode());
        assertEquals(CmeFuturesCalendar.QuarterMonth.JUNE, specMarPost.quarterMonth());

        // December 15, 2024 -> after Dec rollover (Dec 10) -> rolls into MESH25
        var specDecPost = CmeFuturesCalendar.activeContract("MES", LocalDate.of(2024, 12, 15));
        assertEquals("MESH25", specDecPost.contractCode());
        assertEquals(2025, specDecPost.year());
    }

    @Test
    void testNextContract() {
        var specH = CmeFuturesCalendar.contractSpec("MES", 2024, CmeFuturesCalendar.QuarterMonth.MARCH);
        var specM = CmeFuturesCalendar.nextContract(specH);
        assertEquals("MESM24", specM.contractCode());

        var specZ = CmeFuturesCalendar.contractSpec("MES", 2024, CmeFuturesCalendar.QuarterMonth.DECEMBER);
        var specNextYearH = CmeFuturesCalendar.nextContract(specZ);
        assertEquals("MESH25", specNextYearH.contractCode());
    }
}
