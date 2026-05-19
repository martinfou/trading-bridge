package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.*;
import java.util.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Strategy 2.31.177 — Meilleur R/R (3.1)
 * Signal: Open croise au-dessus de LinReg(40)
 * Entry: BUYSTOP at (Lower Bollinger(10,2) + 1.0 * BBRange(20,2))
 * SL: 95 pips, PT: 290 pips
 * Trailing: 70 pips (activation à 100)
 * Expiration: 168 bars
 */
public class Strategy_2_31_177_Converted implements Strategy {

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
        return "Strategy_2_31_177_Converted";
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
        // Not used for bar-based strategy
    }

    @Override
    public List<Order> getPendingOrders() {
        pendingOrders.removeIf(o -> o.status() != Order.Status.PENDING);
        return pendingOrders;
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

    private final String name = "2.31.177";

    @Override
    public String getName() { return name; }

    @Override
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

        // besoin d'au moins ~45 barres pour les indicateurs
        
                // LongEntrySignal de la version JForex:
                // (Open[2+1] < LinReg(40, CLOSE, 2+1) && Open[2] > LinReg(40, CLOSE, 2+1))
                // shift 2+1 = index-3, shift 2 = index-2
                double open3 = getBar(3).open();
                double linReg3 = calcLinReg(history, 40, "close", 3);
                double open2 = getBar(2).open();
                double linReg2 = calcLinReg(history, 40, "close", 3);
        
                if (Double.isNaN(linReg3) || Double.isNaN(linReg2)) return;
        
                boolean longEntry = (open3 < linReg3) && (open2 > linReg2);
        
                if (longEntry && !(activeOrder != null) && !(false)) {
                    // Entry: Bollinger(10, 2, 0, CLOSE, 3)[lower] + (1.0 * BBRange(20, 2, CLOSE, 2))
                    double sma = calcSMA(history, 10, "close", 3);
                    double bbrange = calcBBRange(history, 20, 2.0, "close", 2);
                    
                    if (Double.isNaN(sma) || Double.isNaN(bbrange)) return;
                    
                    // lower band = sma - mult * stddev = sma - bbrange/2
                    double lowerBand = sma - (bbrange / 2.0);
                    double entryPrice = lowerBand + (1.0 * bbrange);
        
                    {
                    double ep = entryPrice;
                    double sl = ep - (95 * PIP);
                    double tp = ep + (290 * PIP);
                    Order order = new Order(SYMBOL, Order.Side.BUY, Order.Type.STOP, QUANTITY, ep)
                        .withStopLoss(sl)
                        .withTakeProfit(tp);
                    pendingOrders.add(order);
                    activeOrder = order;
                    entryTriggered = true;
                    barsSinceEntry = 0;
                    expirationBars = 168;
                };
                }
    }

}

