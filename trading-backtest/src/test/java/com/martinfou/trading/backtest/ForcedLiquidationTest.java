package com.martinfou.trading.backtest;

import com.martinfou.trading.core.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ForcedLiquidationTest {

    static class SimpleBuyerStrategy implements Strategy {
        private boolean bought = false;
        private final List<Order> pendingOrders = new ArrayList<>();

        @Override
        public String name() {
            return "SimpleBuyer";
        }

        @Override
        public void onBar(Bar bar) {
            if (!bought) {
                // Buy 5 contracts MES with $5000 initial capital
                // 5 contracts maint margin = 5 * 1000 * 1.05 = $5250
                pendingOrders.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 5.0, 0.0));
                bought = true;
            }
        }

        @Override
        public void onTick(double bid, double ask, long volume) {}

        @Override
        public List<Order> getPendingOrders() {
            List<Order> copy = new ArrayList<>(pendingOrders);
            pendingOrders.clear();
            return copy;
        }

        @Override
        public void reset() {
            bought = false;
            pendingOrders.clear();
        }
    }

    @Test
    void testForcedLiquidationTriggersWhenEquityDropsBelowMaintenance() {
        Instant t0 = Instant.parse("2024-01-02T10:00:00Z");
        List<Bar> bars = new ArrayList<>();

        // Bar 0: Enter MES at 5000.0 (5 contracts). Maint margin = $5250
        bars.add(new Bar("MES", t0, 5000.0, 5000.0, 5000.0, 5000.0, 100));

        // Bar 1: Price drops to 4950 (-50 pts * $5 * 5 contracts = -$1250 loss). Equity = $5000 - $1250 = $3750 < $5250 maint.
        // Triggers MARGIN_CALL at bar close.
        bars.add(new Bar("MES", t0.plus(1, ChronoUnit.HOURS), 4980.0, 4980.0, 4950.0, 4950.0, 100));

        // Bar 2: Opens at 4940.0. Forced liquidation executes at Open (4940.0)!
        bars.add(new Bar("MES", t0.plus(2, ChronoUnit.HOURS), 4940.0, 4960.0, 4930.0, 4950.0, 100));

        // Bar 3: Subsequent bar
        bars.add(new Bar("MES", t0.plus(3, ChronoUnit.HOURS), 4950.0, 4970.0, 4940.0, 4960.0, 100));

        MarginTracker tracker = new MarginTracker(0.05, true, 25_000.0);
        BacktestEngine engine = new BacktestEngine(new SimpleBuyerStrategy(), bars, 5000.0)
            .withMarginTracker(tracker);

        BacktestResult result = engine.run();

        assertEquals(MarginTracker.MarginHealth.LIQUIDATED, tracker.currentHealth());
        assertEquals(1, result.totalTrades());
        Trade trade = result.trades().get(0);
        assertEquals(4980.0, trade.entryPrice(), 1e-6); // Filled at Bar 1 Open
        assertEquals(4940.0, trade.exitPrice(), 1e-6);  // Liquidated at Bar 2 Open
        assertTrue(trade.pnl() < 0);
    }
}
