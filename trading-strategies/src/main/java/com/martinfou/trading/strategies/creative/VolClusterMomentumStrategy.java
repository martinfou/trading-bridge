package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Volatility Cluster Momentum Strategy — Custom Indicator + Volatility
 *
 * 📊 Data insight: GBP/JPY H1 returns have near-zero autocorrelation
 *    (efficient market), BUT absolute returns show strong clustering:
 *    lag-1 = 0.394, lag-5 = 0.303, lag-20 = 0.179. This means high-vol
 *    begets high-vol and low-vol begets low-vol — the "volatility regime"
 *    persists for 20+ bars.
 *
 * 🔧 Mechanism: Regime-based momentum with custom volatility indicator.
 *    - Calculate VolRatio = current ATR(5) / median ATR(20)
 *    - HIGH regime (VolRatio > 1.4): momentum is more reliable;
 *      trade in direction of breakout of 1-bar range
 *    - LOW regime (VolRatio < 0.7): fading works better;
 *      trade counter-trend after 2 consecutive bars
 *    - NEUTRAL regime: no trade
 *
 * 🎯 Originality: Uses the volatility clustering property as a regime
 *    filter, changing trading logic based on the volatility regime.
 *    Most strategies use fixed logic regardless of volatility background.
 */
public class VolClusterMomentumStrategy implements Strategy {
    private static final String SYMBOL = "GBP/JPY";

    private final String name;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private final List<Double> atr5History = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int tradeBarsHeld = 0;
    private int maxTradeBars = 4;
    private double positionSize = 10000;

    public VolClusterMomentumStrategy() {
        this.name = "VolClusterMomentum_GBPJPY";
    }

    public VolClusterMomentumStrategy(String name) {
        this.name = name;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 25) return;

        // Manage active trade
        if (inTrade) {
            tradeBarsHeld++;
            double atr5 = calculateATR(5);

            // Exit after max bars
            if (tradeBarsHeld >= maxTradeBars) {
                closePosition(bar);
                return;
            }

            // Profit target: 1.5x ATR
            if (tradeDirection == Order.Side.BUY &&
                bar.high() >= entryPrice + atr5 * 1.5) {
                closePosition(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL &&
                bar.low() <= entryPrice - atr5 * 1.5) {
                closePosition(bar);
                return;
            }

            // Stop loss: 0.8x ATR
            if (tradeDirection == Order.Side.BUY &&
                bar.low() <= entryPrice - atr5 * 0.8) {
                closePosition(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL &&
                bar.high() >= entryPrice + atr5 * 0.8) {
                closePosition(bar);
                return;
            }

            // In HIGH vol regime, trail stop
            double volRatio = calculateVolRatio();
            if (volRatio > 1.4) {
                double trailStop = atr5 * 0.6;
                if (tradeDirection == Order.Side.BUY &&
                    bar.high() > entryPrice + trailStop &&
                    bar.low() <= bar.high() - trailStop * 2) {
                    // Trail the stop up
                    double newStop = bar.high() - trailStop;
                    if (bar.low() <= newStop && bar.close() < newStop) {
                        closePosition(bar);
                        return;
                    }
                }
            }

            return; // Still in trade, don't look for new entries
        }

        // Calculate volatility regime
        double volRatio = calculateVolRatio();

        // Need previous bar for comparison
        if (history.size() < 2) return;
        Bar prev = history.get(history.size() - 2);

        if (volRatio > 1.4) {
            // HIGH VOL regime: trade momentum (breakout of prev bar range)
            double avgRange = calculateAverageRange(10);
            if (bar.high() > prev.high() && bar.close() > bar.open()) {
                // Bullish breakout
                if ((bar.high() - bar.low()) > avgRange * 0.8) {
                    enterTrade(Order.Side.BUY, bar.close());
                }
            }
        } else if (volRatio < 0.7) {
            // LOW VOL regime: trade mean reversion
            // Check for 2 consecutive same-direction bars
            if (history.size() >= 3) {
                Bar prev2 = history.get(history.size() - 3);
                boolean twoBearBars = prev2.close() < prev2.open() &&
                                     prev.close() < prev.open();
                boolean twoBullBars = prev2.close() > prev2.open() &&
                                     prev.close() > prev.open();

                if (twoBullBars && bar.close() < bar.open()) {
                    enterTrade(Order.Side.SELL, bar.close());
                } else if (twoBearBars && bar.close() > bar.open()) {
                    enterTrade(Order.Side.BUY, bar.close());
                }
            }
        }
        // NEUTRAL regime: no trade
    }

    private void enterTrade(Order.Side side, double price) {
        Order order = new Order(SYMBOL, side, Order.Type.MARKET, positionSize, price);
        pending.add(order);
        inTrade = true;
        tradeDirection = side;
        entryPrice = price;
        tradeBarsHeld = 0;
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(SYMBOL, closeSide, Order.Type.MARKET, positionSize, bar.close()));
        inTrade = false;
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

    private double calculateVolRatio() {
        if (history.size() < 25) return 1.0;
        double atr5 = calculateATR(5);
        double[] atr20s = new double[Math.min(20, history.size() - 5)];
        for (int i = 0; i < atr20s.length; i++) {
            double sum = 0;
            for (int j = 0; j < 5; j++) {
                int idx = history.size() - 5 - i - j;
                if (idx >= 1 && idx < history.size()) {
                    Bar prev = history.get(idx - 1);
                    Bar curr = history.get(idx);
                    double tr = Math.max(curr.high() - curr.low(),
                        Math.max(Math.abs(curr.high() - prev.close()),
                                 Math.abs(curr.low() - prev.close())));
                    sum += tr;
                }
            }
            atr20s[i] = sum / 5.0;
        }
        double median = 0;
        if (atr20s.length > 0) {
            Arrays.sort(atr20s);
            median = atr20s[atr20s.length / 2];
        }
        return median > 0 ? atr5 / median : 1.0;
    }

    private double calculateAverageRange(int period) {
        int size = history.size();
        double sum = 0;
        int count = 0;
        for (int i = Math.max(0, size - period); i < size; i++) {
            sum += (history.get(i).high() - history.get(i).low());
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
        atr5History.clear();
        inTrade = false;
        tradeDirection = Order.Side.BUY;
        entryPrice = 0;
        tradeBarsHeld = 0;
    }
}
