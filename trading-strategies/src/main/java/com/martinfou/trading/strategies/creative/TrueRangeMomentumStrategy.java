package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * True Range Momentum Strategy — Structure/Technical
 *
 * 📊 Inspiration: Ali Casey's "test at scale" + H1 trend persistence.
 *    Uses prior-bar range extension + SMA trend filter to capture
 *    momentum breakouts only in the direction of the prevailing trend.
 *
 * 🔧 Mechanism:
 *    - SMA(50) defines the trend direction
 *    - Entry (BUY): close above (prior high + 1.0×ATR) AND price above SMA(50)
 *    - Entry (SELL): close below (prior low - 1.0×ATR) AND price below SMA(50)
 *    - The 1.0×ATR buffer prevents whipsaw
 *    - SMA(50) filter ensures we only trade with the trend
 *    - Exit: ATR trailing stop (1.5×) or max 15 bars
 *
 * 🎯 Originality: Unlike existing breakout strategies (OpeningRangeBreakout,
 *    InsideBarBreakout, VolContractionBreakout), this uses PRIOR bar range
 *    + volatility buffer with a trend filter. The key innovation is the
 *    SMA(50) direction filter preventing counter-trend entries.
 *
 * Paramètres: ATR period (14), entry buffer (1.0), SMA trend (50), stop (1.5)
 */
public class TrueRangeMomentumStrategy implements Strategy {

    private static final int MIN_HISTORY = 100;
    private static final int ATR_PERIOD = 14;
    private static final int SMA_TREND = 50;
    private static final double ENTRY_BUFFER = 1.0;
    private static final double ATR_STOP_MULT = 1.5;
    private static final int MAX_BARS_HOLD = 15;
    private static final double MIN_POSITION = 1000;
    private static final int COOLDOWN_BARS = 5;

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private int barsInTrade;
    private double highestSinceEntry;
    private double lowestSinceEntry;
    private int cooldownBars;
    private boolean enteredThisBar; // Prevent multiple entries on same bar

    public TrueRangeMomentumStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    public TrueRangeMomentumStrategy() {
        this("TrueRangeMomentum", "EUR/USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        enteredThisBar = false;
        managePosition(bar);

        if (!inTrade) {
            if (cooldownBars > 0) { cooldownBars--; return; }
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
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

        if (tradeDirection == Order.Side.BUY) {
            highestSinceEntry = Math.max(highestSinceEntry, bar.high());
        } else {
            lowestSinceEntry = Math.min(lowestSinceEntry, bar.low());
        }

        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);

        if (stopHit || barsInTrade >= MAX_BARS_HOLD) {
            closePosition(bar.close());
            return;
        }

        // Trailing stop
        double atr = atr();
        if (!Double.isNaN(atr) && atr > 0) {
            if (tradeDirection == Order.Side.BUY) {
                double trail = highestSinceEntry - atr * ATR_STOP_MULT;
                stopLoss = Math.max(stopLoss, trail);
                if (bar.low() <= stopLoss) { closePosition(bar.close()); return; }
            } else {
                double trail = lowestSinceEntry + atr * ATR_STOP_MULT;
                stopLoss = Math.min(stopLoss, trail);
                if (bar.high() >= stopLoss) { closePosition(bar.close()); return; }
            }
        }
    }

    private void evaluateEntry(Bar bar) {
        int end = history.size() - 1;
        if (end < 2) return;

        Bar prev = history.get(end - 1);
        double atr = atr();
        double sma50 = Indicators.smaLatest(history, SMA_TREND);

        if (Double.isNaN(atr) || atr <= 0 || Double.isNaN(sma50)) return;

        double buffer = atr * ENTRY_BUFFER;

        // BUY: close above (prior high + buffer) AND price above SMA(50)
        if (bar.close() > prev.high() + buffer && bar.close() > sma50) {
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            highestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, MIN_POSITION, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
            enteredThisBar = true;
        }
        // SELL: close below (prior low - buffer) AND price below SMA(50)
        else if (bar.close() < prev.low() - buffer && bar.close() < sma50) {
            entryPrice = bar.close();
            stopLoss = entryPrice + atr * ATR_STOP_MULT;
            lowestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, MIN_POSITION, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            barsInTrade = 0;
            enteredThisBar = true;
        }
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, MIN_POSITION, price).closeOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }

    private double atr() {
        return Indicators.atr(history, ATR_PERIOD);
    }
}
