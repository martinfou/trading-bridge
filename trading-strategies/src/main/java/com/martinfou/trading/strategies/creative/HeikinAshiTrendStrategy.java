package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Heikin Ashi Smoothed Trend Strategy — Trend Following + Custom Indicator
 *
 * 📊 Data insight: Heikin Ashi candles filter out market noise by averaging
 *    price data. Consecutive same-colored HA candles indicate a strong trend.
 *    After 3+ consecutive HA bull candles, the trend is confirmed and pullbacks
 *    offer entries. HA candles eliminate many false signals from regular OHLC.
 *
 * 🔧 Mechanism: Smoothed HA trend following.
 *    - Convert regular bars to Heikin Ashi: HA_close = (O+H+L+C)/4, HA_open =
 *      (prev HA_open + prev HA_close)/2, HA_high = max(H, HA_open, HA_close),
 *      HA_low = min(L, HA_open, HA_close)
 *    - Consecutive HA bull bars (close > open) → BUY bias. Enter on pullback
 *      where HA bar still closes bullish but has a lower shadow
 *    - Consecutive HA bear bars (close < open) → SELL bias
 *    - Exit when HA candles change color or 3 consecutive neutral bars
 *
 * 🎯 Originality: Uses Heikin Ashi specifically as a trend filter, not a
 *    trading system. The pullback entry into HA-confirmed trends is unique.
 *    The smoothing removes noise particularly well on major FX pairs.
 */
public class HeikinAshiTrendStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    // Heikin Ashi candles
    private final List<HACandle> haCandles = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 1000;

    // Trend strength tracking
    private int bullStreak = 0;
    private int bearStreak = 0;

    public HeikinAshiTrendStrategy() {
        this("HeikinAshiTrend", "GBP/JPY");
    }

    public HeikinAshiTrendStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public HeikinAshiTrendStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 3) return;

        // Compute Heikin Ashi
        HACandle ha = computeHA(bar);
        haCandles.add(ha);

        if (haCandles.size() < 4) return;

        double atr = calculateATR(14);

        // Track HA streak
        if (ha.isBull()) {
            bullStreak++;
            bearStreak = 0;
        } else if (ha.isBear()) {
            bearStreak++;
            bullStreak = 0;
        } else {
            bullStreak = 0;
            bearStreak = 0;
        }

        // Manage active trade
        if (inTrade) {
            barsHeld++;

            // Exit when HA changes direction
            if ((tradeDirection == Order.Side.BUY && ha.isBear()) ||
                (tradeDirection == Order.Side.SELL && ha.isBull())) {
                closePosition(bar); return;
            }

            if (barsHeld >= 6) { closePosition(bar); return; }

            if (tradeDirection == Order.Side.BUY) {
                if (bar.high() >= entryPrice + atr * 2.0) { closePosition(bar); return; }
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar); return; }
            } else {
                if (bar.low() <= entryPrice - atr * 2.0) { closePosition(bar); return; }
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar); return; }
            }
            return;
        }

        // Strong HA bull trend: look for pullback entry
        if (bullStreak >= 3) {
            // A pullback HA bar that still closes bullish but has a lower shadow (wick)
            double lowerWick = ha.open < ha.close ? ha.open - ha.low : ha.close - ha.low;
            double totalRange = ha.high - ha.low;
            // Significant lower wick suggests buying pressure even during pullback
            if (ha.isBull() && lowerWick > totalRange * 0.3 && lowerWick > atr * 0.15) {
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.BUY;
                entryPrice = bar.close(); barsHeld = 0;
            }
        }

        // Strong HA bear trend: look for rally entry
        if (bearStreak >= 3) {
            double upperWick = ha.open < ha.close ? ha.high - ha.close : ha.high - ha.open;
            double totalRange = ha.high - ha.low;
            if (ha.isBear() && upperWick > totalRange * 0.3 && upperWick > atr * 0.15) {
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.SELL;
                entryPrice = bar.close(); barsHeld = 0;
            }
        }
    }

    private HACandle computeHA(Bar bar) {
        double haClose = (bar.open() + bar.high() + bar.low() + bar.close()) / 4.0;
        double haOpen, haHigh, haLow;

        if (haCandles.isEmpty()) {
            haOpen = (bar.open() + bar.close()) / 2.0;
        } else {
            HACandle prev = haCandles.getLast();
            haOpen = (prev.open + prev.close) / 2.0;
        }

        haHigh = Math.max(bar.high(), Math.max(haOpen, haClose));
        haLow = Math.min(bar.low(), Math.min(haOpen, haClose));

        return new HACandle(haOpen, haHigh, haLow, haClose);
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()));
        inTrade = false;
    }

    private double calculateATR(int period) {
        int size = history.size();
        double sum = 0; int count = 0;
        for (int i = Math.max(1, size - period); i < size; i++) {
            Bar prev = history.get(i - 1); Bar curr = history.get(i);
            double tr = Math.max(curr.high() - curr.low(),
                Math.max(Math.abs(curr.high() - prev.close()), Math.abs(curr.low() - prev.close())));
            sum += tr; count++;
        }
        return count > 0 ? sum / count : 1.0;
    }

    // Heikin Ashi candle record
    private record HACandle(double open, double high, double low, double close) {
        boolean isBull() { return close > open; }
        boolean isBear() { return close < open; }
    }

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending); pending.clear(); return copy;
    }
    @Override public void reset() {
        history.clear(); pending.clear(); haCandles.clear();
        inTrade = false; tradeDirection = Order.Side.BUY;
        entryPrice = 0; barsHeld = 0; bullStreak = 0; bearStreak = 0;
    }
}
