package com.martinfou.trading.backtest;

import com.martinfou.trading.core.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConservativeFillSimulationTest {

    static class LimitOrderStrategy implements Strategy {
        private final Order.Side side;
        private final double limitPrice;
        private final List<Order> pending = new ArrayList<>();

        public LimitOrderStrategy(Order.Side side, double limitPrice) {
            this.side = side;
            this.limitPrice = limitPrice;
        }

        @Override
        public String name() {
            return "LimitOrderStrategy";
        }

        @Override
        public void onBar(Bar bar) {
            pending.add(new Order(bar.symbol(), side, Order.Type.LIMIT, 1.0, limitPrice));
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
            pending.clear();
        }
    }

    @Test
    void testTouchVsTradeThroughBuyLimit() {
        Instant t0 = Instant.parse("2024-03-18T14:00:00Z"); // Monday RTH
        Instant t1 = t0.plus(1, ChronoUnit.HOURS);
        Instant t2 = t0.plus(2, ChronoUnit.HOURS);

        // Bar 0: Trigger limit order placement at 5000.00 (processed on Bar 1)
        // Bar 1: Low touches exactly 5000.00 (Low = 5000.00)
        // Bar 2: Low trades through to 4999.75 (Low = 4999.75)
        List<Bar> bars = List.of(
            new Bar("MES", t0, 5010.0, 5020.0, 5005.0, 5015.0, 100),
            new Bar("MES", t1, 5015.0, 5018.0, 5000.0, 5008.0, 100),
            new Bar("MES", t2, 5008.0, 5012.0, 4999.75, 5010.0, 100)
        );

        // 1. In TOUCH mode, Bar 1 fills the order immediately on touch
        BacktestEngine touchEngine = new BacktestEngine(new LimitOrderStrategy(Order.Side.BUY, 5000.00), bars, 10_000.0)
            .withFillMode(FillMode.TOUCH);
        BacktestResult touchResult = touchEngine.run();
        assertEquals(1, touchResult.totalTrades());
        assertEquals(5000.00, touchResult.trades().get(0).entryPrice(), 1e-6);

        // 2. In TRADE_THROUGH mode, Bar 1 is skipped (touch only), fills on Bar 2 (trade through)
        BacktestEngine tradeThroughEngine = new BacktestEngine(new LimitOrderStrategy(Order.Side.BUY, 5000.00), bars, 10_000.0)
            .withFillMode(FillMode.TRADE_THROUGH);
        BacktestResult tradeThroughResult = tradeThroughEngine.run();
        assertEquals(1, tradeThroughResult.totalTrades());
        assertEquals(5000.00, tradeThroughResult.trades().get(0).entryPrice(), 1e-6);
        assertEquals(t2, tradeThroughResult.trades().get(0).entryTime());
    }

    @Test
    void testTradeThroughRejectsWhenNoThroughTradeOccurs() {
        Instant t0 = Instant.parse("2024-03-18T14:00:00Z");
        Instant t1 = t0.plus(1, ChronoUnit.HOURS);

        // Bar 1 low touches exactly 5000.00, never trades below
        List<Bar> bars = List.of(
            new Bar("MES", t0, 5010.0, 5020.0, 5005.0, 5015.0, 100),
            new Bar("MES", t1, 5015.0, 5018.0, 5000.0, 5008.0, 100)
        );

        BacktestEngine engine = new BacktestEngine(new LimitOrderStrategy(Order.Side.BUY, 5000.00), bars, 10_000.0)
            .withFillMode(FillMode.TRADE_THROUGH);
        BacktestResult result = engine.run();

        // Under trade-through, order never filled -> 0 trades executed
        assertEquals(0, result.totalTrades());
    }
}
