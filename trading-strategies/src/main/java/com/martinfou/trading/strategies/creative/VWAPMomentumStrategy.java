package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * VWAP Momentum Continuation Strategy — Structure/Technical
 *
 * 📊 Inspiration: forex-soft-signals — VWAP Daddy's multi-VWAP momentum system
 *    and Tom Basso's VWAP-based trend trades. This trades WITH the VWAP deviation,
 *    not against it (H1 VWAP deviations tend to CONTINUE, not revert).
 *
 * 🔧 Mechanism:
 *    - Compute rolling VWAP over the last 24 bars (~1 trading day)
 *    - Entry: price closes > 1.0× ATR(14) above VWAP → BUY momentum continuation
 *            price closes < 1.0× ATR(14) below VWAP → SELL momentum continuation
 *    - Exit: trailing stop at 1.5× ATR from highest/lowest point since entry
 *    - Also exit if price crosses back through VWAP (trend invalidation)
 *    - Max hold: 10 bars
 */
public class VWAPMomentumStrategy implements Strategy {

    private static final int MIN_HISTORY = 60;
    private static final int VWAP_PERIOD = 24;
    private static final int ATR_PERIOD = 14;
    private static final int RANGE_MEDIAN = 20;
    private static final double ENTRY_DEVIATION = 1.0;
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
    private int barsInTrade;
    private double highestSinceEntry;
    private double lowestSinceEntry;
    private double positionSize;
    private int cooldownBars;
    private int tradesToday;
    private int lastTradeDay;

    public VWAPMomentumStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
    }

    public VWAPMomentumStrategy() {
        this("VWAPMomentum", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        // Daily trade counter
        int barDay = bar.timestamp().atZone(java.time.ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        managePosition(bar);

        if (!inTrade) {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 2) return; // max 2 trades/day
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
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

        // Track extremes for trailing stop
        if (tradeDirection == Order.Side.BUY) {
            highestSinceEntry = Math.max(highestSinceEntry, bar.high());
        } else {
            lowestSinceEntry = Math.min(lowestSinceEntry, bar.low());
        }

        // Check SL (from engine) via manual check, TP via manual check, max bars
        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);
        boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= takeProfit)
            || (tradeDirection == Order.Side.SELL && bar.low() <= takeProfit);

        if (stopHit || tpHit || barsInTrade >= MAX_BARS_HOLD) {
            closePosition(bar.close());
            return;
        }

        // Trailing stop: trail from extreme
        if (tradeDirection == Order.Side.BUY) {
            double trail = highestSinceEntry - atr() * ATR_STOP_MULT;
            stopLoss = Math.max(stopLoss, trail);
            if (bar.low() <= stopLoss) { closePosition(bar.close()); return; }
        } else {
            double trail = lowestSinceEntry + atr() * ATR_STOP_MULT;
            stopLoss = Math.min(stopLoss, trail);
            if (bar.high() >= stopLoss) { closePosition(bar.close()); return; }
        }

        // VWAP re-cross exit
        double vwap = computeVWAP();
        if (!Double.isNaN(vwap)) {
            if (tradeDirection == Order.Side.BUY && bar.close() < vwap) {
                closePosition(bar.close());
                return;
            }
            if (tradeDirection == Order.Side.SELL && bar.close() > vwap) {
                closePosition(bar.close());
                return;
            }
        }
    }

    private void evaluateEntry(Bar bar) {
        double vwap = computeVWAP();
        double atr = atr();
        if (Double.isNaN(vwap) || Double.isNaN(atr) || atr <= 0) return;

        double deviation = (bar.close() - vwap) / atr;

        // Only enter on bars with above-median range (conviction)
        if (!hasAboveMedianRange()) return;

        if (deviation >= ENTRY_DEVIATION) {
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            takeProfit = entryPrice + atr * ATR_STOP_MULT * RR_TARGET;
            highestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
            tradesToday++;
        } else if (deviation <= -ENTRY_DEVIATION) {
            entryPrice = bar.close();
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
    }

    /**
     * Compute volume-weighted average price over VWAP_PERIOD bars.
     * Falls back to typical-price (HLC/3) average when volume=0 (Dukascopy).
     */
    private double computeVWAP() {
        int end = history.size() - 1;
        if (end < VWAP_PERIOD) return Double.NaN;

        double sumPriceVol = 0;
        double sumVol = 0;

        for (int i = end - VWAP_PERIOD + 1; i <= end; i++) {
            Bar b = history.get(i);
            double tp = (b.high() + b.low() + b.close()) / 3.0;
            long vol = b.volume();
            if (vol > 0) {
                sumPriceVol += tp * vol;
                sumVol += vol;
            } else {
                sumPriceVol += tp;
                sumVol += 1;
            }
        }

        if (sumVol <= 0) return Double.NaN;
        return sumPriceVol / sumVol;
    }

    /** Check if the latest bar's range is above the median of the last N bars. */
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
