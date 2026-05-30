package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * ATR Channel Trailing Strategy — Trend Following + Volatility
 *
 * 📊 Data insight: In sustained trends, price stays above/below an ATR-based
 *    trailing channel. The "chandelier exit" technique places the stop at
 *    3×ATR below the highest high since entry (for longs). This allows
 *    enough room for normal volatility while locking in gains as the
 *    trend develops. Best in trending conditions with ADX > 20.
 *
 * 🔧 Mechanism: Trend entry + chandelier trailing exit.
 *    - Entry: When price closes above EMA(50) and momentum is bullish
 *      (close > open AND range > 0.7×ATR) → BUY
 *    - When price closes below EMA(50) and bearish → SELL
 *    - Trail stop: For longs, stop = highest_high_since_entry - 3×ATR
 *    - For shorts, stop = lowest_low_since_entry + 3×ATR
 *    - Exit when stopped out or trend reverses
 *
 * 🎯 Originality: Captures trends with a robust trailing mechanism that
 *    adapts to each asset's volatility through ATR. Unlike fixed-pip
 *    trailing stops, this scales with market conditions.
 */
public class ATRChannelTrailStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;

    // Chandelier trail
    private double highestHigh = 0;
    private double lowestLow = Double.MAX_VALUE;
    private double trailingStop = 0;
    private double positionSize = 1000;

    public ATRChannelTrailStrategy() {
        this("ATRChannelTrail", "GBP/JPY");
    }

    public ATRChannelTrailStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public ATRChannelTrailStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 55) return;

        double ema50 = calculateEMA(50);
        double atr = calculateATR(14);
        double avgRange = calculateAverageRange(10);

        // Manage active trade
        if (inTrade) {
            barsHeld++;

            // Update trailing extremes
            if (tradeDirection == Order.Side.BUY) {
                if (bar.high() > highestHigh) {
                    highestHigh = bar.high();
                    trailingStop = highestHigh - atr * 3.0;
                }
                // Exit on stop hit or opposing EMA signal
                if (bar.low() <= trailingStop || barsHeld >= 12) {
                    closePosition(bar); return;
                }
                // Optional: tighten stop if ADX falling
            } else {
                if (bar.low() < lowestLow) {
                    lowestLow = bar.low();
                    trailingStop = lowestLow + atr * 3.0;
                }
                if (bar.high() >= trailingStop || barsHeld >= 12) {
                    closePosition(bar); return;
                }
            }
            return;
        }

        // Entry: strong move in the direction of EMA50
        boolean bullishEma = bar.close() > ema50;
        boolean bearishEma = bar.close() < ema50;
        boolean strongRange = (bar.high() - bar.low()) > avgRange * 0.7;

        // BUY: price above EMA50 with strong bullish momentum
        if (bullishEma && bar.close() > bar.open() && strongRange) {
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.BUY;
            entryPrice = bar.close(); barsHeld = 0;
            highestHigh = bar.high();
            trailingStop = bar.close() - atr * 3.0;
        }
        // SELL: price below EMA50 with strong bearish momentum
        else if (bearishEma && bar.close() < bar.open() && strongRange) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.SELL;
            entryPrice = bar.close(); barsHeld = 0;
            lowestLow = bar.low();
            trailingStop = bar.close() + atr * 3.0;
        }
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()));
        inTrade = false;
    }

    private double calculateEMA(int period) {
        double k = 2.0 / (period + 1);
        double ema = history.get(0).close();
        for (int i = 1; i < history.size(); i++) {
            ema = history.get(i).close() * k + ema * (1 - k);
        }
        return ema;
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

    private double calculateAverageRange(int period) {
        int size = history.size();
        double sum = 0;
        for (int i = Math.max(0, size - period); i < size; i++) {
            sum += (history.get(i).high() - history.get(i).low());
        }
        return sum / Math.min(period, size);
    }

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending); pending.clear(); return copy;
    }
    @Override public void reset() {
        history.clear(); pending.clear(); inTrade = false;
        tradeDirection = Order.Side.BUY; entryPrice = 0; barsHeld = 0;
        highestHigh = 0; lowestLow = Double.MAX_VALUE; trailingStop = 0;
    }
}
