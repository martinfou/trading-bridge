package com.martinfou.trading.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Trade;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComparisonEngineTest {

    @Test
    void testIdenticalDistributions() {
        List<Trade> bt = new ArrayList<>();
        List<Trade> act = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double pnl = i % 2 == 0 ? 10.0 : -5.0;
            bt.add(new Trade("EUR_USD", Order.Side.BUY, 1.1000, 1.1010, 1000.0, Instant.now(), Instant.now(), 1.0, 0.0, 0.0) {
                @Override public double pnl() { return pnl; }
            });
            act.add(new Trade("EUR_USD", Order.Side.BUY, 1.1000, 1.1010, 1000.0, Instant.now(), Instant.now(), 1.0, 0.0, 0.0) {
                @Override public double pnl() { return pnl; }
            });
        }

        ComparisonEngine.ComparisonResult result = ComparisonEngine.compare(bt, act, 10.0, 5.0, 0.5, 0.0001);
        assertNotNull(result);
        assertEquals(1.0, result.pearsonCorrelation(), 1e-6);
        assertEquals(0.0, result.ksStatistic(), 1e-6);
        assertFalse(result.ksSignificant05());
        assertFalse(result.ksSignificant01());
        assertFalse(result.costDriftExceeded());
    }

    @Test
    void testKSBreachAndCostDrift() {
        List<Trade> bt = new ArrayList<>();
        List<Trade> act = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            bt.add(new Trade("EUR_USD", Order.Side.BUY, 1.1000, 1.1010, 1000.0, Instant.now(), Instant.now(), 1.0, 0.0, 0.0) {
                @Override public double pnl() { return 10.0; }
            });
            act.add(new Trade("EUR_USD", Order.Side.BUY, 1.1000, 1.1010, 1000.0, Instant.now(), Instant.now(), 1.0, 0.0, 0.0) {
                @Override public double pnl() { return -500.0; } // very different distribution
            });
        }

        // BT comm per trade = 10.0 / 20 = 0.5
        // Actual comm per trade = 5.0 (exceeds 2x)
        ComparisonEngine.ComparisonResult result = ComparisonEngine.compare(bt, act, 10.0, 5.0, 5.0, 0.0001);
        assertNotNull(result);
        assertTrue(result.ksSignificant05());
        assertTrue(result.ksSignificant01());
        assertTrue(result.costDriftExceeded());
    }
}
