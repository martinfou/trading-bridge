package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Donchian Channel Breakout Strategy — Breakout + Trend Following
 *
 * 📊 Data insight: Breakouts of the 20-period Donchian Channel (highest high
 *    and lowest low over 20 bars) represent genuine support/resistance breaks.
 *    These breakouts tend to have momentum continuation, especially when
 *    accompanied by above-average range expansion on the breakout bar.
 *
 * 🔧 Mechanism: Donchian Channel breakout with volume confirmation.
 *    - Calculate 20-bar Donchian: high = max(high[20]), low = min(low[20])
 *    - Breakout above high → BUY when bar shows bullish body
 *    - Breakdown below low → SELL when bar shows bearish body
 *    - ATR trailing stop with 2:1 risk-reward
 *    - Exit when price touches opposite channel
 *
 * 🎯 Originality: Classic Turtle Trading System adapted for H1 FX.
 *    Uses the original Donchian concept but with modern risk management
 *    (ATR stops, trailing). Parameterized for multi-asset use.
 */
public class DonchianChannelBreakoutStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;

    // Trailing stop for active trades
    private double trailingStop = 0;
    private double highestSinceEntry = 0;
    private double lowestSinceEntry = Double.MAX_VALUE;

    private double positionSize = 1000;
    private int channelPeriod = 20;

    public DonchianChannelBreakoutStrategy() {
        this("DonchianChannelBreakout", "GBP/JPY");
    }

    public DonchianChannelBreakoutStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public DonchianChannelBreakoutStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < channelPeriod + 5) return;

        double atr = calculateATR(14);
        double donchianHigh = getDonchianHigh();
        double donchianLow = getDonchianLow();

        // Manage active trade
        if (inTrade) {
            barsHeld++;

            // Update trailing extremes
            if (tradeDirection == Order.Side.BUY) {
                if (bar.high() > highestSinceEntry) highestSinceEntry = bar.high();
                trailingStop = highestSinceEntry - atr * 2.0;
                if (bar.low() <= trailingStop || barsHeld >= 8) {
                    closePosition(bar); return;
                }
                // Take profit: touch opposite channel
                if (bar.high() >= donchianHigh + entryPrice - donchianLow) {
                    closePosition(bar); return;
                }
            } else {
                if (bar.low() < lowestSinceEntry) lowestSinceEntry = bar.low();
                trailingStop = lowestSinceEntry + atr * 2.0;
                if (bar.high() >= trailingStop || barsHeld >= 8) {
                    closePosition(bar); return;
                }
                if (bar.low() <= donchianLow - (donchianHigh - entryPrice)) {
                    closePosition(bar); return;
                }
            }
            return;
        }

        // Look for breakout
        double range = bar.high() - bar.low();
        double avgRange = calculateAverageRange(10);

        // Upside breakout
        if (bar.high() > donchianHigh && bar.close() > bar.open()) {
            if (range > avgRange * 0.7) {
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.BUY;
                entryPrice = bar.close(); barsHeld = 0;
                highestSinceEntry = bar.high();
                trailingStop = bar.close() - atr * 2.0;
            }
        }
        // Downside breakout
        else if (bar.low() < donchianLow && bar.close() < bar.open()) {
            if (range > avgRange * 0.7) {
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.SELL;
                entryPrice = bar.close(); barsHeld = 0;
                lowestSinceEntry = bar.low();
                trailingStop = bar.close() + atr * 2.0;
            }
        }
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()).closeOnly());
        inTrade = false;
    }

    private double getDonchianHigh() {
        double max = 0;
        for (int i = history.size() - channelPeriod; i < history.size(); i++) {
            if (history.get(i).high() > max) max = history.get(i).high();
        }
        return max;
    }

    private double getDonchianLow() {
        double min = Double.MAX_VALUE;
        for (int i = history.size() - channelPeriod; i < history.size(); i++) {
            if (history.get(i).low() < min) min = history.get(i).low();
        }
        return min;
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

    private double calculateAverageRange(int period) {
        int size = history.size();
        double sum = 0;
        for (int i = Math.max(0, size - period); i < size; i++) {
            sum += (history.get(i).high() - history.get(i).low());
        }
        return sum / Math.min(period, size);
    }

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending); pending.clear(); return copy;
    }
    @Override public void reset() {
        history.clear(); pending.clear(); inTrade = false;
        tradeDirection = Order.Side.BUY; entryPrice = 0; barsHeld = 0;
        trailingStop = 0; highestSinceEntry = 0; lowestSinceEntry = Double.MAX_VALUE;
    }
}
