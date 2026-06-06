package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.LotSizing;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.FixedQuantityStrategy;
import com.martinfou.trading.strategies.StrategyCatalog;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunSizingIntegrationTest {

    @Test
    void catalogAppliesConfiguredQuantity() {
        Strategy strategy = StrategyCatalog.create(
            "LondonOpenRangeBreakout", "EUR_USD", LotSizing.lotsToUnits(0.05));
        assertInstanceOf(FixedQuantityStrategy.class, strategy);
        assertEquals(LotSizing.lotsToUnits(0.05), ((FixedQuantityStrategy) strategy).quantity(), 1e-9);
    }

    @Test
    void largerLotSizeScalesPnlLinearly() {
        List<Bar> bars = List.of(
            bar("2020-01-01T00:00:00Z", 1.1000, 1.1010, 1.0990, 1.1005),
            bar("2020-01-01T01:00:00Z", 1.1005, 1.1020, 1.1000, 1.1015));

        BacktestResult small = new BacktestEngine(new AlwaysBuyStrategy(), bars, 100_000.0).run();
        BacktestResult large = new BacktestEngine(
            new FixedQuantityStrategy(new AlwaysBuyStrategy(), LotSizing.lotsToUnits(0.02)),
            bars,
            100_000.0).run();

        assertTrue(small.totalTrades() > 0);
        assertEquals(small.totalPnl() * 2, large.totalPnl(), Math.abs(small.totalPnl()) * 0.01 + 1e-6);
    }

    private static Bar bar(String iso, double o, double h, double l, double c) {
        return new Bar("EUR_USD", Instant.parse(iso), o, h, l, c, 0);
    }

    private static final class AlwaysBuyStrategy implements Strategy {
        private final List<Order> pending = new ArrayList<>();
        private boolean entered;

        @Override
        public String name() {
            return "always-buy";
        }

        @Override
        public void onBar(Bar bar) {
            if (!entered) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 1000, 0));
                entered = true;
            }
        }

        @Override
        public void onTick(double bid, double ask, long volume) {}

        @Override
        public List<Order> getPendingOrders() {
            List<Order> copy = List.copyOf(pending);
            pending.clear();
            return copy;
        }

        @Override
        public void reset() {
            pending.clear();
            entered = false;
        }
    }
}
