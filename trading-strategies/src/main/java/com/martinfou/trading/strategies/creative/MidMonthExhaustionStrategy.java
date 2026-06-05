package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * Mid-Month Exhaustion Strategy — Seasonality/Calendar
 *
 * 📊 Inspiration: Quantified Strategies' regime-awareness and
 *    the forex-seasonality skill's monthly patterns.
 *    Mid-month (weeks 2-3) is a consolidation/mean-reversion period
 *    in forex — strong 3-bar moves tend to exhaust.
 *
 * 🔧 Mechanism:
 *    - Only trade during weeks 2-3 of each calendar month
 *    - Entry: Price moved ≥ 1.5×ATR over last 3 bars → fade that move
 *    - Rationale: mid-month lacks strong directional catalysts (no NFP,
 *      no month-end rebalancing), so extremes revert
 *    - Exit: ATR trailing stop (1.5×) or max 8 bars hold
 *
 * 🎯 Originality: No existing strategy isolates mid-month mean-reversion.
 *    MonthWeekPhaseStrategy uses week-of-month but for directional bias,
 *    not mean-reversion. MonthlyRotationStrategy is pure monthly bias.
 *    ThursdayRangeExpansion and FridayBear are day-specific.
 *    This targets only the mid-month window (trading days 6-21 of month).
 */
public class MidMonthExhaustionStrategy implements Strategy {

    private static final int MIN_HISTORY = 40;
    private static final int ATR_PERIOD = 14;
    private static final int LOOKBACK_BARS = 3;
    private static final double MOVE_THRESHOLD = 1.5;
    private static final double ATR_STOP_MULT = 1.5;
    private static final int MAX_BARS_HOLD = 8;
    private static final double MIN_POSITION = 1000;
    private static final int COOLDOWN_BARS = 3;

    // Mid-month window: trading days 6 through 21 of each month
    // (skipping first week and last week)
    private static final int MID_MONTH_START = 6;
    private static final int MID_MONTH_END = 21;

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private final ZoneId nyZone = ZoneId.of("America/New_York");

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private int barsInTrade;
    private double highestSinceEntry;
    private double lowestSinceEntry;
    private int cooldownBars;

    // Month tracking
    private int lastMonthBarDay = -1;
    private int monthTradingDay = 0;
    private int currentMonth = -1;

    public MidMonthExhaustionStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    public MidMonthExhaustionStrategy() {
        this("MidMonthExhaustion", "EUR/USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        ZonedDateTime zdt = bar.timestamp().atZone(nyZone);
        int barMonth = zdt.getMonthValue();
        int barDayOfMonth = zdt.getDayOfMonth();

        // Track trading day within month (only count Mon-Fri)
        int dayOfWeek = zdt.getDayOfWeek().getValue();
        if (dayOfWeek >= 1 && dayOfWeek <= 5) {
            if (barDayOfMonth != lastMonthBarDay || barMonth != currentMonth) {
                if (barMonth != currentMonth) {
                    monthTradingDay = 0;
                    currentMonth = barMonth;
                }
                monthTradingDay++;
                lastMonthBarDay = barDayOfMonth;
            }
        }

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
        monthTradingDay = 0;
        currentMonth = -1;
        lastMonthBarDay = -1;
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
        // Only trade during mid-month window
        if (monthTradingDay < MID_MONTH_START || monthTradingDay > MID_MONTH_END) return;

        int end = history.size() - 1;
        if (end < LOOKBACK_BARS) return;

        double atr = atr();
        if (Double.isNaN(atr) || atr <= 0) return;

        // Measure 3-bar move
        double threeBarMove = bar.close() - history.get(end - LOOKBACK_BARS + 1).open();
        double absMove = Math.abs(threeBarMove);
        double threshold = atr * MOVE_THRESHOLD;

        if (absMove < threshold) return; // Not extreme enough

        if (threeBarMove > 0) {
            // Strong up move → fade: SELL
            entryPrice = bar.close();
            stopLoss = entryPrice + atr * ATR_STOP_MULT;
            lowestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, MIN_POSITION, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            barsInTrade = 0;
        } else {
            // Strong down move → fade: BUY
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            highestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, MIN_POSITION, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
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
