package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import com.martinfou.trading.strategies.StrategyOrderQueues;

import java.util.*;

/**
 * Strategy 2.38.112 — R/R 2.9, SL 110
 * Signal: Vortex(12) crossover (bar 3 vs bar 4)
 * Entry: BUYSTOP at (LWMA(40, LOW, 3) + 1.6 * BiggestRange(50, 3))
 * SL: 110 pips, PT: 320 pips
 * Expiration: 133 bars
 */
public class Strategy_2_38_112_Converted implements Strategy {

    private static final double QUANTITY = 1000;
    private static final double STOP_LOSS_PIPS = 110;
    private static final double TAKE_PROFIT_PIPS = 320;
    private static final int PENDING_EXPIRATION_BARS = 133;

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();
    private Order pendingEntry;
    private int barsSincePending;
    private boolean inPosition;
    private double positionSl;
    private double positionTp;
    private static final int MIN_BARS = 50;

    @Override
    public String name() {
        return "Strategy_2_38_112_Converted";
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
        pendingEntry = null;
        barsSincePending = 0;
        inPosition = false;
        positionSl = 0;
        positionTp = 0;
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
        if (history.size() < MIN_BARS) {
            return;
        }

        if (inPosition) {
            if (bar.low() <= positionSl || bar.high() >= positionTp) {
                inPosition = false;
            }
            return;
        }

        if (pendingEntry != null) {
            if (pendingEntry.status() == Order.Status.FILLED) {
                inPosition = true;
                positionSl = pendingEntry.stopLoss();
                positionTp = pendingEntry.takeProfit();
                pendingEntry = null;
                return;
            }
            if (pendingEntry.status() == Order.Status.PENDING) {
                barsSincePending++;
                if (barsSincePending >= PENDING_EXPIRATION_BARS) {
                    pendingOrders.remove(pendingEntry);
                    pendingEntry = null;
                }
                return;
            }
            pendingEntry = null;
        }

        // LongEntrySignal: Vortex(12) crossover (bar 3 vs bar 4)
        double v3Plus = calcVortexPlus(history, 12, 3);
        double v3Minus = calcVortexMinus(history, 12, 3);
        double v4Plus = calcVortexPlus(history, 12, 4);
        double v4Minus = calcVortexMinus(history, 12, 4);

        if (Double.isNaN(v3Plus) || Double.isNaN(v3Minus) || Double.isNaN(v4Plus) || Double.isNaN(v4Minus)) {
            return;
        }

        boolean longEntry = (v3Plus > v3Minus) && (v4Plus < v4Minus);
        if (!longEntry) {
            return;
        }

        double lwma = calcLWMA(history, 40, "low", 3);
        double biggestRange = calcBiggestRange(history, 50, 3);
        if (Double.isNaN(lwma) || Double.isNaN(biggestRange)) {
            return;
        }

        String symbol = bar.symbol();
        double pip = Indicators.pipSize(symbol);
        double entryPrice = lwma + (1.6 * biggestRange);
        double sl = entryPrice - (STOP_LOSS_PIPS * pip);
        double tp = entryPrice + (TAKE_PROFIT_PIPS * pip);

        Order order = new Order(symbol, Order.Side.BUY, Order.Type.STOP, QUANTITY, entryPrice)
            .withStopLoss(sl)
            .withTakeProfit(tp);
        pendingOrders.add(order);
        pendingEntry = order;
        barsSincePending = 0;
    }

}

