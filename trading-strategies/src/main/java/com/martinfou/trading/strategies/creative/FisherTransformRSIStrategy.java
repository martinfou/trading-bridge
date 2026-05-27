package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Fisher Transform RSI Strategy — Momentum + Statistical
 *
 * 📊 Data insight: The Fisher Transform normalizes price data to a Gaussian
 *    distribution, making extreme values (peaks/troughs) more pronounced
 *    and easier to identify. Applied to RSI(9), it converts the bounded
 *    [0,100] range into an unbounded Gaussian where values > 2 or < -2
 *    indicate significant extremes prone to reversal.
 *
 * 🔧 Mechanism:
 *    - Calculate RSI(9) on H1 closes
 *    - Apply Fisher Transform: F = 0.5 × ln((1+X)/(1-X)) where X = 2×normalizedRSI-1
 *    - Fisher > 1.5 → overbought, expect mean reversion SELL
 *    - Fisher < -1.5 → oversold, expect mean reversion BUY
 *    - Entry triggered when Fisher crosses back below 1.0 (after >1.5) for sell
 *      or crosses back above -1.0 (after <-1.5) for buy
 *
 * 🎯 Originality: Uses statistical normalization (Fisher Transform) on RSI
 *    instead of raw RSI thresholds. The Fisher Transform makes turning points
 *    sharper and more identifiable across different asset volatilities.
 */
public class FisherTransformRSIStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double positionSize = 10000;

    // Fisher state
    private double prevFisherValue = 0;
    private double fisherValue = 0;
    private boolean wasOverbought = false;
    private boolean wasOversold = false;
    private boolean fisherRising = false;

    public FisherTransformRSIStrategy() {
        this("FisherTransformRSI", "GBP/JPY");
    }

    public FisherTransformRSIStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public FisherTransformRSIStrategy(String name, String symbol) {
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

        // Manage active trade
        if (inTrade) {
            if (tradeDirection == Order.Side.BUY) {
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar); return; }
                if (bar.high() >= entryPrice + atr * 2.0) { closePosition(bar); return; }
                // Time stop: exit after 10 bars
                if (history.size() > 10 && bar.timestamp() != null) {
                    int entryIdx = findEntryBar();
                    if (entryIdx > 0 && history.size() - entryIdx > 12) { closePosition(bar); return; }
                }
            } else {
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar); return; }
                if (bar.low() <= entryPrice - atr * 2.0) { closePosition(bar); return; }
                if (history.size() > 10) {
                    int entryIdx = findEntryBar();
                    if (entryIdx > 0 && history.size() - entryIdx > 12) { closePosition(bar); return; }
                }
            }
            return;
        }

        // Calculate Fisher Transform on RSI(9)
        double[] rsiVal = calculateRSI(9);
        if (rsiVal.length < 2) return;

        double rsi = rsiVal[rsiVal.length - 1];

        // Normalize RSI to [-1, 1]
        double normRsi = 2.0 * (rsi / 100.0) - 1.0;

        // Apply Fisher Transform: F = 0.5 * ln((1+X)/(1-X))
        // Clamp to avoid ln(0)
        if (normRsi > 0.999) normRsi = 0.999;
        if (normRsi < -0.999) normRsi = -0.999;

        prevFisherValue = fisherValue;
        fisherValue = 0.5 * Math.log((1.0 + normRsi) / (1.0 - normRsi));
        // Smooth with previous value
        fisherValue = 0.67 * fisherValue + 0.33 * prevFisherValue;

        fisherRising = fisherValue > prevFisherValue;

        // Track extreme zones
        if (fisherValue > 1.5) wasOverbought = true;
        if (fisherValue < -1.5) wasOversold = true;

        // Reset extremes when leaving the zone
        if (fisherValue < 0.5) wasOverbought = false;
        if (fisherValue > -0.5) wasOversold = false;

        // Entry signals
        // BUY: was oversold (< -1.5), now crossing above -1.0 with rising Fisher
        if (wasOversold && prevFisherValue <= -1.0 && fisherValue > -1.0 && fisherRising) {
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close();
            wasOversold = false;
            return;
        }

        // SELL: was overbought (> 1.5), now crossing below 1.0 with falling Fisher
        if (wasOverbought && prevFisherValue >= 1.0 && fisherValue < 1.0 && !fisherRising) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close();
            wasOverbought = false;
            return;
        }
    }

    private int findEntryBar() {
        // Simple heuristic: look back for the last bar that generated an order
        return history.size() - 5;
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()));
        inTrade = false;
    }

    private double[] calculateRSI(int period) {
        if (history.size() < period + 1) return new double[]{50};

        int size = history.size();
        double[] rsiValues = new double[size];
        rsiValues[period] = 50; // seed

        double gainSum = 0, lossSum = 0;
        for (int i = 1; i <= period; i++) {
            double change = history.get(i).close() - history.get(i - 1).close();
            if (change > 0) gainSum += change;
            else lossSum -= change;
        }

        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;

        for (int i = period + 1; i < size; i++) {
            double change = history.get(i).close() - history.get(i - 1).close();
            double gain = change > 0 ? change : 0;
            double loss = change < 0 ? -change : 0;

            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;

            double rs = avgLoss == 0 ? 100 : avgGain / avgLoss;
            rsiValues[i] = 100 - (100 / (1 + rs));
        }

        return rsiValues;
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

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending); pending.clear(); return copy;
    }
    @Override public void reset() {
        history.clear(); pending.clear(); inTrade = false;
        tradeDirection = Order.Side.BUY; entryPrice = 0;
        prevFisherValue = 0; fisherValue = 0;
        wasOverbought = false; wasOversold = false; fisherRising = false;
    }
}
