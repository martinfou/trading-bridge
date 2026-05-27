package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Chaikin Money Flow Strategy — Volume + Momentum
 *
 * 📊 Data insight: The Chaikin Money Flow (CMF) combines price and volume
 *    to measure buying/selling pressure over a lookback period. CMF > 0
 *    indicates buying pressure (close near high), CMF < 0 indicates selling
 *    pressure (close near low). When price is in a pullback but CMF stays
 *    positive, the pullback is likely a buying opportunity. Since not all
 *    assets have reliable volume, we use bar range as a volume proxy.
 *
 * 🔧 Mechanism:
 *    - Calculate Money Flow Multiplier = ((close - low) - (high - close)) / (high - low)
 *    - Money Flow Volume = MFM × range proxy
 *    - CMF(20) = sum(MFV over 20 bars) / sum(volume over 20 bars)
 *    - BUY when: price pulls back >0.5×ATR from high, CMF > 0 (buying pressure intact)
 *    - SELL when: price rallies >0.5×ATR from low, CMF < 0 (selling pressure intact)
 *    - Exit when CMF crosses zero against position direction
 *
 * 🎯 Originality: CMF is typically used on US equities with real volume data.
 *    This adaptation uses range-based volume proxy, making it work on FX
 *    where real volume isn't available. The pullback-entry approach adds
 *    timing precision to a volume-weighted indicator.
 */
public class ChaikinMoneyFlowStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double positionSize = 10000;

    public ChaikinMoneyFlowStrategy() {
        this("ChaikinMoneyFlow", "GBP/JPY");
    }

    public ChaikinMoneyFlowStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public ChaikinMoneyFlowStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 25) return;

        double atr = calculateATR(14);
        double cmf = calculateCMF(20);

        // Manage active trade
        if (inTrade) {
            // Exit when CMF crosses zero against position
            if (tradeDirection == Order.Side.BUY && cmf < -0.05) { closePosition(bar); return; }
            if (tradeDirection == Order.Side.SELL && cmf > 0.05) { closePosition(bar); return; }

            if (tradeDirection == Order.Side.BUY) {
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar); return; }
                if (bar.high() >= entryPrice + atr * 2.0) { closePosition(bar); return; }
            } else {
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar); return; }
                if (bar.low() <= entryPrice - atr * 2.0) { closePosition(bar); return; }
            }
            return;
        }

        // Need recent price extremes
        double recentHigh = 0, recentLow = Double.MAX_VALUE;
        for (int i = Math.max(0, history.size() - 10); i < history.size(); i++) {
            recentHigh = Math.max(recentHigh, history.get(i).high());
            recentLow = Math.min(recentLow, history.get(i).low());
        }

        double pullbackThreshold = atr * 0.5;

        // BUY: price pulled back from recent high, CMF still positive (buying pressure intact)
        if (cmf > 0.1) {
            boolean pulledBack = bar.high() <= recentHigh - pullbackThreshold || 
                                 bar.close() <= recentHigh - pullbackThreshold;
            if (pulledBack && bar.close() > bar.open() && bar.low() >= recentLow) {
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); return;
            }
        }

        // SELL: price rallied from recent low, CMF still negative (selling pressure intact)
        if (cmf < -0.1) {
            boolean rallied = bar.low() >= recentLow + pullbackThreshold ||
                             bar.close() >= recentLow + pullbackThreshold;
            if (rallied && bar.close() < bar.open() && bar.high() <= recentHigh) {
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); return;
            }
        }

        // Strong CMF trend continuation
        if (cmf > 0.2 && bar.close() > bar.open() && bar.high() - bar.low() > atr * 0.6) {
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); return;
        }

        if (cmf < -0.2 && bar.close() < bar.open() && bar.high() - bar.low() > atr * 0.6) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); return;
        }
    }

    private double calculateCMF(int period) {
        int size = history.size();
        if (size < period + 1) return 0;

        double mfvSum = 0;  // money flow volume sum
        double volSum = 0;  // volume (range proxy) sum

        int start = size - period;
        for (int i = start; i < size; i++) {
            Bar b = history.get(i);
            double range = b.high() - b.low();
            if (range == 0) continue;

            // Money Flow Multiplier
            double mfm = ((b.close() - b.low()) - (b.high() - b.close())) / range;
            // Use range as volume proxy
            double volProxy = range * 1000; // scale up for numeric stability
            mfvSum += mfm * volProxy;
            volSum += volProxy;
        }

        return volSum > 0 ? mfvSum / volSum : 0;
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
        history.clear(); pending.clear(); inTrade = false;
        tradeDirection = Order.Side.BUY; entryPrice = 0;
    }
}
