package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarginTrackerTest {

    @Test
    void testFuturesMarginEvaluationAndBuffer() {
        MarginTracker tracker = new MarginTracker(0.05, true, 25_000.0);
        Position mesPos = new Position("MES", Order.Side.BUY, 2.0, 5000.0, Instant.now());

        // 2 contracts MES:
        // Initial margin = 1200.0 * 2 = 2400.0
        // Maintenance margin = 1000.0 * 2 * 1.05 = 2100.0
        MarginTracker.MarginMetrics metrics = tracker.evaluate(
            10_000.0,
            List.of(mesPos),
            5000.0,
            Instant.now()
        );

        assertEquals(2400.0, metrics.totalInitialMargin(), 1e-6);
        assertEquals(2100.0, metrics.totalMaintenanceMargin(), 1e-6);
        assertEquals(7600.0, metrics.availableFunds(), 1e-6);
        assertEquals(MarginTracker.MarginHealth.HEALTHY, metrics.health());
    }

    @Test
    void testMarginCallTriggeredWhenEquityBelowMaintenance() {
        MarginTracker tracker = new MarginTracker(0.05, true, 25_000.0);
        Position mesPos = new Position("MES", Order.Side.BUY, 2.0, 5000.0, Instant.now());

        // Equity drops to $2000, which is below maintenance ($2100)
        MarginTracker.MarginMetrics metrics = tracker.evaluate(
            2000.0,
            List.of(mesPos),
            4900.0,
            Instant.now()
        );

        assertEquals(MarginTracker.MarginHealth.MARGIN_CALL, metrics.health());
        assertEquals(MarginTracker.MarginHealth.MARGIN_CALL, tracker.currentHealth());
    }

    @Test
    void testEquitiesRegTMarginAndPdtRule() {
        MarginTracker tracker = new MarginTracker(0.05, true, 25_000.0);
        Position stockPos = new Position("IWM", Order.Side.BUY, 100.0, 200.0, Instant.now());

        // 100 shares at $200 = $20,000 notional
        // Reg-T 50% initial margin = $10,000, 25% maintenance = $5,000
        MarginTracker.MarginMetrics metrics = tracker.evaluate(
            20_000.0,
            List.of(stockPos),
            200.0,
            Instant.now()
        );

        assertEquals(10_000.0, metrics.totalInitialMargin(), 1e-6);
        assertEquals(5000.0, metrics.totalMaintenanceMargin(), 1e-6);
        assertFalse(metrics.pdtRestricted());

        // Execute 3 day trades under $25k equity
        Instant now = Instant.now();
        tracker.recordDayTrade(now.minus(2, ChronoUnit.DAYS));
        tracker.recordDayTrade(now.minus(1, ChronoUnit.DAYS));
        tracker.recordDayTrade(now);

        MarginTracker.MarginMetrics restricted = tracker.evaluate(
            20_000.0,
            List.of(stockPos),
            200.0,
            now
        );

        assertTrue(restricted.pdtRestricted());
    }
}
