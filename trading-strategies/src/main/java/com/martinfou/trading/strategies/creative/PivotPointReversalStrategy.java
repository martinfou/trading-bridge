package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Pivot Point Reversal Strategy — Mean Reversion + Support/Resistance
 *
 * 📊 Data insight: Daily pivot points (P = H + L + C / 3) act as key
 *    support/resistance levels due to their widespread use by institutional
 *    traders. S1 (2×P - H) and R1 (2×P - L) are the most active levels.
 *    Price tends to bounce off these levels, especially when RSI confirms
 *    oversold/overbought conditions.
 *
 * 🔧 Mechanism: Trade bounces from daily pivot levels.
 *    - Compute daily pivot P, R1, S1 using previous day's H, L, C
 *    - Price reaches R1 → SELL (resistance bounce), confirm with RSI > 60
 *    - Price reaches S1 → BUY (support bounce), confirm with RSI < 40
 *    - Target: pivot P (middle). Stop: beyond R1/S1 + ATR buffer
 *    - Only trade within 24 hours of the pivot calculation
 *
 * 🎯 Originality: Applies classic floor-trader pivot technique to H1 FX.
 *    Unlike most pivot strategies that use fixed pip buffers, this adapts
 *    buffer sizes to each asset's ATR, making it multi-asset compatible.
 */
public class PivotPointReversalStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double targetPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 1000;

    // Pivot levels for current day
    private int currentDay = -1;
    private double pivotP = 0;
    private double pivotR1 = 0;
    private double pivotS1 = 0;
    private double prevDayHigh = 0;
    private double prevDayLow = Double.MAX_VALUE;
    private double prevDayClose = 0;

    public PivotPointReversalStrategy() {
        this("PivotPointReversal", "GBP/JPY");
    }

    public PivotPointReversalStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public PivotPointReversalStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 25) return;

        // Track daily pivot levels
        updateDailyPivots(bar);

        double atr = calculateATR(14);

        // Manage active trade
        if (inTrade) {
            barsHeld++;

            if (barsHeld >= 5) { closePosition(bar); return; }

            if (tradeDirection == Order.Side.BUY) {
                if (bar.high() >= targetPrice) { closePosition(bar); return; }
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar); return; }
            } else {
                if (bar.low() <= targetPrice) { closePosition(bar); return; }
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar); return; }
            }
            return;
        }

        if (pivotR1 == 0 && pivotS1 == 0) return;

        // RSI for confirmation
        double rsi = calculateRSI(14);

        // R1 resistance touch → SELL
        double r1Buffer = atr * 0.3;
        if (bar.high() >= pivotR1 - r1Buffer && bar.high() <= pivotR1 + r1Buffer
            && rsi > 55 && bar.close() < pivotR1) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.SELL;
            entryPrice = bar.close();
            targetPrice = pivotP; // Target: pivot
            barsHeld = 0;
            return;
        }

        // S1 support touch → BUY
        double s1Buffer = atr * 0.3;
        if (bar.low() <= pivotS1 + s1Buffer && bar.low() >= pivotS1 - s1Buffer
            && rsi < 45 && bar.close() > pivotS1) {
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.BUY;
            entryPrice = bar.close();
            targetPrice = pivotP; // Target: pivot
            barsHeld = 0;
        }
    }

    private void updateDailyPivots(Bar bar) {
        long epochDay = bar.timestamp().getEpochSecond() / 86400;
        if (epochDay == currentDay) return;

        if (currentDay >= 0) {
            // Calculate pivot from previous day's data
            pivotP = (prevDayHigh + prevDayLow + prevDayClose) / 3;
            pivotR1 = 2 * pivotP - prevDayLow;
            pivotS1 = 2 * pivotP - prevDayHigh;
        }

        currentDay = (int) epochDay;
        prevDayHigh = bar.high();
        prevDayLow = bar.low();
        prevDayClose = bar.close();
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()).closeOnly());
        inTrade = false;
    }

    private double calculateRSI(int period) {
        if (history.size() < period + 1) return 50;
        double gain = 0, loss = 0;
        for (int i = history.size() - period; i < history.size(); i++) {
            double diff = history.get(i).close() - history.get(i - 1).close();
            if (diff > 0) gain += diff; else loss -= diff;
        }
        double avgGain = gain / period, avgLoss = loss / period;
        if (avgLoss == 0) return 100;
        return 100 - (100 / (1 + avgGain / avgLoss));
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
        tradeDirection = Order.Side.BUY; entryPrice = 0; targetPrice = 0; barsHeld = 0;
        currentDay = -1; pivotP = 0; pivotR1 = 0; pivotS1 = 0;
        prevDayHigh = 0; prevDayLow = Double.MAX_VALUE; prevDayClose = 0;
    }
}
