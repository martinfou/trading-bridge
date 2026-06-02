package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * Momentum Acceleration Breakout Strategy — News/Sentiment proxy
 *
 * 📊 Inspiration: Ali Casey's distribution-over-peak philosophy applied to
 *    momentum. Trades on ACCELERATION — the CHANGE in momentum velocity.
 *    Catches moves just as they gain steam (before peak velocity).
 *
 * 🔧 Mechanism:
 *    - Compute 5-bar trailing return (= momentum)
 *    - Compute 3-bar rate-of-change of this return (= acceleration)
 *    - Entry: acceleration > 0 AND 5-bar return > +0.5% → BUY
 *            acceleration < 0 AND 5-bar return < -0.5% → SELL
 *    - Exit: trailing stop, acceleration flip, or max 10 bars
 */
public class MomentumAccelerationStrategy implements Strategy {

    private static final int MIN_HISTORY = 80;
    private static final int RETURN_PERIOD = 5;
    private static final int ACCEL_WINDOW = 3;
    private static final int ATR_PERIOD = 14;
    private static final int RANGE_MEDIAN = 20;
    private static final double RETURN_THRESHOLD = 0.005;
    private static final double ATR_STOP_MULT = 1.5;
    private static final double RR_TARGET = 2.0;
    private static final int MAX_BARS_HOLD = 10;
    private static final double MIN_POSITION = 1000;
    private static final int COOLDOWN_BARS = 3;

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private Double entryAccel;
    private int barsInTrade;
    private double highestSinceEntry;
    private double lowestSinceEntry;
    private double positionSize;
    private int cooldownBars;
    private int tradesToday;
    private int lastTradeDay;

    public MomentumAccelerationStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
    }

    public MomentumAccelerationStrategy() {
        this("MomentumAcceleration", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        int barDay = bar.timestamp().atZone(java.time.ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        managePosition(bar);

        if (!inTrade) {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 2) return;
            evaluateEntry(bar);
        }
    }

    @Override
    public void onTick(double bid, double ask, long volume) {}

    @Override
    public List<Order> getPendingOrders() {
        var copy = List.copyOf(pending);
        pending.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        barsInTrade = 0;
        cooldownBars = 0;
        tradesToday = 0;
        lastTradeDay = -1;
        entryAccel = null;
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

        if (tradeDirection == Order.Side.BUY) {
            highestSinceEntry = Math.max(highestSinceEntry, bar.high());
        } else {
            lowestSinceEntry = Math.min(lowestSinceEntry, bar.low());
        }

        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);
        boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= takeProfit)
            || (tradeDirection == Order.Side.SELL && bar.low() <= takeProfit);

        if (stopHit || tpHit || barsInTrade >= MAX_BARS_HOLD) {
            closePosition(bar.close());
            return;
        }

        // Trailing stop
        if (tradeDirection == Order.Side.BUY) {
            double trail = highestSinceEntry - atr() * ATR_STOP_MULT;
            stopLoss = Math.max(stopLoss, trail);
            if (bar.low() <= stopLoss) { closePosition(bar.close()); return; }
        } else {
            double trail = lowestSinceEntry + atr() * ATR_STOP_MULT;
            stopLoss = Math.min(stopLoss, trail);
            if (bar.high() >= stopLoss) { closePosition(bar.close()); return; }
        }

        // Acceleration flip exit
        double accel = computeAcceleration();
        if (entryAccel != null && !Double.isNaN(accel)) {
            if ((tradeDirection == Order.Side.BUY && accel < -0.0001)
                || (tradeDirection == Order.Side.SELL && accel > 0.0001)) {
                closePosition(bar.close());
            }
        }
    }

    private void evaluateEntry(Bar bar) {
        double accel = computeAcceleration();
        double ret5 = compute5BarReturn();
        if (Double.isNaN(accel) || Double.isNaN(ret5)) return;

        double atr = atr();
        if (Double.isNaN(atr) || atr <= 0) return;

        if (!hasAboveMedianRange()) return;

        if (accel > 0 && ret5 > RETURN_THRESHOLD) {
            entryPrice = bar.close();
            entryAccel = accel;
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            takeProfit = entryPrice + atr * ATR_STOP_MULT * RR_TARGET;
            highestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
            tradesToday++;
        } else if (accel < 0 && ret5 < -RETURN_THRESHOLD) {
            entryPrice = bar.close();
            entryAccel = accel;
            stopLoss = entryPrice + atr * ATR_STOP_MULT;
            takeProfit = entryPrice - atr * ATR_STOP_MULT * RR_TARGET;
            lowestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            barsInTrade = 0;
            tradesToday++;
        }
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, positionSize, price).closeOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
        entryAccel = null;
    }

    private double computeAcceleration() {
        int end = history.size() - 1;
        if (end < RETURN_PERIOD + ACCEL_WINDOW) return Double.NaN;

        double[] returns = new double[ACCEL_WINDOW];
        for (int i = 0; i < ACCEL_WINDOW; i++) {
            int idx = end - ACCEL_WINDOW + 1 + i;
            returns[i] = (history.get(idx).close() - history.get(idx - RETURN_PERIOD).close())
                / history.get(idx - RETURN_PERIOD).close();
        }

        return returns[ACCEL_WINDOW - 1] - returns[0];
    }

    private double compute5BarReturn() {
        int end = history.size() - 1;
        if (end < RETURN_PERIOD) return Double.NaN;
        return (history.get(end).close() - history.get(end - RETURN_PERIOD).close())
            / history.get(end - RETURN_PERIOD).close();
    }

    private boolean hasAboveMedianRange() {
        int end = history.size() - 1;
        int lookback = Math.min(RANGE_MEDIAN, end);
        if (lookback < 3) return true;

        double[] ranges = new double[lookback];
        for (int i = 0; i < lookback; i++) {
            int idx = end - lookback + 1 + i;
            ranges[i] = history.get(idx).high() - history.get(idx).low();
        }
        double latestRange = history.get(end).high() - history.get(end).low();
        Arrays.sort(ranges);
        double median = ranges[lookback / 2];
        return latestRange >= median;
    }

    private double atr() {
        return Indicators.atr(history, ATR_PERIOD);
    }
}
