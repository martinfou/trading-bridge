package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic test strategies for backtest/paper platform tests.
 * No catalog dependency; no random data.
 */
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

    /** Single BUY MARKET on first bar; position closed at last bar close. */
    public static Strategy buyOnce() {
        return new BuyOnce();
    }

    /** BUY bar 0, SELL bar 1 — explicit round-trip (close without reversal). */
    public static Strategy buyThenSell() {
        return new BuyThenSell();
    }

    /** BUY with stop-loss attached on first bar. */
    public static Strategy buyWithStopLoss(double stopLoss) {
        return new BuyWithStopLoss(stopLoss);
    }

    /** BUY with take-profit attached on first bar. */
    public static Strategy buyWithTakeProfit(double takeProfit) {
        return new BuyWithTakeProfit(takeProfit);
    }

    /** Alternating BUY/SELL each bar — {@code pairs} round-trips. */
    public static Strategy alternatingRoundTrips(int pairs) {
        return new AlternatingRoundTrips(pairs);
    }

    /** LIMIT BUY at {@code limitPrice}; re-emitted up to {@code maxBars} bars. */
    public static Strategy limitBuy(double limitPrice, double quantity, int maxBars) {
        return new LimitBuy(limitPrice, quantity, maxBars);
    }

    public static Strategy limitBuy(double limitPrice, double quantity) {
        return limitBuy(limitPrice, quantity, 1);
    }

    /** STOP BUY at {@code stopPrice}; re-emitted up to {@code maxBars} bars. */
    public static Strategy stopBuy(double stopPrice, double quantity, int maxBars) {
        return new StopBuy(stopPrice, quantity, maxBars);
    }

    public static Strategy stopBuy(double stopPrice, double quantity) {
        return stopBuy(stopPrice, quantity, 1);
    }

    /** Two BUY MARKET orders on consecutive bars (same-side add). */
    public static Strategy buyTwiceSameSide() {
        return new BuyTwiceSameSide();
    }

    /** Emits order only on the given bar index. */
    public static Strategy delayedMarketBuy(int barIndex, double quantity) {
        return new DelayedMarketBuy(barIndex, quantity);
    }

    /** Single SELL MARKET on first bar; covered at last bar close. */
    public static Strategy sellOnce() {
        return new SellOnce();
    }

    /** SELL bar 0, BUY bar 1 — short round-trip (cover without reversal). */
    public static Strategy sellThenBuy() {
        return new SellThenBuy();
    }

    /** LIMIT SELL at {@code limitPrice}; re-emitted up to {@code maxBars} bars. */
    public static Strategy limitSell(double limitPrice, double quantity, int maxBars) {
        return new LimitSell(limitPrice, quantity, maxBars);
    }

    public static Strategy limitSell(double limitPrice, double quantity) {
        return limitSell(limitPrice, quantity, 1);
    }

    /** STOP SELL at {@code stopPrice}; re-emitted up to {@code maxBars} bars. */
    public static Strategy stopSell(double stopPrice, double quantity, int maxBars) {
        return new StopSell(stopPrice, quantity, maxBars);
    }

    public static Strategy stopSell(double stopPrice, double quantity) {
        return stopSell(stopPrice, quantity, 1);
    }

    /** Two BUY MARKET orders on the same bar (same-side add). */
    public static Strategy doubleMarketBuySameBar() {
        return new DoubleMarketBuySameBar();
    }

    /** BUY with both stop-loss and take-profit (SL priority when both touched). */
    public static Strategy buyWithStopLossAndTakeProfit(double stopLoss, double takeProfit) {
        return new BuyWithStopLossAndTakeProfit(stopLoss, takeProfit);
    }

    /** SELL with stop-loss above entry (short protection). */
    public static Strategy sellWithStopLoss(double stopLoss) {
        return new SellWithStopLoss(stopLoss);
    }

    /** Abstract base for scripted order-queue strategies. */
    abstract static class ScriptedStrategy implements Strategy {
        protected final List<Order> pending = new ArrayList<>();

        @Override
        public void onTick(double bid, double ask, long volume) {}

        @Override
        public List<Order> getPendingOrders() {
            return List.copyOf(pending);
        }

        @Override
        public void reset() {
            pending.clear();
            onReset();
        }

        protected void onReset() {}

        protected void clearAndEmit(Order order) {
            pending.clear();
            pending.add(order);
        }
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

    static final class BuyOnce extends ScriptedStrategy {
        private boolean sent;

        @Override
        public String name() {
            return "BuyOnce";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (!sent) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 10_000, 0));
                sent = true;
            }
        }

        @Override
        protected void onReset() {
            sent = false;
        }
    }

    static final class BuyThenSell extends ScriptedStrategy {
        private int barIndex;

        @Override
        public String name() {
            return "BuyThenSell";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (barIndex == 0) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 10_000, 0));
            } else if (barIndex == 1) {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.MARKET, 10_000, 0));
            }
            barIndex++;
        }

        @Override
        protected void onReset() {
            barIndex = 0;
        }
    }

    static final class BuyWithStopLoss extends ScriptedStrategy {
        private final double stopLoss;
        private boolean sent;

        BuyWithStopLoss(double stopLoss) {
            this.stopLoss = stopLoss;
        }

        @Override
        public String name() {
            return "BuyWithSL";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (!sent) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 10_000, 0)
                    .withStopLoss(stopLoss));
                sent = true;
            }
        }

        @Override
        protected void onReset() {
            sent = false;
        }
    }

    static final class BuyWithTakeProfit extends ScriptedStrategy {
        private final double takeProfit;
        private boolean sent;

        BuyWithTakeProfit(double takeProfit) {
            this.takeProfit = takeProfit;
        }

        @Override
        public String name() {
            return "BuyWithTP";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (!sent) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 10_000, 0)
                    .withTakeProfit(takeProfit));
                sent = true;
            }
        }

        @Override
        protected void onReset() {
            sent = false;
        }
    }

    static final class AlternatingRoundTrips extends ScriptedStrategy {
        private final int pairs;
        private int barIndex;

        AlternatingRoundTrips(int pairs) {
            this.pairs = pairs;
        }

        @Override
        public String name() {
            return "AlternatingRoundTrips";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            int pairIndex = barIndex / 2;
            if (pairIndex >= pairs) {
                barIndex++;
                return;
            }
            if (barIndex % 2 == 0) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 10_000, 0));
            } else {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.MARKET, 10_000, 0));
            }
            barIndex++;
        }

        @Override
        protected void onReset() {
            barIndex = 0;
        }
    }

    static final class LimitBuy extends ScriptedStrategy {
        private final double limitPrice;
        private final double quantity;
        private final int maxBars;
        private int barIndex;

        LimitBuy(double limitPrice, double quantity, int maxBars) {
            this.limitPrice = limitPrice;
            this.quantity = quantity;
            this.maxBars = maxBars;
        }

        @Override
        public String name() {
            return "LimitBuy";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (barIndex < maxBars) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.LIMIT, quantity, limitPrice));
            }
            barIndex++;
        }

        @Override
        protected void onReset() {
            barIndex = 0;
        }
    }

    static final class StopBuy extends ScriptedStrategy {
        private final double stopPrice;
        private final double quantity;
        private final int maxBars;
        private int barIndex;

        StopBuy(double stopPrice, double quantity, int maxBars) {
            this.stopPrice = stopPrice;
            this.quantity = quantity;
            this.maxBars = maxBars;
        }

        @Override
        public String name() {
            return "StopBuy";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (barIndex < maxBars) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.STOP, quantity, stopPrice));
            }
            barIndex++;
        }

        @Override
        protected void onReset() {
            barIndex = 0;
        }
    }

    static final class BuyTwiceSameSide extends ScriptedStrategy {
        private int barIndex;

        @Override
        public String name() {
            return "BuyTwiceSameSide";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (barIndex == 0 || barIndex == 1) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 5_000, 0));
            }
            barIndex++;
        }

        @Override
        protected void onReset() {
            barIndex = 0;
        }
    }

    static final class DelayedMarketBuy extends ScriptedStrategy {
        private final int targetBar;
        private final double quantity;
        private int barIndex;

        DelayedMarketBuy(int targetBar, double quantity) {
            this.targetBar = targetBar;
            this.quantity = quantity;
        }

        @Override
        public String name() {
            return "DelayedMarketBuy";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (barIndex == targetBar) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, quantity, 0));
            }
            barIndex++;
        }

        @Override
        protected void onReset() {
            barIndex = 0;
        }
    }

    static final class SellOnce extends ScriptedStrategy {
        private boolean sent;

        @Override
        public String name() {
            return "SellOnce";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (!sent) {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.MARKET, 10_000, 0));
                sent = true;
            }
        }

        @Override
        protected void onReset() {
            sent = false;
        }
    }

    static final class SellThenBuy extends ScriptedStrategy {
        private int barIndex;

        @Override
        public String name() {
            return "SellThenBuy";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (barIndex == 0) {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.MARKET, 10_000, 0));
            } else if (barIndex == 1) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 10_000, 0));
            }
            barIndex++;
        }

        @Override
        protected void onReset() {
            barIndex = 0;
        }
    }

    static final class LimitSell extends ScriptedStrategy {
        private final double limitPrice;
        private final double quantity;
        private final int maxBars;
        private int barIndex;

        LimitSell(double limitPrice, double quantity, int maxBars) {
            this.limitPrice = limitPrice;
            this.quantity = quantity;
            this.maxBars = maxBars;
        }

        @Override
        public String name() {
            return "LimitSell";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (barIndex < maxBars) {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.LIMIT, quantity, limitPrice));
            }
            barIndex++;
        }

        @Override
        protected void onReset() {
            barIndex = 0;
        }
    }

    static final class StopSell extends ScriptedStrategy {
        private final double stopPrice;
        private final double quantity;
        private final int maxBars;
        private int barIndex;

        StopSell(double stopPrice, double quantity, int maxBars) {
            this.stopPrice = stopPrice;
            this.quantity = quantity;
            this.maxBars = maxBars;
        }

        @Override
        public String name() {
            return "StopSell";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (barIndex < maxBars) {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.STOP, quantity, stopPrice));
            }
            barIndex++;
        }

        @Override
        protected void onReset() {
            barIndex = 0;
        }
    }

    static final class DoubleMarketBuySameBar extends ScriptedStrategy {
        private boolean sent;

        @Override
        public String name() {
            return "DoubleMarketBuySameBar";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (!sent) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 5_000, 0));
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 5_000, 0));
                sent = true;
            }
        }

        @Override
        protected void onReset() {
            sent = false;
        }
    }

    static final class BuyWithStopLossAndTakeProfit extends ScriptedStrategy {
        private final double stopLoss;
        private final double takeProfit;
        private boolean sent;

        BuyWithStopLossAndTakeProfit(double stopLoss, double takeProfit) {
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
        }

        @Override
        public String name() {
            return "BuyWithSLTP";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (!sent) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 10_000, 0)
                    .withStopLoss(stopLoss)
                    .withTakeProfit(takeProfit));
                sent = true;
            }
        }

        @Override
        protected void onReset() {
            sent = false;
        }
    }

    static final class SellWithStopLoss extends ScriptedStrategy {
        private final double stopLoss;
        private boolean sent;

        SellWithStopLoss(double stopLoss) {
            this.stopLoss = stopLoss;
        }

        @Override
        public String name() {
            return "SellWithSL";
        }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (!sent) {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.MARKET, 10_000, 0)
                    .withStopLoss(stopLoss));
                sent = true;
            }
        }

        @Override
        protected void onReset() {
            sent = false;
        }
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

    // ---------------------------------------------------------------
    //  Edging-specific test strategies
    // ---------------------------------------------------------------

    /** BUY bar 0, SELL closeOnly bar 1 — tests REDUCE_ONLY semantics. */
    public static Strategy buyThenCloseOnlySell() {
        return new BuyThenCloseOnlySell();
    }

    static final class BuyThenCloseOnlySell extends ScriptedStrategy {
        private int barIndex;

        @Override
        public String name() { return "BuyThenCloseOnlySell"; }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (barIndex == 0) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 10_000, 0));
            } else if (barIndex == 1) {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.MARKET, 10_000, 0).closeOnly());
            }
            barIndex++;
        }

        @Override
        protected void onReset() { barIndex = 0; }
    }

    /** SELL closeOnly bar 0, no opposite position — tests silent no-op. */
    public static Strategy closeOnlyWithoutPosition() {
        return new CloseOnlyWithoutPosition();
    }

    static final class CloseOnlyWithoutPosition extends ScriptedStrategy {
        private boolean sent;

        @Override
        public String name() { return "CloseOnlyNoOp"; }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (!sent) {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.MARKET, 10_000, 0).closeOnly());
                sent = true;
            }
        }

        @Override
        protected void onReset() { sent = false; }
    }

    /** BUY bar 0, SELL bar 1 (hedge), both survive to end. */
    public static Strategy longThenShortHedge() {
        return new LongThenShortHedge();
    }

    static final class LongThenShortHedge extends ScriptedStrategy {
        private int barIndex;

        @Override
        public String name() { return "LongThenShortHedge"; }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (barIndex == 0) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 10_000, 0));
            } else if (barIndex == 1) {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.MARKET, 10_000, 0));
            }
            barIndex++;
        }

        @Override
        protected void onReset() { barIndex = 0; }
    }

    /** BUY with stop-loss, then SHORT hedge — tests SL fires only on one side. */
    public static Strategy longWithStopThenShortHedge(double longStopLoss) {
        return new LongWithStopThenShortHedge(longStopLoss);
    }

    static final class LongWithStopThenShortHedge extends ScriptedStrategy {
        private final double longStopLoss;
        private int barIndex;

        LongWithStopThenShortHedge(double longStopLoss) {
            this.longStopLoss = longStopLoss;
        }

        @Override
        public String name() { return "LongWithStopThenShortHedge"; }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (barIndex == 0) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 10_000, 0)
                    .withStopLoss(longStopLoss));
            } else if (barIndex == 1) {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.MARKET, 10_000, 0));
            }
            barIndex++;
        }

        @Override
        protected void onReset() { barIndex = 0; }
    }

    /** LONG and SHORT with independent SL/TP — tests per-side stop handling. */
    public static Strategy longAndShortWithSeparateStops(double longSl, double shortSl) {
        return new LongAndShortWithSeparateStops(longSl, shortSl);
    }

    static final class LongAndShortWithSeparateStops extends ScriptedStrategy {
        private final double longSl, shortSl;
        private int barIndex;

        LongAndShortWithSeparateStops(double longSl, double shortSl) {
            this.longSl = longSl;
            this.shortSl = shortSl;
        }

        @Override
        public String name() { return "LongAndShortWithStops"; }

        @Override
        public void onBar(Bar bar) {
            pending.clear();
            if (barIndex == 0) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 10_000, 0)
                    .withStopLoss(longSl));
            } else if (barIndex == 1) {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.MARKET, 10_000, 0)
                    .withStopLoss(shortSl));
            }
            barIndex++;
        }

        @Override
        protected void onReset() { barIndex = 0; }
    }
}
