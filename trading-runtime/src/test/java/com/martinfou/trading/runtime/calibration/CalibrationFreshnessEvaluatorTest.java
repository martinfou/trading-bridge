package com.martinfou.trading.runtime.calibration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class CalibrationFreshnessEvaluatorTest {

    @Test
    void testUncalibratedStrategyReturnsExpired() {
        Instant now = Instant.now();
        var result = CalibrationFreshnessEvaluator.evaluate(
            "LtCrossMomentum",
            null,
            0,
            0,
            now
        );

        assertEquals(CalibrationFreshnessEvaluator.Status.EXPIRED, result.status());
        assertTrue(result.message().contains("never been calibrated"));
    }

    @Test
    void testRecentlyCalibratedStrategyReturnsFresh() {
        Instant now = Instant.now();
        Instant lastCal = now.minus(5, ChronoUnit.DAYS);

        var result = CalibrationFreshnessEvaluator.evaluate(
            "LtCrossMomentum",
            lastCal,
            100,
            10,
            now
        );

        assertEquals(CalibrationFreshnessEvaluator.Status.FRESH, result.status());
        assertEquals(5, result.ageDays());
        assertEquals(30, result.maxAgeDays());
    }

    @Test
    void testNearingExpiryReturnsWarning() {
        Instant now = Instant.now();
        Instant lastCal = now.minus(25, ChronoUnit.DAYS); // 25 days out of 30 days (>= 80%)

        var result = CalibrationFreshnessEvaluator.evaluate(
            "LtCrossMomentum",
            lastCal,
            100,
            10,
            now
        );

        assertEquals(CalibrationFreshnessEvaluator.Status.WARNING, result.status());
        assertTrue(result.message().contains("approaching expiry"));
    }

    @Test
    void testExceededAgeLimitReturnsExpired() {
        Instant now = Instant.now();
        Instant lastCal = now.minus(35, ChronoUnit.DAYS); // 35 days > 30 maxAgeDays

        var result = CalibrationFreshnessEvaluator.evaluate(
            "LtCrossMomentum",
            lastCal,
            100,
            10,
            now
        );

        assertEquals(CalibrationFreshnessEvaluator.Status.EXPIRED, result.status());
        assertTrue(result.message().contains("Age limit exceeded"));
    }

    @Test
    void testExceededTradeLimitReturnsExpired() {
        Instant now = Instant.now();
        Instant lastCal = now.minus(5, ChronoUnit.DAYS);

        var result = CalibrationFreshnessEvaluator.evaluate(
            "LtCrossMomentum",
            lastCal,
            100,
            150, // 150 trades > 100 maxTradesCount
            now
        );

        assertEquals(CalibrationFreshnessEvaluator.Status.EXPIRED, result.status());
        assertTrue(result.message().contains("Trade limit exceeded"));
    }
}
