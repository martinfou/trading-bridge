package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.*;
import com.martinfou.trading.strategies.MarketAnalyzer;
import java.util.*;

/**
 * Adapted from StrategyQuant 2.15.195
 * Backtested: GBPJPY H1, 2003-2024
 * Family: LinReg Cross + ATR entry
 *
 * Signal: LinReg(14) crossover — Open crosses above LinReg(CLOSE,14)
 * Entry: LowestLow(50) + 2.9 × ATR(50)
 * Exit: SL=165 pips, TP=320 pips, Trailing 3.8×ATR after 40 pip profit
 * Expiration: 14 bars | ExitAfterBars: 28 | Long only, BUYSTOP
 * R:R = 1:1.94
 */
public class Strategy_2_15_195_Adapted implements Strategy {

    private static final double PIP_JPY = 0.01;
    private static final String SYMBOL = "GBP_JPY";
    private static final double QUANTITY = 1000;

    // SQ parameters
    private final int linRegPeriod = 14;
    private final double priceEntryMult = 2.9;
    private final int exitAfterBars = 28;
    private final int atrPeriod = 50;
    private final int period1 = 50; // for lowestLow
    private final double stopLossPips = 165;
    private final double takeProfitPips = 320;
    private final double trailingStopCoef = 3.8;
    private final double trailingActivationPips = 40;
    private final int signalExpirationBars = 14;

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();
    private Order activeOrder = null;
    private int barsSinceEntry = 0;
    private double entryPrice = 0;
    private boolean entryTriggered = false;
    private boolean trailingActivated = false;

    @Override
    public String name() {
        return "SQ_2.15.195_LinRegATR_Trailing";
    }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < Math.max(period1, atrPeriod) + linRegPeriod + 10) return;

        // Manage active order
        if (activeOrder != null) {
            barsSinceEntry++;

            // Exit after bars
            if (barsSinceEntry >= exitAfterBars) {
                pendingOrders.remove(activeOrder);
                activeOrder = null;
                entryTriggered = false;
                trailingActivated = false;
                return;
            }

            // If order is still PENDING, allow re-evaluation of entry signals
            if (activeOrder.status() == Order.Status.PENDING) {
                checkEntry(bar);
                return;
            }

            // Trailing stop (3.8 × ATR after 40 pip profit)
            double currentPrice = bar.close();
            double unrealizedPips = (currentPrice - entryPrice) / PIP_JPY;

            if (!trailingActivated && unrealizedPips >= trailingActivationPips) {
                trailingActivated = true;
            }

            if (trailingActivated) {
                double atrVal = calcATR(history, atrPeriod, 1);
                double trailingDistance = trailingStopCoef * atrVal;
                double newSl = currentPrice - trailingDistance;

                // Only move SL up, never down
                if (newSl > activeOrder.stopLoss()) {
                    Order updatedOrder = new Order(SYMBOL, activeOrder.side(), activeOrder.type(),
                            activeOrder.quantity(), activeOrder.price())
                            .withStopLoss(newSl)
                            .withTakeProfit(activeOrder.takeProfit());
                    pendingOrders.remove(activeOrder);
                    pendingOrders.add(updatedOrder);
                    activeOrder = updatedOrder;
                }
            }

            return;
        }

        if (entryTriggered) return;

        checkEntry(bar);
    }

    /** Evaluate entry conditions and place order if signal fires. */
    private void checkEntry(Bar bar) {

        // LinReg crossover signal
        if (history.size() < Math.max(period1, atrPeriod) + linRegPeriod + 10) return;
        double linReg3 = calcLinReg(history, linRegPeriod, 3); // shift 3
        double open3 = getBar(history, 3).open();
        double open2 = getBar(history, 2).open();

        boolean entrySignal = (open3 < linReg3) && (open2 > linReg3);

        if (!entrySignal) return;

        // Entry price: LowestLow(50) + 2.9 × ATR(50)
        double lowestLow = calcLowestLow(history, period1, 1);
        double atrVal = calcATR(history, atrPeriod, 2);
        entryPrice = lowestLow + (priceEntryMult * atrVal);

        double sl = entryPrice - (stopLossPips * PIP_JPY);
        double tp = entryPrice + (takeProfitPips * PIP_JPY);

        // Cancel any existing pending order before placing a new one
        if (activeOrder != null && activeOrder.status() == Order.Status.PENDING) {
            pendingOrders.remove(activeOrder);
        }

        Order order = new Order(SYMBOL, Order.Side.BUY, Order.Type.STOP, QUANTITY, entryPrice)
                .withStopLoss(sl)
                .withTakeProfit(tp);
        pendingOrders.add(order);
        activeOrder = order;
        entryTriggered = true;
        barsSinceEntry = 0;
        trailingActivated = false;
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
        // Not used
    }

    @Override
    public List<Order> getPendingOrders() {
        pendingOrders.removeIf(o -> o.status() != Order.Status.PENDING);
        // If the filled order was removed (position closed), allow re-entry
        if (activeOrder != null && !pendingOrders.contains(activeOrder)) {
            activeOrder = null;
            entryTriggered = false;
            barsSinceEntry = 0;
            trailingActivated = false;
        }
        return pendingOrders;
    }

    @Override
    public void reset() {
        history.clear();
        pendingOrders.clear();
        activeOrder = null;
        barsSinceEntry = 0;
        entryPrice = 0;
        entryTriggered = false;
        trailingActivated = false;
    }

    // --- Indicator helpers ---

    private Bar getBar(List<Bar> bars, int shift) {
        return bars.get(bars.size() - 1 - shift);
    }

    private double calcATR(List<Bar> bars, int period, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period;
        if (start < 0) return 0;
        double sum = 0;
        for (int i = start; i < end; i++) {
            Bar b = bars.get(i);
            sum += b.high() - b.low();
        }
        return sum / period;
    }

    private double calcLinReg(List<Bar> bars, int period, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period;
        if (start < 0) return bars.get(bars.size() - 1).close();

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        int n = period;
        for (int i = 0; i < n; i++) {
            double y = bars.get(end - i).close();
            double x = i;
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        double intercept = (sumY - slope * sumX) / n;
        // Return the value at the last position (x = n-1)
        return intercept + slope * (n - 1);
    }

    private double calcLowestLow(List<Bar> bars, int period, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period;
        if (start < 0) start = 0;
        double lowest = Double.MAX_VALUE;
        for (int i = start; i <= end; i++) {
            double low = bars.get(i).low();
            if (low < lowest) lowest = low;
        }
        return lowest;
    }
}
