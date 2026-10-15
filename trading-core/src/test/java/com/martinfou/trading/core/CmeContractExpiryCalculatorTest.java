package com.martinfou.trading.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Month;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CmeContractExpiryCalculator Unit Tests (Story 46.4)")
class CmeContractExpiryCalculatorTest {

    @Test
    @DisplayName("Should correctly calculate third Friday of March, June, Sept, Dec 2026")
    void testThirdFridayCalculations() {
        assertEquals(LocalDate.of(2026, Month.MARCH, 20), CmeContractExpiryCalculator.getThirdFriday(2026, Month.MARCH));
        assertEquals(LocalDate.of(2026, Month.JUNE, 19), CmeContractExpiryCalculator.getThirdFriday(2026, Month.JUNE));
        assertEquals(LocalDate.of(2026, Month.SEPTEMBER, 18), CmeContractExpiryCalculator.getThirdFriday(2026, Month.SEPTEMBER));
        assertEquals(LocalDate.of(2026, Month.DECEMBER, 18), CmeContractExpiryCalculator.getThirdFriday(2026, Month.DECEMBER));
    }

    @Test
    @DisplayName("Should detect roll window 8 days before third Friday")
    void testRollWindowDetection() {
        // March 20, 2026 expiration -> roll window starts March 12
        assertFalse(CmeContractExpiryCalculator.isRollWindow(LocalDate.of(2026, Month.MARCH, 10)));
        assertTrue(CmeContractExpiryCalculator.isRollWindow(LocalDate.of(2026, Month.MARCH, 13)));
        assertTrue(CmeContractExpiryCalculator.isRollWindow(LocalDate.of(2026, Month.MARCH, 20)));
        assertFalse(CmeContractExpiryCalculator.isRollWindow(LocalDate.of(2026, Month.MARCH, 21)));
    }

    @Test
    @DisplayName("Should return correct quarterly month codes")
    void testQuarterlyMonthCodes() {
        assertEquals("H", CmeContractExpiryCalculator.getMonthCode(Month.MARCH));
        assertEquals("M", CmeContractExpiryCalculator.getMonthCode(Month.JUNE));
        assertEquals("U", CmeContractExpiryCalculator.getMonthCode(Month.SEPTEMBER));
        assertEquals("Z", CmeContractExpiryCalculator.getMonthCode(Month.DECEMBER));
    }
}
