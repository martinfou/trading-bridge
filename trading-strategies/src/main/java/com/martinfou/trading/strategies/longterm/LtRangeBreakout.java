package com.martinfou.trading.strategies.longterm;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * LtRangeBreakout — Donchian Channel Breakout (Classic Turtle System)
 *
 * 📊 Inspiration: The original Richard Dennis / William Eckhardt Turtle Trader
 *    system. Donchian channel breakout is the oldest systematic trend-following
 *    strategy, proven across decades of futures and forex data.
 *
 * 🔧 Mechanism:
 *    - Entry: price > highest high of last 20 bars → BUY
 *            price < lowest low of last 20 bars → SELL
 *    - SL = 2 × ATR(14), TP = 4 × ATR(14)
 *    - Trailing stop follows at ATR × 2 from extreme since entry
 *    - Max 1 trade per day
 *    - Exit on trailing stop hit or opposite breakout signal
 */
public class LtRangeBreakout implements Strategy {

    private static final int DONCHIAN_PERIOD = 20;
    private static final int ATR_PERIOD = 14;
    private static final double SL_MULT = 2.0;
    private static final double TP_MULT = 4.0;
    private static final double REFERENCE_CAPITAL = 10_000;
    private static final double RISK_PCT = 0.01;
    private static final int MIN_HISTORY = 80;
    private static final int COOLDOWN_BARS = 8;

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int barsInTrade;
    private double highestSinceEntry;
    private double lowestSinceEntry;
    private int cooldownBars;
    private int tradesToday;
    private int lastTradeDay;

    public LtRangeBreakout(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    public LtRangeBreakout() {
        this("LtRangeBreakout", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        // Track trades per day (max 1)
        int barDay = bar.timestamp().atZone(java.time.ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        managePosition(bar);

        if (!inTrade) {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 1) return;
            evaluateEntry(bar);
        }
    }

    @Override
    public void onTick(double bid, double ask, long volume) {}

    @Override
    public List<Order> getPendingOrders() {
        var copy = List.copyOf(pending);
        pending.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        barsInTrade = 0;
        cooldownBars = 0;
        tradesToday = 0;
        lastTradeDay = -1;
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

        if (tradeDirection == Order.Side.BUY) {
            highestSinceEntry = Math.max(highestSinceEntry, bar.high());
        } else {
            lowestSinceEntry = Math.min(lowestSinceEntry, bar.low());
        }

        // Check trailing stop hit
        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);
        boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= takeProfit)
            || (tradeDirection == Order.Side.SELL && bar.low() <= takeProfit);

        if (stopHit || tpHit) {
            closePosition(bar.close());
            return;
        }

        // Trailing stop — tighten as price moves in our favor
        double atr = atr();
        if (Double.isNaN(atr) || atr <= 0) return;

        if (tradeDirection == Order.Side.BUY) {
            double trail = highestSinceEntry - atr * SL_MULT;
            stopLoss = Math.max(stopLoss, trail);
            if (bar.low() <= stopLoss) { closePosition(bar.close()); return; }
        } else {
            double trail = lowestSinceEntry + atr * SL_MULT;
            stopLoss = Math.min(stopLoss, trail);
            if (bar.high() >= stopLoss) { closePosition(bar.close()); return; }
        }

        // Check for opposite breakout signal → exit
        double highestHigh = highest(history, DONCHIAN_PERIOD);
        double lowestLow = lowest(history, DONCHIAN_PERIOD);

        if (tradeDirection == Order.Side.BUY && bar.low() < lowestLow) {
            // Bearish breakout while long → exit
            closePosition(bar.close());
        } else if (tradeDirection == Order.Side.SELL && bar.high() > highestHigh) {
            // Bullish breakout while short → exit
            closePosition(bar.close());
        }
    }

    private void evaluateEntry(Bar bar) {
        double highestHigh = highest(history, DONCHIAN_PERIOD);
        double lowestLow = lowest(history, DONCHIAN_PERIOD);
        double atr = atr();

        if (Double.isNaN(atr) || atr <= 0) return;

        // Bullish breakout: buy when price breaks above highest high
        if (bar.close() > highestHigh) {
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * SL_MULT;
            takeProfit = entryPrice + atr * TP_MULT;
            highestSinceEntry = entryPrice;
            lowestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol), entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
            tradesToday++;
        }
        // Bearish breakout: sell when price breaks below lowest low
        else if (bar.close() < lowestLow) {
            entryPrice = bar.close();
            stopLoss = entryPrice + atr * SL_MULT;
            takeProfit = entryPrice - atr * TP_MULT;
            highestSinceEntry = entryPrice;
            lowestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol), entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            barsInTrade = 0;
            tradesToday++;
        }
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, 1000, price).closeOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }

    private double atr() {
        return Indicators.atr(history, ATR_PERIOD);
    }

    private static double highest(List<Bar> bars, int period) {
        int end = bars.size() - 2; // exclude the current bar (index -1) — use prior bar for lookback
        int start = Math.max(0, end - period + 1);
        double max = Double.NEGATIVE_INFINITY;
        for (int i = start; i <= end; i++) {
            if (bars.get(i).high() > max) max = bars.get(i).high();
        }
        return max;
    }

    private static double lowest(List<Bar> bars, int period) {
        int end = bars.size() - 2; // exclude current bar
        int start = Math.max(0, end - period + 1);
        double min = Double.POSITIVE_INFINITY;
        for (int i = start; i <= end; i++) {
            if (bars.get(i).low() < min) min = bars.get(i).low();
        }
        return min;
    }
}
