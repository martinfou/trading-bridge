package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;

import java.util.ArrayList;
import java.util.List;

/** Minimal strategies for backtest module unit tests (no catalog dependency). */
public final class TestStrategies {

    private TestStrategies() {}

    public static Strategy smaCrossover(int fast, int slow) {
        return new SmaCrossover(fast, slow);
    }

    public static Strategy noOp() {
        return new NoOpStrategy();
    }

    public static Strategy noOp(String name) {
        return new NoOpStrategy(name);
    }

    private static final class NoOpStrategy implements Strategy {
        private final String name;

        NoOpStrategy() {
            this("NoOp");
        }

        NoOpStrategy(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void onBar(Bar bar) {}

        @Override
        public void onTick(double bid, double ask, long volume) {}

        @Override
        public List<Order> getPendingOrders() {
            return List.of();
        }

        @Override
        public void reset() {}
    }

    static final class SmaCrossover implements Strategy {
        private final int fastPeriod;
        private final int slowPeriod;
        private final List<Order> pending = new ArrayList<>();
        private final List<Double> closes = new ArrayList<>();

        SmaCrossover(int fast, int slow) {
            this.fastPeriod = fast;
            this.slowPeriod = slow;
        }

        @Override
        public String name() {
            return "SMA Crossover";
        }

        @Override
        public void onTick(double bid, double ask, long volume) {}

        @Override
        public void onBar(Bar bar) {
            closes.add(bar.close());
            if (closes.size() < slowPeriod) {
                return;
            }

            double fastSma = sma(fastPeriod);
            double slowSma = sma(slowPeriod);

            pending.clear();
            if (fastSma > slowSma) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 0.01, 0));
            } else {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.MARKET, 0.01, 0));
            }
        }

        private double sma(int period) {
            double sum = 0;
            for (int i = closes.size() - period; i < closes.size(); i++) {
                sum += closes.get(i);
            }
            return sum / period;
        }

        @Override
        public List<Order> getPendingOrders() {
            return List.copyOf(pending);
        }

        @Override
        public void reset() {
            closes.clear();
            pending.clear();
        }
    }
}
