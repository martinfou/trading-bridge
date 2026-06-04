package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * London Session EMA Pullback — Structure/Technical
 *
 * 📊 Inspiration: Ali Casey (StatOasis) — H1 trends persist, pullbacks to
 *    EMA in trending conditions are entries not reversals.
 *    Lewis Jackson — regime-aware entry with trend confirmation.
 *
 * 🔧 Mechanism:
 *    - Track 20-EMA (fast) and 50-EMA (slow) for trend direction
 *    - Entry at pullback to 20-EMA during London session (08:00-16:00 UTC)
 *    - Pullback confirmed by 1-2 bars moving against the trend
 *    - Trend required: 50-EMA slope positive (long) or negative (short)
 *    - Exit: ATR trailing stop at 1.5×, max 10 bars hold
 *
 * 🎯 Novelty: Combines EMA trend filter + session filter + pullback entry.
 *    None of the existing creative strategies use this exact combination.
 */
public class LondonSessionEMAPullbackStrategy implements Strategy {

    private static final int FAST_EMA = 20;
    private static final int SLOW_EMA = 50;
    private static final int ATR_PERIOD = 14;
    private static final double ATR_SL_MULT = 1.5;
    private static final double RR_TARGET = 2.0;
    private static final int MAX_BARS_HOLD = 10;
    private static final int MIN_HISTORY = 60;
    private static final int COOLDOWN_BARS = 5;
    private static final int SESSION_START_HOUR = 8;  // London open UTC
    private static final int SESSION_END_HOUR = 16;    // London close UTC
    private static final double MIN_POSITION = 1000;
    private static final int PULLBACK_BARS = 2;

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private final ZoneOffset tz = ZoneOffset.UTC;

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int barsInTrade;
    private double highestSinceEntry;
    private double lowestSinceEntry;
    private int cooldownBars;
    private double positionSize = MIN_POSITION;

    public LondonSessionEMAPullbackStrategy() {
        this("LondonSessionEMAPullback", "EUR/USD");
    }

    public LondonSessionEMAPullbackStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        if (inTrade) {
            managePosition(bar);
            return;
        }

        if (cooldownBars > 0) {
            cooldownBars--;
            return;
        }

        evaluateEntry(bar);
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

    private void evaluateEntry(Bar bar) {
        // Session filter: London only
        ZonedDateTime zdt = bar.timestamp().atZone(tz);
        int hour = zdt.getHour();
        if (hour < SESSION_START_HOUR || hour >= SESSION_END_HOUR) return;

        double atr = Indicators.atr(history, ATR_PERIOD);
        if (Double.isNaN(atr) || atr <= 0) return;

        double emaFast = Indicators.emaLatest(history, FAST_EMA);
        double emaSlow = Indicators.emaLatest(history, SLOW_EMA);
        if (Double.isNaN(emaFast) || Double.isNaN(emaSlow)) return;

        int size = history.size();
        double close = bar.close();

        // Trend detection via 50-EMA slope
        double emaSlowPrev = Indicators.sma(history, SLOW_EMA, size - 2);
        boolean trendUp = emaSlow > emaSlowPrev;
        boolean trendDown = emaSlow < emaSlowPrev;

        // Pullback detection to 20-EMA
        double emaFastPrev = Indicators.sma(history, FAST_EMA, size - 2);

        // Check if we had PULLBACK_BARS of movement against the trend
        boolean pullbackToFast = false;
        boolean pullbackToSlow = false;

        if (trendUp) {
            // Price was above 20-EMA, pulled back to/below it
            pullbackToFast = close <= emaFast
                && history.get(size - 2).close() > emaFastPrev
                && bar.low() >= emaSlow; // Don't break slow EMA
        } else if (trendDown) {
            pullbackToFast = close >= emaFast
                && history.get(size - 2).close() < emaFastPrev
                && bar.high() <= emaSlow;
        }

        if (!pullbackToFast) return;

        // Entry
        if (trendUp) {
            entryPrice = close;
            stopLoss = entryPrice - atr * ATR_SL_MULT;
            takeProfit = entryPrice + atr * ATR_SL_MULT * RR_TARGET;
            highestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
            cooldownBars = 0;
        } else if (trendDown) {
            entryPrice = close;
            stopLoss = entryPrice + atr * ATR_SL_MULT;
            takeProfit = entryPrice - atr * ATR_SL_MULT * RR_TARGET;
            lowestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            barsInTrade = 0;
            cooldownBars = 0;
        }
    }

    private void managePosition(Bar bar) {
        barsInTrade++;

        if (tradeDirection == Order.Side.BUY) {
            highestSinceEntry = Math.max(highestSinceEntry, bar.high());
        } else {
            lowestSinceEntry = Math.min(lowestSinceEntry, bar.low());
        }

        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);
        boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= takeProfit)
            || (tradeDirection == Order.Side.SELL && bar.low() <= takeProfit);

        if (stopHit || tpHit || barsInTrade >= MAX_BARS_HOLD) {
            closePosition(bar.close());
            return;
        }

        // Trailing stop
        double atr = Indicators.atr(history, ATR_PERIOD);
        if (!Double.isNaN(atr)) {
            if (tradeDirection == Order.Side.BUY) {
                double trail = highestSinceEntry - atr * ATR_SL_MULT;
                stopLoss = Math.max(stopLoss, trail);
                if (bar.low() <= stopLoss) { closePosition(bar.close()); return; }
            } else {
                double trail = lowestSinceEntry + atr * ATR_SL_MULT;
                stopLoss = Math.min(stopLoss, trail);
                if (bar.high() >= stopLoss) { closePosition(bar.close()); return; }
            }
        }
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, positionSize, price).closeOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }
}
