package com.martinfou.trading.backtest;

import com.martinfou.trading.core.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FuturesRolloverAccountingTest {

    static class HoldStrategy implements Strategy {
        private boolean entered = false;
        private final List<Order> pending = new ArrayList<>();

        @Override
        public String name() {
            return "HoldStrategy";
        }

        @Override
        public void onBar(Bar bar) {
            if (!entered) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 1.0, 0.0));
                entered = true;
            }
        }

        @Override
        public void onTick(double bid, double ask, long volume) {}

        @Override
        public List<Order> getPendingOrders() {
            List<Order> copy = new ArrayList<>(pending);
            pending.clear();
            return copy;
        }

        @Override
        public void reset() {
            entered = false;
            pending.clear();
        }
    }

    @Test
    void testRolloverDeductsCommissionsAndSlippage() {
        // March 2024 rollover is March 5, 2024
        Instant t0 = Instant.parse("2024-03-01T14:00:00Z"); // Pre-rollover
        Instant t1 = Instant.parse("2024-03-04T14:00:00Z"); // Pre-rollover
        Instant t2 = Instant.parse("2024-03-05T14:00:00Z"); // Rollover date (T-10)
        Instant t3 = Instant.parse("2024-03-06T14:00:00Z"); // Post-rollover

        List<Bar> bars = List.of(
            new Bar("MES", t0, 5000.0, 5020.0, 4990.0, 5010.0, 100),
            new Bar("MES", t1, 5010.0, 5030.0, 5000.0, 5020.0, 100),
            new Bar("MES", t2, 5020.0, 5040.0, 5010.0, 5030.0, 100),
            new Bar("MES", t3, 5030.0, 5050.0, 5020.0, 5040.0, 100)
        );

        // Commission = $0.62 per leg. Fixed slippage = $0.25 (1 tick)
        BacktestEngine engine = new BacktestEngine(new HoldStrategy(), bars, 10_000.0)
            .withRollover(true)
            .withCommissionFixed(0.62)
            .withSlippageFixed(0.25);

        BacktestResult result = engine.run();

        // 1 initial entry fill + 2 rollover legs (close old + open new) = 3 commission charges = 3 * $0.62 = $1.86
        assertEquals(1.86, result.totalCommission(), 1e-4);

        // 1 rollover closing trade + 1 final closing trade = 2 trades in result
        assertEquals(2, result.totalTrades());
        Trade rollTrade = result.trades().get(0);
        assertNotNull(rollTrade.rolloverGroupId(), "Rollover trade must have a rolloverGroupId");
        assertTrue(result.totalSlippage() > 0, "Slippage must be recorded for rollover trades");
    }
}
