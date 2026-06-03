package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * Post-News Continuation Strategy — V5
 *
 * Opposite of PostNewsAbsorption (fade): enters WITH the expansion direction
 * after an absorption/consolidation bar confirms the breakout is real.
 *
 * 🔧 Mechanism:
 *    - Detect volatility expansion bars: range > 1.8x median range of last 20 bars
 *    - After expansion, wait for absorption bar: range < median range with
 *      body confirming continuation in the expansion direction
 *    - Enter on absorption bar close in expansion direction
 *    - Exit: ATR trailing stop (2.0x for more room on continuations), max 8 bars hold
 *    - No RR target — let trend run with trailing stop
 */
public class PostNewsContinuationStrategy implements Strategy {

    private static final int MIN_HISTORY = 50;
    private static final int ATR_PERIOD = 14;
    private static final int RANGE_MEDIAN_BARS = 20;
    private static final double EXPANSION_MULT = 1.8;
    private static final int MAX_ABSORPTION_DELAY = 3;
    private static final double ATR_STOP_MULT = 2.0;   // wider stop for continuations
    private static final int MAX_BARS_HOLD = 8;
    private static final double MIN_POSITION = 1000;
    private static final int COOLDOWN_BARS = 3;

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private final ZoneId nyZone = ZoneId.of("America/New_York");

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private int barsInTrade;
    private double highestSinceEntry;
    private double lowestSinceEntry;
    private double positionSize;
    private int cooldownBars;
    private int tradesToday;
    private int lastTradeDay;

    private enum Phase { IDLE, EXPANSION_DETECTED }
    private Phase phase = Phase.IDLE;
    private int expansionBarIndex = -1;
    private Order.Side expansionDirection;
    private int barsSinceExpansion;

    public PostNewsContinuationStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
    }

    public PostNewsContinuationStrategy() {
        this("PostNewsContinuation", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        int barDay = bar.timestamp().atZone(nyZone).getDayOfYear();
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
        phase = Phase.IDLE;
        expansionBarIndex = -1;
        barsSinceExpansion = 0;
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

        if (tradeDirection == Order.Side.BUY) {
            highestSinceEntry = Math.max(highestSinceEntry, bar.high());
        } else {
            lowestSinceEntry = Math.min(lowestSinceEntry, bar.low());
        }

        // ATR trailing stop only — no fixed TP, let trend run
        double atr = atr();
        if (!Double.isNaN(atr) && atr > 0) {
            if (tradeDirection == Order.Side.BUY) {
                double trail = highestSinceEntry - atr * ATR_STOP_MULT;
                stopLoss = Math.max(stopLoss, trail);
                if (bar.low() <= stopLoss) { closePosition(bar.close()); return; }
            } else {
                double trail = lowestSinceEntry + atr * ATR_STOP_MULT;
                stopLoss = Math.min(stopLoss, trail);
                if (bar.high() >= stopLoss) { closePosition(bar.close()); return; }
            }
        }

        // Max bars hold
        if (barsInTrade >= MAX_BARS_HOLD) {
            closePosition(bar.close());
        }
    }

    private void evaluateEntry(Bar bar) {
        int end = history.size() - 1;
        double atr = atr();
        if (Double.isNaN(atr) || atr <= 0) return;

        double medianRange = computeMedianRange();

        switch (phase) {
            case IDLE -> {
                double range = bar.high() - bar.low();
                if (range > medianRange * EXPANSION_MULT) {
                    phase = Phase.EXPANSION_DETECTED;
                    expansionBarIndex = end;
                    expansionDirection = bar.close() > bar.open() ? Order.Side.BUY : Order.Side.SELL;
                    barsSinceExpansion = 0;
                }
            }
            case EXPANSION_DETECTED -> {
                checkContinuationEntry(bar, end, atr, medianRange);
            }
        }
    }

    /**
     * Check if current bar is an absorption bar confirming continuation
     * in the expansion direction. Enter WITH the expansion (breakout continuation).
     */
    private void checkContinuationEntry(Bar bar, int end, double atr, double medianRange) {
        double range = bar.high() - bar.low();

        // If another expansion fires, reset to this new one
        if (range > medianRange * EXPANSION_MULT) {
            expansionBarIndex = end;
            expansionDirection = bar.close() > bar.open() ? Order.Side.BUY : Order.Side.SELL;
            barsSinceExpansion = 0;
            phase = Phase.EXPANSION_DETECTED;
            return;
        }

        // Check for absorption bar (narrow range)
        if (range <= medianRange) {
            boolean bullishContinuation = bar.close() > bar.open()
                && bar.close() >= bar.low() + range * 0.6;
            boolean bearishContinuation = bar.close() < bar.open()
                && bar.close() <= bar.low() + range * 0.4;

            // Enter WITH the expansion direction (breakout continuation)
            if (bullishContinuation && expansionDirection == Order.Side.BUY) {
                doEntry(bar, Order.Side.BUY, atr);
                resetPhase();
                return;
            } else if (bearishContinuation && expansionDirection == Order.Side.SELL) {
                doEntry(bar, Order.Side.SELL, atr);
                resetPhase();
                return;
            }
        }

        // Timeout: max bars waiting for absorption
        barsSinceExpansion++;
        if (barsSinceExpansion >= MAX_ABSORPTION_DELAY) {
            resetPhase();
        }
    }

    private void doEntry(Bar bar, Order.Side direction, double atr) {
        entryPrice = bar.close();
        if (direction == Order.Side.BUY) {
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            highestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss));
        } else {
            stopLoss = entryPrice + atr * ATR_STOP_MULT;
            lowestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss));
        }
        inTrade = true;
        tradeDirection = direction;
        barsInTrade = 0;
        tradesToday++;
    }

    private void resetPhase() {
        phase = Phase.IDLE;
        expansionBarIndex = -1;
        barsSinceExpansion = 0;
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, positionSize, price).closeOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }

    private double computeMedianRange() {
        int end = history.size() - 1;
        int lookback = Math.min(RANGE_MEDIAN_BARS, end);
        if (lookback < 3) return Double.MAX_VALUE;
        double[] ranges = new double[lookback];
        for (int i = 0; i < lookback; i++) {
            int idx = end - lookback + 1 + i;
            ranges[i] = history.get(idx).high() - history.get(idx).low();
        }
        Arrays.sort(ranges);
        return ranges[lookback / 2];
    }

    private double atr() {
        return Indicators.atr(history, ATR_PERIOD);
    }
}
