package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import com.martinfou.trading.strategies.StrategyOrderQueues;

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

    private static final double QUANTITY = 1000;

    private final int linRegPeriod = 14;
    private final double priceEntryMult = 2.9;
    private final int exitAfterBars = 28;
    private final int atrPeriod = 50;
    private final int period1 = 50;
    private final double stopLossPips = 165;
    private final double takeProfitPips = 320;
    private final double trailingStopCoef = 3.8;
    private final double trailingActivationPips = 40;
    private static final int PENDING_EXPIRATION_BARS = 14;

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();
    private Order pendingEntry;
    private int barsSincePending;
    private boolean inPosition;
    private double entryPrice;
    private double positionQty;
    private double positionSl;
    private double positionTp;
    private int barsInPosition;
    private boolean trailingActivated;

    @Override
    public String name() {
        return "SQ_2.15.195_LinRegATR_Trailing";
    }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < Math.max(period1, atrPeriod) + linRegPeriod + 10) {
            return;
        }

        String symbol = bar.symbol();
        double pip = Indicators.pipSize(symbol);

        if (pendingEntry != null && pendingEntry.status() == Order.Status.FILLED) {
            inPosition = true;
            entryPrice = pendingEntry.price();
            positionQty = pendingEntry.quantity();
            positionSl = pendingEntry.price() - (stopLossPips * pip);
            positionTp = pendingEntry.price() + (takeProfitPips * pip);
            pendingEntry = null;
            barsInPosition = 0;
            trailingActivated = false;
        }

        if (inPosition) {
            barsInPosition++;
            manageOpenPosition(bar, symbol, pip);
            return;
        }

        if (pendingEntry != null) {
            if (pendingEntry.status() == Order.Status.PENDING) {
                barsSincePending++;
                if (barsSincePending >= PENDING_EXPIRATION_BARS) {
                    pendingOrders.remove(pendingEntry);
                    pendingEntry = null;
                } else {
                    tryReplacePendingEntry(bar, symbol, pip);
                }
                return;
            }
            pendingEntry = null;
        }

        tryPlaceEntry(bar, symbol, pip);
    }

    private void manageOpenPosition(Bar bar, String symbol, double pip) {
        if (barsInPosition >= exitAfterBars) {
            closePosition(symbol);
            return;
        }

        double currentPrice = bar.close();
        double unrealizedPips = (currentPrice - entryPrice) / pip;
        if (!trailingActivated && unrealizedPips >= trailingActivationPips) {
            trailingActivated = true;
        }

        if (trailingActivated) {
            double atrVal = calcATR(history, atrPeriod, 1);
            double trailingDistance = trailingStopCoef * atrVal;
            double newSl = currentPrice - trailingDistance;
            if (newSl > positionSl) {
                positionSl = newSl;
            }
        }

        if (bar.low() <= positionSl || bar.high() >= positionTp) {
            closePosition(symbol);
        }
    }

    private void closePosition(String symbol) {
        pendingOrders.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionQty, 0).closeOnly());
        resetPositionState();
    }

    private void resetPositionState() {
        inPosition = false;
        entryPrice = 0;
        positionQty = 0;
        positionSl = 0;
        positionTp = 0;
        barsInPosition = 0;
        trailingActivated = false;
    }

    private void tryReplacePendingEntry(Bar bar, String symbol, double pip) {
        Optional<EntrySignal> signal = evaluateEntry(symbol, pip);
        if (signal.isEmpty()) {
            return;
        }
        pendingOrders.remove(pendingEntry);
        pendingEntry = placeStopEntry(symbol, pip, signal.get());
    }

    private void tryPlaceEntry(Bar bar, String symbol, double pip) {
        Optional<EntrySignal> signal = evaluateEntry(symbol, pip);
        if (signal.isEmpty()) {
            return;
        }
        pendingEntry = placeStopEntry(symbol, pip, signal.get());
    }

    private Optional<EntrySignal> evaluateEntry(String symbol, double pip) {
        double linReg3 = calcLinReg(history, linRegPeriod, 3);
        double open3 = getBar(history, 3).open();
        double open2 = getBar(history, 2).open();
        if (!(open3 < linReg3 && open2 > linReg3)) {
            return Optional.empty();
        }
        double lowestLow = calcLowestLow(history, period1, 1);
        double atrVal = calcATR(history, atrPeriod, 2);
        double ep = lowestLow + (priceEntryMult * atrVal);
        double sl = ep - (stopLossPips * pip);
        double tp = ep + (takeProfitPips * pip);
        return Optional.of(new EntrySignal(ep, sl, tp));
    }

    private Order placeStopEntry(String symbol, double pip, EntrySignal signal) {
        // Exits are strategy-managed (trailing + time stop); avoid engine SL/TP double-close.
        Order order = new Order(symbol, Order.Side.BUY, Order.Type.STOP, QUANTITY, signal.entryPrice());
        pendingOrders.add(order);
        barsSincePending = 0;
        return order;
    }

    private record EntrySignal(double entryPrice, double stopLoss, double takeProfit) {}

    @Override
    public void onTick(double bid, double ask, long volume) {
        // Not used
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
        resetPositionState();
    }

    private Bar getBar(List<Bar> bars, int shift) {
        return bars.get(bars.size() - 1 - shift);
    }

    private double calcATR(List<Bar> bars, int period, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period;
        if (start < 0) {
            return 0;
        }
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
        if (start < 0) {
            return bars.get(bars.size() - 1).close();
        }

        double sumX = 0;
        double sumY = 0;
        double sumXY = 0;
        double sumX2 = 0;
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
        return intercept + slope * (n - 1);
    }

    private double calcLowestLow(List<Bar> bars, int period, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period;
        if (start < 0) {
            start = 0;
        }
        double lowest = Double.MAX_VALUE;
        for (int i = start; i <= end; i++) {
            double low = bars.get(i).low();
            if (low < lowest) {
                lowest = low;
            }
        }
        return lowest;
    }
}
