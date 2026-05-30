package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * MACD Histogram Divergence Strategy — Momentum + Divergence
 *
 * 📊 Data insight: MACD Histogram divergence is more sensitive than RSI
 *    divergence because MACD measures the difference between two EMAs
 *    (momentum), while the histogram measures the acceleration of momentum.
 *    When price makes a higher high but MACD Histogram makes a lower high,
 *    momentum is decelerating — a powerful reversal signal.
 *
 * 🔧 Mechanism:
 *    - Calculate MACD(12,26,9): EMA12 - EMA26, signal = EMA9 of MACD, histogram
 *    - Find pivot highs/lows in both price and histogram
 *    - Bullish divergence: price lower low, histogram higher low
 *    - Bearish divergence: price higher high, histogram lower high
 *    - Enter on confirmation bar (close above/below pivot bar close)
 *    - Exit when histogram flips direction
 *
 * 🎯 Originality: Uses MACD Histogram (not line) divergence, which is a
 *    second-derivative measure. Captures momentum exhaustion before price turns.
 */
public class MACDHistogramDivergenceStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private final List<Double> macdLine = new ArrayList<>();
    private final List<Double> signalLine = new ArrayList<>();
    private final List<Double> histogram = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double positionSize = 1000;

    public MACDHistogramDivergenceStrategy() {
        this("MACDHistoDivergence", "GBP/JPY");
    }

    public MACDHistogramDivergenceStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public MACDHistogramDivergenceStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 35) return;

        // Update MACD
        updateMACD();

        double atr = calculateATR(14);

        // Manage active trade
        if (inTrade) {
            // Exit when histogram flips direction relative to entry
            if (histogram.size() >= 3) {
                double h1 = histogram.get(histogram.size() - 1);
                double h2 = histogram.get(histogram.size() - 2);

                if (tradeDirection == Order.Side.BUY && h1 < 0 && h2 < 0 && h1 < h2) {
                    closePosition(bar); return;
                }
                if (tradeDirection == Order.Side.SELL && h1 > 0 && h2 > 0 && h1 > h2) {
                    closePosition(bar); return;
                }
            }

            // ATR stops + TP
            if (tradeDirection == Order.Side.BUY) {
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar); return; }
                if (bar.high() >= entryPrice + atr * 2.0) { closePosition(bar); return; }
            } else {
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar); return; }
                if (bar.low() <= entryPrice - atr * 2.0) { closePosition(bar); return; }
            }
            return;
        }

        // Need enough histogram history for divergence detection
        if (histogram.size() < 20) return;

        int idx = histogram.size() - 1;
        double currentHisto = histogram.get(idx);
        double currentClose = bar.close();

        // Find the most recent pivot in histogram (3-bar peak/trough)
        // Bullish divergence: price lower low, histogram higher low
        boolean bullishDiv = false;
        boolean bearishDiv = false;

        // Check for histogram trough (pivot low): bar[2] < bar[1] < bar[0] or similar
        if (idx >= 5) {
            for (int p = 3; p < 10 && (idx - p) >= 2; p++) {
                int pivotIdx = idx - p;
                // Histogram trough
                if (histogram.get(pivotIdx) < histogram.get(pivotIdx - 1) &&
                    histogram.get(pivotIdx) < histogram.get(pivotIdx + 1) &&
                    histogram.get(pivotIdx) < histogram.get(pivotIdx + 2)) {

                    double histoPivot = histogram.get(pivotIdx);
                    double pricePivotLow = history.get(pivotIdx).low();
                    double pricePivotClose = history.get(pivotIdx).close();

                    // Check if recent price made lower low but histogram made higher low
                    double recentLow = bar.low();
                    double recentHistoCurrent = currentHisto;
                    double recentHistoPrev = histogram.get(idx - 1);

                    if (recentLow < pricePivotLow * 0.9999 && // price lower low
                        recentHistoCurrent > histoPivot * 1.01 && // histogram higher trough
                        recentHistoCurrent < histoPivot + atr * 2 && // not too extended
                        bar.close() > bar.open()) { // bullish close
                        bullishDiv = true;
                        break;
                    }
                }

                // Histogram peak
                if (histogram.get(pivotIdx) > histogram.get(pivotIdx - 1) &&
                    histogram.get(pivotIdx) > histogram.get(pivotIdx + 1) &&
                    histogram.get(pivotIdx) > histogram.get(pivotIdx + 2)) {

                    double histoPivot = histogram.get(pivotIdx);
                    double pricePivotHigh = history.get(pivotIdx).high();
                    double pricePivotClose = history.get(pivotIdx).close();

                    double recentHigh = bar.high();
                    double recentHistoCurrent = currentHisto;

                    if (recentHigh > pricePivotHigh * 1.0001 && // price higher high
                        recentHistoCurrent < histoPivot * 0.99 && // histogram lower peak
                        recentHistoCurrent > histoPivot - atr * 2 &&
                        bar.close() < bar.open()) { // bearish close
                        bearishDiv = true;
                        break;
                    }
                }
            }
        }

        // Entry
        if (bullishDiv) {
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); return;
        }
        if (bearishDiv) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); return;
        }
    }

    private void updateMACD() {
        if (history.size() < 26) return;

        double ema12 = calculateEMA(12);
        double ema26 = calculateEMA(26);
        double macdVal = ema12 - ema26;

        macdLine.add(macdVal);

        // Signal line is EMA9 of MACD values
        if (macdLine.size() >= 9) {
            double signal = 0;
            double k = 2.0 / (9 + 1);
            signal = macdLine.get(0);
            for (int i = 1; i < macdLine.size(); i++)
                signal = macdLine.get(i) * k + signal * (1 - k);
            signalLine.add(signal);
            histogram.add(macdVal - signal);
        } else {
            histogram.add(0.0);
        }
    }

    private double calculateEMA(int period) {
        double k = 2.0 / (period + 1);
        double ema = history.get(0).close();
        for (int i = 1; i < history.size(); i++)
            ema = history.get(i).close() * k + ema * (1 - k);
        return ema;
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

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending); pending.clear(); return copy;
    }
    @Override public void reset() {
        history.clear(); pending.clear(); macdLine.clear(); signalLine.clear(); histogram.clear();
        inTrade = false; tradeDirection = Order.Side.BUY; entryPrice = 0;
    }
}
