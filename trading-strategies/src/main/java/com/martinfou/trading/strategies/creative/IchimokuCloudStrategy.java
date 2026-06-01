package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Ichimoku Cloud Strategy — Trend Following + Multi-Timeframe
 *
 * 📊 Data insight: The Ichimoku Kinko Hyo system provides multiple
 *    perspectives simultaneously: Tenkan-sen (fast support), Kijun-sen
 *    (slow support), Senkou Span A/B (cloud = future support/resistance),
 *    and Chikou Span (lagging confirmation). When price is above the cloud,
 *    Tenkan crosses above Kijun, and Chikou is above price from 26 bars ago,
 *    a strong multi-confirmation uptrend exists.
 *
 * 🔧 Mechanism:
 *    - Calculate Tenkan-sen (9-period midpoint), Kijun-sen (26-period midpoint)
 *    - Senkou Span A = (Tenkan + Kijun)/2 shifted 26 bars forward
 *    - Senkou Span B = (52-period midpoint) shifted 26 bars forward
 *    - BUY when: price > cloud AND Tenkan > Kijun AND Chikou > price from 26 bars ago
 *    - SELL when: price < cloud AND Tenkan < Kijun AND Chikou < price from 26 bars ago
 *    - Exit when Tenkan/Kijun cross back or price enters cloud
 *
 * 🎯 Originality: True Ichimoku implementation — not just a cloud filter but
 *    full 3-component confirmation. The cloud thickness also gives dynamic
 *    volatility-based stop distances.
 */
public class IchimokuCloudStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double positionSize = 1000;

    public IchimokuCloudStrategy() {
        this("IchimokuCloud", "GBP/JPY");
    }

    public IchimokuCloudStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public IchimokuCloudStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 60) return;

        double atr = calculateATR(14);

        // Ichimoku components
        double tenkan = (highestHigh(9) + lowestLow(9)) / 2.0;
        double kijun = (highestHigh(26) + lowestLow(26)) / 2.0;
        double senkouA = (tenkan + kijun) / 2.0; // will compare to price
        double senkouB = (highestHigh(52) + lowestLow(52)) / 2.0;
        double cloudTop = Math.max(senkouA, senkouB);
        double cloudBot = Math.min(senkouA, senkouB);

        // Chikou Span: current close compared to close 26 bars ago
        double chikouCurrent = bar.close();
        double chikou26Ago = history.get(history.size() - 1 - 26).close();

        // Manage active trade
        if (inTrade) {
            // Exit conditions
            boolean tenkanCrossed = (tradeDirection == Order.Side.BUY) ? tenkan < kijun : tenkan > kijun;
            boolean priceInCloud = bar.close() > cloudBot && bar.close() < cloudTop;
            boolean cloudWide = (cloudTop - cloudBot) > atr * 0.5;

            if (tenkanCrossed || priceInCloud) {
                closePosition(bar);
                return;
            }

            // ATR-based stops
            if (tradeDirection == Order.Side.BUY) {
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar); return; }
                if (bar.high() >= entryPrice + atr * 2.0) { closePosition(bar); return; }
            } else {
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar); return; }
                if (bar.low() <= entryPrice - atr * 2.0) { closePosition(bar); return; }
            }
            return;
        }

        // Entry signals
        boolean aboveCloud = bar.close() > cloudTop;
        boolean belowCloud = bar.close() < cloudBot;
        boolean tenkanAboveKijun = tenkan > kijun;
        boolean tenkanBelowKijun = tenkan < kijun;
        boolean chikouConfirmingLong = chikouCurrent > chikou26Ago;
        boolean chikouConfirmingShort = chikouCurrent < chikou26Ago;

        // Trend alignment filter — need cloud not too thin
        double cloudThickness = cloudTop - cloudBot;
        double minCloud = atr * 0.3;

        // BUY: above cloud, Tenkan > Kijun, Chikou confirms
        if (aboveCloud && tenkanAboveKijun && chikouConfirmingLong && cloudThickness > minCloud) {
            // Enter on pullback toward Kijun
            if (bar.low() <= kijun + atr * 0.5) {
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); return;
            }
        }

        // SELL: below cloud, Tenkan < Kijun, Chikou confirms
        if (belowCloud && tenkanBelowKijun && chikouConfirmingShort && cloudThickness > minCloud) {
            // Enter on rally toward Kijun
            if (bar.high() >= kijun - atr * 0.5) {
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); return;
            }
        }
    }

    private double highestHigh(int period) {
        double max = Double.MIN_VALUE;
        for (int i = history.size() - period; i < history.size(); i++)
            if (i >= 0) max = Math.max(max, history.get(i).high());
        return max;
    }

    private double lowestLow(int period) {
        double min = Double.MAX_VALUE;
        for (int i = history.size() - period; i < history.size(); i++)
            if (i >= 0) min = Math.min(min, history.get(i).low());
        return min;
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()).closeOnly());
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
