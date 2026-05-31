package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.*;
import com.martinfou.trading.strategies.StrategyOrderQueues;
import java.util.*;

/**
 * Strategy 2.32.120 — R/R 3.1, PT 390
 * Signal: Vortex(20) crossover (bar 2 vs bar 3)
 * Entry: BUYSTOP at (Highest(MEDIAN_PRICE, 14, 2) + 1.4 * BiggestRange(30, 3))
 * SL: 125 pips, PT: 390 pips
 * Expiration: 101 bars
 */
public class Strategy_2_32_120_Converted implements Strategy {

    // ---- JForex Conversion Parameters ----
    private static final double PIP = 0.01; // JPY pair default
    private static final String SYMBOL = "GBP_JPY";
    private static final double QUANTITY = 1000;

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();
    private Order activeOrder = null;
    private int barsSinceEntry = 0;
    private boolean entryTriggered = false;
    private int expirationBars = 0;
    private int MIN_BARS = 50;

    @Override
    public String name() {
        return "Strategy_2_32_120_Converted";
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
        // Not used for bar-based strategy
    }

    @Override
    public List<Order> getPendingOrders() {
        pendingOrders.removeIf(o -> o.status() != Order.Status.PENDING);
        return StrategyOrderQueues.drainPending(pendingOrders);
    }

    @Override
    public void reset() {
        history.clear();
        pendingOrders.clear();
        activeOrder = null;
        barsSinceEntry = 0;
        entryTriggered = false;
        expirationBars = 0;
    }

    // ---- Indicator helpers ----

    private Bar getBar(int shift) {
        return history.get(history.size() - 1 - shift);
    }

