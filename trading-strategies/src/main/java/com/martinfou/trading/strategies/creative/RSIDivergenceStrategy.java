package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * RSI Divergence Strategy — Momentum + Mean Reversion
 *
 * 📊 Data insight: RSI(14) divergence occurs when price makes a new high/low
 *    but RSI does not confirm. This indicates momentum exhaustion and often
 *    precedes a reversal. The signal is most reliable when RSI is in extreme
 *    territory (>70 or <30) and the divergence spans 3+ bars.
 *
 * 🔧 Mechanism: Detect hidden and regular divergences.
 *    - Regular bearish divergence: price higher high, RSI lower high → SELL
 *    - Regular bullish divergence: price lower low, RSI higher low → BUY
 *    - Validate with RSI in extreme zone (>65 for bearish, <35 for bullish)
 *    - Hold for 2 bars or until ATR-based target/stop
 *
 * 🎯 Originality: Pure divergence detection without oscillators or trend
 *    filters. Works across all assets because divergence is a universal
 *    market phenomenon. Adapts to each asset's volatility via ATR.
 */
public class RSIDivergenceStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 10000;

    // Divergence tracking
    private double peakPrice = 0;
    private double peakRSI = 0;
    private double troughPrice = Double.MAX_VALUE;
    private double troughRSI = 100;
    private int peakBar = 0;
    private int troughBar = 0;

    public RSIDivergenceStrategy() {
        this("RSIDivergence", "GBP/JPY");
    }

    public RSIDivergenceStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public RSIDivergenceStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 20) return;

        // Manage active trade
        if (inTrade) {
            barsHeld++;
            double atr = calculateATR(14);

            if (barsHeld >= 3) {
                closePosition(bar);
                return;
            }

            if (tradeDirection == Order.Side.BUY) {
                if (bar.high() >= entryPrice + atr * 1.2) { closePosition(bar); return; }
                if (bar.low() <= entryPrice - atr * 0.7) { closePosition(bar); return; }
            } else {
                if (bar.low() <= entryPrice - atr * 1.2) { closePosition(bar); return; }
                if (bar.high() >= entryPrice + atr * 0.7) { closePosition(bar); return; }
            }
            return;
        }

        double rsi = calculateRSI(14);
        double price = bar.close();

        // Update peaks and troughs
        int lastN = 10;
        int startIdx = Math.max(0, history.size() - lastN);

        // Find the highest price and corresponding RSI in lookback window
        double maxPrice = 0;
        double maxPriceRSI = 0;
        double minPrice = Double.MAX_VALUE;
        double minPriceRSI = 0;

        for (int i = startIdx; i < history.size(); i++) {
            double p = history.get(i).high();
            if (p > maxPrice) {
                maxPrice = p;
                // Compute RSI at that bar's index
                maxPriceRSI = calculateRSIAt(14, i);
            }
            double lp = history.get(i).low();
            if (lp < minPrice) {
                minPrice = lp;
                minPriceRSI = calculateRSIAt(14, i);
            }
        }

        // Check for bearish divergence: price making higher high, RSI making lower high
        if (price > peakPrice * 0.999 && rsi < peakRSI - 5 && peakRSI > 65) {
            // Bearish divergence detected
            if (rsi < 70 && price > history.get(Math.max(0, history.size() - 2)).close()) {
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true;
                tradeDirection = Order.Side.SELL;
                entryPrice = bar.close();
                barsHeld = 0;
                return;
            }
        }

        // Check for bullish divergence: price making lower low, RSI making higher low
        if (price < troughPrice * 1.001 && rsi > troughRSI + 5 && troughRSI < 35) {
            if (rsi > 30 && price < history.get(Math.max(0, history.size() - 2)).close()) {
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true;
                tradeDirection = Order.Side.BUY;
                entryPrice = bar.close();
                barsHeld = 0;
                return;
            }
        }

        // Update tracker
        if (rsi > peakRSI || price > peakPrice) {
            if (rsi > peakRSI) { peakRSI = rsi; peakBar = history.size(); }
            if (price > peakPrice) { peakPrice = price; }
        }
        if (rsi < troughRSI || price < troughPrice) {
            if (rsi < troughRSI) { troughRSI = rsi; troughBar = history.size(); }
            if (price < troughPrice) { troughPrice = price; }
        }

        // Decay trackers over time
        if (history.size() - peakBar > 20) { peakRSI *= 0.95; peakPrice *= 0.999; }
        if (history.size() - troughBar > 20) { troughRSI = troughRSI * 0.95 + 5; troughPrice *= 1.001; }
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()));
        inTrade = false;
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
        barsHeld = 0;
        peakPrice = 0;
        peakRSI = 0;
        troughPrice = Double.MAX_VALUE;
        troughRSI = 100;
        peakBar = 0;
        troughBar = 0;
    }

    private double calculateRSI(int period) {
        return calculateRSIAt(period, history.size() - 1);
    }

    private double calculateRSIAt(int period, int idx) {
        if (idx < period) return 50;
        double gain = 0, loss = 0;
        for (int i = idx - period + 1; i <= idx; i++) {
            double diff = history.get(i).close() - history.get(i - 1).close();
            if (diff > 0) gain += diff; else loss -= diff;
        }
        double avgGain = gain / period, avgLoss = loss / period;
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    private double calculateATR(int period) {
        int size = history.size();
        double sum = 0;
        int count = 0;
        for (int i = Math.max(1, size - period); i < size; i++) {
            Bar prev = history.get(i - 1);
            Bar curr = history.get(i);
            double tr = Math.max(curr.high() - curr.low(),
                Math.max(Math.abs(curr.high() - prev.close()),
                         Math.abs(curr.low() - prev.close())));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 1.0;
    }
}
