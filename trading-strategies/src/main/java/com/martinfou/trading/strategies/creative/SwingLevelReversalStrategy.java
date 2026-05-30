package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Swing Level Reversal Strategy — Mean Reversion from S/R Swings
 *
 * 📊 Data insight: 5-bar pivot highs and lows represent significant
 *    support/resistance levels where institutions place orders. When price
 *    retests these levels and shows rejection (close in the unfavored half
 *    of the bar's range), it signals a high-probability reversal back toward
 *    the mean.
 *
 * 🔧 Mechanism: Trade reversals off swing pivot levels.
 *    - Detect 5-bar swing highs (high > 2 bars before AND 2 after)
 *    - Detect 5-bar swing lows (low < 2 bars before AND 2 after)
 *    - Keep last 2 swing highs and 2 swing lows
 *    - Retest of swing high with close within 0.2×ATR below, rejection → SELL
 *    - Retest of swing low with close within 0.2×ATR above, rejection → BUY
 *    - Each level can only be traded once
 *    - SL: 0.5×ATR beyond the swing level, TP: 1.5×ATR from entry
 *    - Max hold: 8 bars
 *
 * 🎯 Originality: Unlike fixed-pip pivot strategies, this dynamically adapts
 *    to each asset's volatility via ATR, and the 5-bar window naturally
 *    filters out noise. The "rejection" condition (close in lower/upper 50%
 *    of range) captures failed breakouts that classic pivot strategies miss.
 */
public class SwingLevelReversalStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double swingLevel = 0;
    private int barsHeld = 0;
    private double positionSize = 1000;

    // Swing levels (keep last 3 for margin, but only last 2 actively checked)
    private final List<Double> swingHighs = new ArrayList<>();
    private final List<Double> swingLows = new ArrayList<>();
    // Track which levels we've already traded (rounded to 4 decimals)
    private final Set<String> usedLevels = new HashSet<>();

    public SwingLevelReversalStrategy() {
        this("SwingLevelRev", "GBP/JPY");
    }

    public SwingLevelReversalStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public SwingLevelReversalStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 20) return;

        double atr = calculateATR(14);

        // --- Manage active trade ---
        if (inTrade) {
            barsHeld++;

            // Max hold: 8 bars
            if (barsHeld >= 8) { closePosition(bar); return; }

            if (tradeDirection == Order.Side.BUY) {
                // SL: 0.5*ATR below the swing low level
                if (bar.low() <= swingLevel - atr * 0.5) { closePosition(bar); return; }
                // TP: 1.5*ATR from entry
                if (bar.high() >= entryPrice + atr * 1.5) { closePosition(bar); return; }
            } else {
                // SL: 0.5*ATR above the swing high level
                if (bar.high() >= swingLevel + atr * 0.5) { closePosition(bar); return; }
                // TP: 1.5*ATR from entry
                if (bar.low() <= entryPrice - atr * 1.5) { closePosition(bar); return; }
            }
            return;
        }

        // --- Detect swing points (retrospectively, 2 bars after the pivot) ---
        // We need at least 5 bars in history: bars at positions size-5, size-4, size-3, size-2, size-1
        // Center is at size-3, current bar is at size-1 (the 2nd bar after center)
        if (history.size() >= 5) {
            int pivotIdx = history.size() - 3;
            Bar pivot = history.get(pivotIdx);
            Bar left1  = history.get(pivotIdx - 1);
            Bar left2  = history.get(pivotIdx - 2);
            Bar right1 = history.get(pivotIdx + 1);
            Bar right2 = bar; // current bar is the 2nd bar after pivot

            // 5-bar swing high: pivot.high > both left AND both right
            if (pivot.high() > left1.high() && pivot.high() > left2.high() &&
                pivot.high() > right1.high() && pivot.high() > right2.high()) {
                String key = "H" + roundLevel(pivot.high());
                if (!usedLevels.contains(key)) {
                    swingHighs.add(pivot.high());
                    usedLevels.add(key);
                    if (swingHighs.size() > 3) swingHighs.remove(0);
                }
            }

            // 5-bar swing low: pivot.low < both left AND both right
            if (pivot.low() < left1.low() && pivot.low() < left2.low() &&
                pivot.low() < right1.low() && pivot.low() < right2.low()) {
                String key = "L" + roundLevel(pivot.low());
                if (!usedLevels.contains(key)) {
                    swingLows.add(pivot.low());
                    usedLevels.add(key);
                    if (swingLows.size() > 3) swingLows.remove(0);
                }
            }
        }

        // --- Check retest of swing highs → SELL ---
        int checkHighStart = Math.max(0, swingHighs.size() - 2);
        for (int i = swingHighs.size() - 1; i >= checkHighStart; i--) {
            double level = swingHighs.get(i);
            // Price must close within 0.2*ATR below the swing high
            if (bar.close() <= level && bar.close() >= level - atr * 0.2) {
                double range = bar.high() - bar.low();
                // Rejection: close in the lower 50% of the body's range
                if (range > 0 && (bar.close() - bar.low()) / range < 0.5) {
                    // Mark level as used
                    usedLevels.add("H" + roundLevel(level));
                    // Enter SELL
                    pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                    inTrade = true;
                    tradeDirection = Order.Side.SELL;
                    entryPrice = bar.close();
                    swingLevel = level;
                    barsHeld = 0;
                    return;
                }
            }
        }

        // --- Check retest of swing lows → BUY ---
        int checkLowStart = Math.max(0, swingLows.size() - 2);
        for (int i = swingLows.size() - 1; i >= checkLowStart; i--) {
            double level = swingLows.get(i);
            // Price must close within 0.2*ATR above the swing low
            if (bar.close() >= level && bar.close() <= level + atr * 0.2) {
                double range = bar.high() - bar.low();
                // Rejection: close in the upper 50% of the body's range
                if (range > 0 && (bar.close() - bar.low()) / range > 0.5) {
                    // Mark level as used
                    usedLevels.add("L" + roundLevel(level));
                    // Enter BUY
                    pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                    inTrade = true;
                    tradeDirection = Order.Side.BUY;
                    entryPrice = bar.close();
                    swingLevel = level;
                    barsHeld = 0;
                    return;
                }
            }
        }
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()));
        inTrade = false;
    }

    /**
     * Round a price level to 4 decimal places for level deduplication.
     */
    private double roundLevel(double level) {
        return Math.round(level * 10000.0) / 10000.0;
    }

    private double calculateATR(int period) {
        int size = history.size();
        double sum = 0;
        int count = 0;
        for (int i = Math.max(1, size - period); i < size; i++) {
            Bar prev = history.get(i - 1);
            Bar curr = history.get(i);
            double tr = Math.max(curr.high() - curr.low(),
                Math.max(Math.abs(curr.high() - prev.close()), Math.abs(curr.low() - prev.close())));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 1.0;
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
        history.clear();
        pending.clear();
        inTrade = false;
        tradeDirection = Order.Side.BUY;
        entryPrice = 0;
        swingLevel = 0;
        barsHeld = 0;
        swingHighs.clear();
        swingLows.clear();
        usedLevels.clear();
    }
}
