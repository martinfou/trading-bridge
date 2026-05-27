package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Bollinger Squeeze Breakout Strategy — Volatility + Breakout
 *
 * 📊 Data insight: When Bollinger Band width contracts to a multi-period low,
 *    volatility is compressing ("the squeeze"). This typically precedes a
 *    sharp expansion. Trading the direction of the first strong bar after
 *    the squeeze captures the explosive move.
 *
 * 🔧 Mechanism: Detect Bollinger Band squeeze, trade the breakout.
 *    - Calculate BB(20,2): middle = SMA(20), upper = SMA+2*σ, lower = SMA-2*σ
 *    - BandWidth = (upper - lower) / middle
 *    - Squeeze = BandWidth < 20-period SMA of BandWidth (narrowing)
 *    - After squeeze, trade break of the squeeze high/low
 *    - ATR-based stop and target
 *
 * 🎯 Originality: Pure volatility cycle play using the Bollinger squeeze
 *    concept adapted for H1. Unlike VolContractionBreakoutStrategy which
 *    compares consecutive bars, this looks at the 20-bar volatility cycle.
 */
public class BollingerSqueezeBreakoutStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 10000;

    // Squeeze state
    private boolean squeezeActive = false;
    private double squeezeHigh = 0;
    private double squeezeLow = Double.MAX_VALUE;
    private int barsSinceSqueeze = 0;
    private int maxBarsAfterSqueeze = 8;

    public BollingerSqueezeBreakoutStrategy() {
        this("BollingerSqueezeBreakout", "GBP/JPY");
    }

    public BollingerSqueezeBreakoutStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public BollingerSqueezeBreakoutStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 45) return;

        double atr = calculateATR(14);

        // Manage active trade
        if (inTrade) {
            barsHeld++;

            if (barsHeld >= 5) { closePosition(bar); return; }

            if (tradeDirection == Order.Side.BUY) {
                if (bar.high() >= entryPrice + atr * 2.0) { closePosition(bar); return; }
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar); return; }
            } else {
                if (bar.low() <= entryPrice - atr * 2.0) { closePosition(bar); return; }
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar); return; }
            }
            return;
        }

        // Check for Bollinger squeeze
        double[] bb = calculateBB(20, 2.0);
        double middle = bb[0];
        double upper = bb[1];
        double lower = bb[2];
        double bandWidth = (upper - lower) / (middle > 0 ? middle : 1);

        // Calculate SMA of bandWidth over 20 periods
        double bwSMA = calculateBandWidthSMA(20);

        // Detect squeeze
        boolean newSqueeze = bandWidth < bwSMA * 1.0;

        if (newSqueeze && !squeezeActive) {
            squeezeActive = true;
            squeezeHigh = bar.high();
            squeezeLow = bar.low();
            barsSinceSqueeze = 0;
        }

        if (squeezeActive) {
            barsSinceSqueeze++;

            // Update squeeze zone
            if (bar.high() > squeezeHigh) squeezeHigh = bar.high();
            if (bar.low() < squeezeLow) squeezeLow = bar.low();

            // Expire squeeze after too many bars
            if (barsSinceSqueeze > maxBarsAfterSqueeze) {
                squeezeActive = false;
                return;
            }

            // Look for breakout
            if (bar.high() > squeezeHigh && bar.close() > bar.open()) {
                // Upside breakout
                if ((bar.high() - bar.low()) > atr * 0.6) {
                    pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                    inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); barsHeld = 0;
                    squeezeActive = false;
                }
            } else if (bar.low() < squeezeLow && bar.close() < bar.open()) {
                // Downside breakout
                if ((bar.high() - bar.low()) > atr * 0.6) {
                    pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                    inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); barsHeld = 0;
                    squeezeActive = false;
                }
            }

            // Reset squeeze if price hasn't broken out and bandwidth is expanding again
            if (bandWidth > bwSMA * 1.2) {
                squeezeActive = false;
            }
        }
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()));
        inTrade = false;
    }

    private double[] calculateBB(int period, double mult) {
        double sma = 0;
        int size = history.size();
        for (int i = size - period; i < size; i++) {
            sma += history.get(i).close();
        }
        sma /= period;

        double variance = 0;
        for (int i = size - period; i < size; i++) {
            double diff = history.get(i).close() - sma;
            variance += diff * diff;
        }
        double std = Math.sqrt(variance / period);

        return new double[]{sma, sma + mult * std, sma - mult * std};
    }

    private double calculateBandWidthSMA(int period) {
        if (history.size() < period + 20) return 1.0;
        double sum = 0;
        for (int i = history.size() - period; i < history.size(); i++) {
            double[] bb = calculateBBAt(20, 2.0, i);
            double mid = bb[0] > 0 ? bb[0] : 1;
            double bw = (bb[1] - bb[2]) / mid;
            sum += bw;
        }
        return sum / period;
    }

    private double[] calculateBBAt(int period, double mult, int endIdx) {
        if (endIdx < period) return new double[]{0, 0, 0};
        double sma = 0;
        for (int i = endIdx - period + 1; i <= endIdx; i++) {
            sma += history.get(i).close();
        }
        sma /= period;
        double variance = 0;
        for (int i = endIdx - period + 1; i <= endIdx; i++) {
            double diff = history.get(i).close() - sma;
            variance += diff * diff;
        }
        double std = Math.sqrt(variance / period);
        return new double[]{sma, sma + mult * std, sma - mult * std};
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
        tradeDirection = Order.Side.BUY; entryPrice = 0; barsHeld = 0;
        squeezeActive = false; squeezeHigh = 0; squeezeLow = Double.MAX_VALUE;
        barsSinceSqueeze = 0;
    }
}
