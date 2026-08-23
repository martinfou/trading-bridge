package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FuturesMarginPdtExemptionTest {

    @Test
    void testFuturesPositionsAreExemptFromPdtRestriction() {
        MarginTracker tracker = new MarginTracker(0.05, true, 25_000.0);
        Instant now = Instant.parse("2024-03-20T14:00:00Z");

        // Record 5 day trades within the last 2 days on a $5,000 account
        tracker.recordDayTrade(now.minusSeconds(3600 * 24));
        tracker.recordDayTrade(now.minusSeconds(3600 * 20));
        tracker.recordDayTrade(now.minusSeconds(3600 * 10));
        tracker.recordDayTrade(now.minusSeconds(3600 * 5));
        tracker.recordDayTrade(now);

        // Open 1 MES contract (Futures)
        Position mesPos = new Position("MES", Order.Side.BUY, 1.0, 5000.0, now);
        MarginTracker.MarginMetrics metrics = tracker.evaluate(5000.0, List.of(mesPos), 5000.0, now);

        // Under $25k, but because it's futures, PDT restriction must NOT trigger
        assertFalse(metrics.pdtRestricted(), "Futures positions must be exempt from FINRA PDT rule");
        assertEquals(MarginTracker.MarginHealth.HEALTHY, metrics.health());
        assertEquals(1200.0, metrics.totalInitialMargin());
    }

    @Test
    void testEquityPositionsTriggerPdtUnder25k() {
        MarginTracker tracker = new MarginTracker(0.05, true, 25_000.0);
        Instant now = Instant.parse("2024-03-20T14:00:00Z");

        // Record 4 day trades
        tracker.recordDayTrade(now.minusSeconds(3600 * 24));
        tracker.recordDayTrade(now.minusSeconds(3600 * 20));
        tracker.recordDayTrade(now.minusSeconds(3600 * 10));
        tracker.recordDayTrade(now);

        // Open AAPL stock position (Equity) with $10,000 equity (< $25,000)
        Position stockPos = new Position("AAPL", Order.Side.BUY, 10.0, 180.0, now);
        MarginTracker.MarginMetrics metrics = tracker.evaluate(10_000.0, List.of(stockPos), 180.0, now);

        assertTrue(metrics.pdtRestricted(), "Equity positions under $25k with >3 day trades must be PDT restricted");
    }
}
