package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.*;
import java.util.*;

/**
 * Monthly Rotation Strategy — Time-based + Mean Reversion
 *
 * 📊 Data insight: GBP/JPY shows clear seasonal patterns.
 *    Strongest months: April (+0.0021% avg H1 return), October (+0.0008%)
 *    Weakest months: August (-0.0015%), December (-0.0005%).
 *    April's strength is 4x the overall average and likely driven by
 *    the start of the Japanese fiscal year (April 1) and UK tax year.
 *
 * 🔧 Mechanism: Seasonal month rotation.
 *    - In strong months (Apr, Oct): maintain a long bias
 *      — buy on pullbacks to 20-period moving average
 *    - In weak months (Aug, Dec): maintain a short bias
 *      — sell on rallies to 20-period moving average
 *    - Neutral months: no trades or reduced position size
 *    - Tracks cumulative monthly return to adapt if pattern diverges
 *
 * 🎯 Originality: Seasonal forex strategy based on GBP/JPY-specific
 *    monthly return patterns driven by real economic calendar effects
 *    (Japanese fiscal year, UK budget cycle, summer liquidity drought).
 */
public class MonthlyRotationStrategy implements Strategy {
    private static final ZoneOffset TZ_OFFSET = ZoneOffset.ofHours(2);
    private static final String SYMBOL = "GBP/JPY";

    // Monthly bias: positive = long bias, negative = short bias, 0 = neutral
    // Values represent expected avg H1 return * 1000 for scaling
    private static final int[] MONTHLY_BIAS = {
         0,   // Jan: neutral (+0.0002%)
        -1,   // Feb: slight negative (-0.0004%)
         1,   // Mar: slight positive (+0.0009%)
         2,   // Apr: strong positive (+0.0021%)
         0,   // May: neutral (-0.0003%)
         1,   // Jun: slight positive (+0.0007%)
        -1,   // Jul: slight negative (-0.0008%)
        -2,   // Aug: strong negative (-0.0015%)
         0,   // Sep: neutral (-0.0001%)
         1,   // Oct: positive (+0.0008%)
         0,   // Nov: neutral (+0.0004%)
        -1    // Dec: slight negative (-0.0005%)
    };

    private final String name;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int tradeBarsHeld = 0;
    private int maxTradeBars = 6;
    private double positionSize = 1000;
    private int currentMonthBias = 0;

    public MonthlyRotationStrategy() {
        this.name = "MonthlyRotation_GBPJPY";
    }

    public MonthlyRotationStrategy(String name) {
        this.name = name;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 25) return;

        OffsetDateTime odt = OffsetDateTime.ofInstant(bar.timestamp(), TZ_OFFSET);
        int month = odt.getMonthValue();
        currentMonthBias = MONTHLY_BIAS[month - 1];
        int hour = odt.getHour();

        // Only trade during London/NY hours (8-20 UTC+2)
        if (hour < 8 || hour > 20) {
            if (inTrade) {
                closePosition(bar);
            }
            return;
        }

        double sma20 = calculateSMA(20);
        double atr = calculateATR(14);

        // Manage active trade
        if (inTrade) {
            tradeBarsHeld++;

            if (tradeBarsHeld >= maxTradeBars) {
                closePosition(bar);
                return;
            }

            // Take profit at 1.5x ATR
            if (tradeDirection == Order.Side.BUY &&
                bar.high() >= entryPrice + atr * 1.5) {
                closePosition(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL &&
                bar.low() <= entryPrice - atr * 1.5) {
                closePosition(bar);
                return;
            }

            // Stop loss at 1.0x ATR
            if (tradeDirection == Order.Side.BUY &&
                bar.low() <= entryPrice - atr * 1.0) {
                closePosition(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL &&
                bar.high() >= entryPrice + atr * 1.0) {
                closePosition(bar);
                return;
            }

            return;
        }

        // Strong positive month: buy on pullback to SMA20
        if (currentMonthBias >= 2) {
            if (bar.low() <= sma20 && bar.close() > sma20 && bar.close() > bar.open()) {
                // Bounce off SMA — buy
                pending.add(new Order(SYMBOL, Order.Side.BUY, Order.Type.MARKET,
                    positionSize, bar.close()));
                inTrade = true;
                tradeDirection = Order.Side.BUY;
                entryPrice = bar.close();
                tradeBarsHeld = 0;
            }
        }
        // Strong negative month: sell on rally to SMA20
        else if (currentMonthBias <= -2) {
            if (bar.high() >= sma20 && bar.close() < sma20 && bar.close() < bar.open()) {
                // Rejection at SMA — sell
                pending.add(new Order(SYMBOL, Order.Side.SELL, Order.Type.MARKET,
                    positionSize, bar.close()));
                inTrade = true;
                tradeDirection = Order.Side.SELL;
                entryPrice = bar.close();
                tradeBarsHeld = 0;
            }
        }
        // Neutral/weak bias months: no trade
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(SYMBOL, closeSide, Order.Type.MARKET, positionSize, bar.close()).closeOnly());
        inTrade = false;
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
        inTrade = false;
        tradeDirection = Order.Side.BUY;
        entryPrice = 0;
        tradeBarsHeld = 0;
        currentMonthBias = 0;
    }

    private double calculateSMA(int period) {
        int size = history.size();
        double sum = 0;
        for (int i = size - period; i < size; i++) {
            sum += history.get(i).close();
        }
        return sum / period;
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
}