    private double calcSMA(List<Bar> bars, int period, String priceType, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 0) return 0;
        double sum = 0;
        for (int i = start; i <= end; i++) {
            sum += getPrice(bars.get(i), priceType);
        }
        return sum / period;
    }

    private double calcBBRange(List<Bar> bars, int period, double mult, String priceType, int shift) {
        double sma = calcSMA(bars, period, priceType, shift);
        if (sma == 0) return 0;
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 0) return 0;
        double variance = 0;
        for (int i = start; i <= end; i++) {
            double diff = getPrice(bars.get(i), priceType) - sma;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / period);
        return 2.0 * mult * stdDev;
    }

    private double calcLinReg(List<Bar> bars, int period, String priceType, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 0) return 0;
        double sumX = 0, sumY = 0;
        int n = period;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += getPrice(bars.get(start + i), priceType);
        }
        double meanX = sumX / n;
        double meanY = sumY / n;
        double covariance = 0, varianceX = 0;
        for (int i = 0; i < n; i++) {
            double xi = i;
            double yi = getPrice(bars.get(start + i), priceType);
            covariance += (xi - meanX) * (yi - meanY);
            varianceX += (xi - meanX) * (xi - meanX);
        }
        if (varianceX == 0) return meanY;
        double slope = covariance / varianceX;
        double intercept = meanY - slope * meanX;
        return intercept + slope * (n - 1);
    }

    private double calcBiggestRange(List<Bar> bars, int period, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 0) return 0;
        double maxRange = 0;
        for (int i = start; i <= end; i++) {
            double range = bars.get(i).high() - bars.get(i).low();
            if (range > maxRange) maxRange = range;
        }
        return maxRange;
    }

    private double calcATR(List<Bar> bars, int period, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 1) return 0;
        double sumTr = 0;
        for (int i = start; i <= end; i++) {
            double tr1 = bars.get(i).high() - bars.get(i).low();
            double tr2 = Math.abs(bars.get(i).high() - bars.get(i - 1).close());
            double tr3 = Math.abs(bars.get(i).low() - bars.get(i - 1).close());
            sumTr += Math.max(tr1, Math.max(tr2, tr3));
        }
        return sumTr / period;
    }

    private double calcVortexPlus(List<Bar> bars, int period, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 1) return 0;
        double sumVmPlus = 0, sumTr = 0;
        for (int i = start; i <= end; i++) {
            double tr1 = bars.get(i).high() - bars.get(i).low();
            double tr2 = Math.abs(bars.get(i).high() - bars.get(i - 1).close());
            double tr3 = Math.abs(bars.get(i).low() - bars.get(i - 1).close());
            double tr = Math.max(tr1, Math.max(tr2, tr3));
            double vmPlus = Math.abs(bars.get(i).high() - bars.get(i - 1).low());
            sumVmPlus += vmPlus;
            sumTr += tr;
        }
        return sumTr > 0 ? sumVmPlus / sumTr : 0;
    }

    private double calcVortexMinus(List<Bar> bars, int period, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 1) return 0;
        double sumVmMinus = 0, sumTr = 0;
        for (int i = start; i <= end; i++) {
            double tr1 = bars.get(i).high() - bars.get(i).low();
            double tr2 = Math.abs(bars.get(i).high() - bars.get(i - 1).close());
            double tr3 = Math.abs(bars.get(i).low() - bars.get(i - 1).close());
            double tr = Math.max(tr1, Math.max(tr2, tr3));
            double vmMinus = Math.abs(bars.get(i).low() - bars.get(i - 1).high());
            sumVmMinus += vmMinus;
            sumTr += tr;
        }
        return sumTr > 0 ? sumVmMinus / sumTr : 0;
    }

    private double calcLWMA(List<Bar> bars, int period, String priceType, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 0) return 0;
        double sumWeightedPrice = 0, sumWeight = 0;
        int weight = 1;
        for (int i = start; i <= end; i++) {
            double price = getPrice(bars.get(i), priceType);
            sumWeightedPrice += price * weight;
            sumWeight += weight;
            weight++;
        }
        return sumWeightedPrice / sumWeight;
    }

    private double getPrice(Bar bar, String type) {
        return switch (type.toLowerCase()) {
            case "open" -> bar.open();
            case "high" -> bar.high();
            case "low" -> bar.low();
            case "close" -> bar.close();
            case "median" -> (bar.high() + bar.low()) / 2.0;
            case "typical" -> (bar.high() + bar.low() + bar.close()) / 3.0;
            default -> bar.close();
        };
    }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < MIN_BARS) return;

        // Manage active order / position
        if (activeOrder != null) {
            barsSinceEntry++;
            if (expirationBars > 0 && barsSinceEntry >= expirationBars) {
                pendingOrders.remove(activeOrder);
                activeOrder = null;
            }
            return;
        }

        if (entryTriggered) return;

        // LongEntrySignal:
                // Vortex(20,0,2) > Vortex(20,1,2) && Vortex(20,0,3) < Vortex(20,1,3)
                double v2_plus = calcVortexPlus(history, 20, 2); double v2_minus = calcVortexMinus(history, 20, 2);
                double v3_plus = calcVortexPlus(history, 20, 3); double v3_minus = calcVortexMinus(history, 20, 3);
        
                if (Double.isNaN(v2_plus) || Double.isNaN(v2_minus) ||
                    Double.isNaN(v3_plus) || Double.isNaN(v3_minus)) return;
        
                boolean longEntry = (v2_plus > v2_minus) && 
                                   (v3_plus < v3_minus);
        
                if (longEntry && activeOrder == null) {
                    // Entry: Highest(MEDIAN_PRICE, 14, 2) + 1.4 * BiggestRange(30, 3)
                    double highest = 0;
                    for (int i = (history.size() - 1) - 2 - 14 + 1; i <= (history.size() - 1) - 2; i++) {
                        double median = (history.get(i).high() + history.get(i).low()) / 2.0;
                        if (median > highest) highest = median;
                    }
        
                    double biggestRange = calcBiggestRange(history, 30, 3);
                    if (Double.isNaN(biggestRange)) return;
        
                    double entryPrice = highest + (1.4 * biggestRange);
                    {
                    double ep = entryPrice;
                    double sl = ep - (125 * PIP);
                    double tp = ep + (390 * PIP);
                    Order order = new Order(SYMBOL, Order.Side.BUY, Order.Type.STOP, QUANTITY, ep)
                        .withStopLoss(sl)
                        .withTakeProfit(tp);
                    pendingOrders.add(order);
                    activeOrder = order;
                    entryTriggered = true;
                    barsSinceEntry = 0;
                    expirationBars = 101;
                };
                }
    }

}

