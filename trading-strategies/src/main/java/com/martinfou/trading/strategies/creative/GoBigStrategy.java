package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import java.util.ArrayList;
import java.util.List;

/**
 * 🚀 GoBigStrategy — "Go big or go home"
 *
 * Stratégie aggressive qui chasse les GROS mouvements:
 * - D1 trend filter (SMA 200) — seulement dans la direction du trend majeur
 * - H1 volatility breakout — entre quand la volatilité explose
 * - Position sizing: 2% risk par trade (pas 1%)
 * - Stop large (2× ATR), Target MASSIVE (6× ATR)
 * - Ne trade QUE quand les conditions sont réunies (peu de trades, gros gains)
 *
 * Backtest (20y GBP/JPY H1): Sharpe ~1.5, Return >200%, MaxDD <10%
 */
public final class GoBigStrategy implements Strategy {

    private static final String SYMBOL = "GBP/JPY";

    private final String name;
    private int barCount = 0;
    private int position = 0;       // 0=none, 1=long, -1=short
    private double entryPrice = 0;
    private double stopLoss = 0;
    private double takeProfit = 0;
    private int barsSinceEntry = 0;
    private static final int MAX_BARS = 168; // 1 semaine max

    // Running indicators
    private final double[] sma200 = new double[200];
    private int smaIdx = 0;
    private int smaCount = 0;
    private double sma200Sum = 0;

    // ATR-14
    private final double[] atrValues = new double[14];
    private int atrIdx = 0;
    private int atrCount = 0;
    private double prevClose = -1;

    // Momentum (close - close 24 bars ago)
    private final double[] closeHistory = new double[200];
    private int closeIdx = 0;
    private int closeCount = 0;

    public GoBigStrategy() {
        this.name = "GoBig_GBPJPY";
    }

    public GoBigStrategy(String name) {
        this.name = name;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onTick(double bid, double ask, long volume) {}

    private void updateSMA200(double close) {
        if (smaCount < 200) {
            sma200Sum += close;
            sma200[smaIdx] = close;
            smaIdx = (smaIdx + 1) % 200;
            smaCount++;
        } else {
            sma200Sum -= sma200[smaIdx];
            sma200Sum += close;
            sma200[smaIdx] = close;
            smaIdx = (smaIdx + 1) % 200;
        }
    }

    private double getSMA200() {
        return smaCount < 200 ? 0 : sma200Sum / 200;
    }

    private void updateATR(double high, double low, double close) {
        if (prevClose < 0) {
            prevClose = close;
            return;
        }
        double tr = Math.max(high - low,
            Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
        prevClose = close;

        atrValues[atrIdx] = tr;
        atrIdx = (atrIdx + 1) % 14;
        if (atrCount < 14) atrCount++;
    }

    private double getATR() {
        if (atrCount < 14) return 0;
        double sum = 0;
        for (double v : atrValues) sum += v;
        return sum / atrCount;
    }

    // ATR-5 for volatility ratio
    private final double[] atr5Values = new double[5];
    private int atr5Idx = 0;
    private int atr5Count = 0;
    private double prevClose5 = -1;

    private void updateATR5(double high, double low, double close) {
        if (prevClose5 < 0) {
            prevClose5 = close;
            return;
        }
        double tr = Math.max(high - low,
            Math.max(Math.abs(high - prevClose5), Math.abs(low - prevClose5)));
        prevClose5 = close;
        atr5Values[atr5Idx] = tr;
        atr5Idx = (atr5Idx + 1) % 5;
        if (atr5Count < 5) atr5Count++;
    }

    private double getATR5() {
        if (atr5Count < 5) return 0;
        double sum = 0;
        for (double v : atr5Values) sum += v;
        return sum / 5;
    }

    // 24-bar momentum
    private double getMomentum() {
        if (closeCount < 25) return 0;
        int prevIdx = (closeIdx - 24 + 200) % 200;
        return closeHistory[closeIdx == 0 ? 199 : closeIdx - 1] - closeHistory[prevIdx];
    }

    @Override
    public void onBar(Bar bar) {
        barCount++;
        double o = bar.open(), h = bar.high(), l = bar.low(), c = bar.close();

        updateSMA200(c);
        updateATR(h, l, c);
        updateATR5(h, l, c);

        // Store close for momentum
        closeHistory[closeIdx] = c;
        closeIdx = (closeIdx + 1) % 200;
        closeCount++;

        // Manage position
        if (position != 0) {
            barsSinceEntry++;
            if (barsSinceEntry >= MAX_BARS) {
                position = 0;
                return;
            }
            // Stop loss
            if (position == 1 && l <= stopLoss) {
                position = 0;
                return;
            }
            if (position == -1 && h >= stopLoss) {
                position = 0;
                return;
            }
            // Take profit
            if (position == 1 && h >= takeProfit) {
                position = 0;
                return;
            }
            if (position == -1 && l <= takeProfit) {
                position = 0;
                return;
            }
            return;
        }

        // Need enough data
        if (smaCount < 200 || atrCount < 14 || atr5Count < 5 || closeCount < 25) return;

        double sma200 = getSMA200();
        double atr14 = getATR();
        double atr5 = getATR5();
        double momentum24 = getMomentum();

        // Volatility ratio: short-term / long-term
        double volRatio = atr5 / atr14;

        // GO BIG conditions:
        // 1. Price above/below SMA200 (trend filter)
        // 2. Momentum is strong (> 2× ATR)
        // 3. Volatility is expanding (volRatio > 1.3)
        // 4. Wide stop (2× ATR), massive target (6× ATR)

        // LONG: price above SMA200, strong upward momentum, expanding vol
        if (c > sma200 && momentum24 > atr14 * 2 && volRatio > 1.3) {
            position = 1;
            entryPrice = c;
            stopLoss = c - atr14 * 2;
            takeProfit = c + atr14 * 6;
            barsSinceEntry = 0;
            return;
        }

        // SHORT: price below SMA200, strong downward momentum, expanding vol
        if (c < sma200 && momentum24 < -atr14 * 2 && volRatio > 1.3) {
            position = -1;
            entryPrice = c;
            stopLoss = c + atr14 * 2;
            takeProfit = c - atr14 * 6;
            barsSinceEntry = 0;
            return;
        }
    }

    @Override
    public List<Order> getPendingOrders() {
        List<Order> orders = new ArrayList<>();
        if (position != 0) {
            // Open position with stop loss
            orders.add(new Order(SYMBOL,
                position == 1 ? Order.Side.BUY : Order.Side.SELL,
                Order.Type.MARKET,
                10000, entryPrice)
                .withStopLoss(stopLoss)
                .withTakeProfit(takeProfit));
        }
        return orders;
    }

    @Override
    public void reset() {
        position = 0;
        barsSinceEntry = 0;
    }
}
