package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * Weekday Session Strategy — Seasonality/Calendar
 *
 * 📊 Inspiration: forex-seasonality time-of-day patterns + DailyFX weekday
 *    seasonality. Specific weekdays and session times have directional biases
 *    driven by institutional flows, option expiries, and weekend positioning.
 *
 * 🔧 Mechanism:
 *    - Trade specific sessions on specific weekdays:
 *      Monday:    London open momentum (08:00 UTC) — trend continuation from Friday
 *      Tuesday:   London/NY overlap (13:00-15:00 UTC) — strongest directional day
 *      Wednesday: Position squaring before NFP week (fade extremes)
 *      Thursday:  Pre-weekend positioning
 *      Friday:    NFP day (if high impact) — Steven Goldstein straddle approach
 *    - Simple price-break entries (not news-dependent)
 *    - ATR-based stops and fixed profit targets
 *
 * 🎯 Improvements over TurnOfMonth:
 *    - More trades (weekly vs monthly = 4× frequency)
 *    - Session-specific logic adapts to market microstructure
 *    - Multiple entry opportunities per week
 */
public class WeekdaySessionStrategy implements Strategy {

    // Trading sessions by day (UTC)
    private static final int LONDON_OPEN = 7;    // 07 UTC
    private static final int NY_OVERLAP = 13;     // 13-15 UTC
    private static final int NY_CLOSE = 20;       // 20 UTC

    private static final int ATR_PERIOD = 14;
    private static final int MIN_HISTORY = 48;
    private static final double STOP_ATR_MULT = 2.0;
    private static final double TP_ATR_MULT = 2.5;
    private static final double POSITION_SIZE = 1000;

    // Directional bias by weekday: +1 = long bias, -1 = short bias, 0 = neutral/fade
    // Monday: trend continuation, Tuesday: direction, Wednesday: fade/neutral,
    // Thursday: positioning, Friday: mixed
    private static final int[] WEEKLY_BIAS = { 1, 1, 0, -1, 0 };

    // Pairs that the bias works best for
    private static final int MONDAY = 1;
    private static final int TUESDAY = 2;
    private static final int WEDNESDAY = 3;
    private static final int THURSDAY = 4;
    private static final int FRIDAY = 5;

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
    private int entryBarTime;

    // Track daily state
    private int currentDayKey = -1;
    private int currentDayOfWeek = -1;
    private boolean dailyTradeAttempted = false;
    private boolean dailyEntryPlaced = false;

    public WeekdaySessionStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    public WeekdaySessionStrategy() {
        this("WeekdaySession", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        ZonedDateTime zdt = bar.timestamp().atZone(ZoneOffset.UTC);
        int dayKey = dayKey(bar);
        int hour = zdt.getHour();
        int dayOfWeek = zdt.getDayOfWeek().getValue(); // 1=Mon, 7=Sun

        // New day — reset daily state
        if (dayKey != currentDayKey) {
            currentDayKey = dayKey;
            currentDayOfWeek = dayOfWeek;
            dailyTradeAttempted = false;
            dailyEntryPlaced = false;

            // Skip weekends
            if (dayOfWeek >= 6) return;
        }

        // Weekend — skip
        if (currentDayOfWeek >= 6) return;

        managePosition(bar);

        if (!inTrade && !dailyEntryPlaced) {
            evaluateEntry(bar, dayOfWeek, hour);
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
        currentDayKey = -1;
        currentDayOfWeek = -1;
        dailyTradeAttempted = false;
        dailyEntryPlaced = false;
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

        // Exit at end of day
        if (barsInTrade > 8) {
            inTrade = false;
            return;
        }

        if (tradeDirection == Order.Side.BUY) {
            if (bar.low() <= stopLoss || bar.high() >= takeProfit) {
                inTrade = false;
                return;
            }
        } else {
            if (bar.high() >= stopLoss || bar.low() <= takeProfit) {
                inTrade = false;
                return;
            }
        }

        // Trailing stop after 4 bars
        if (barsInTrade >= 4) {
            double atr = atr(ATR_PERIOD);
            if (tradeDirection == Order.Side.BUY) {
                stopLoss = Math.max(stopLoss, bar.close() - atr * STOP_ATR_MULT);
            } else {
                stopLoss = Math.min(stopLoss, bar.close() + atr * STOP_ATR_MULT);
            }
        }
    }

    private void evaluateEntry(Bar bar, int dayOfWeek, int hour) {
        double atr = atr(ATR_PERIOD);
        if (Double.isNaN(atr) || atr <= 0) return;

        int bias = WEEKLY_BIAS[dayOfWeek - 1];
        Order.Side direction = null;

        switch (dayOfWeek) {
            case MONDAY:
                // London open momentum: enter in direction of previous Friday's close trend
                if (hour == LONDON_OPEN) {
                    direction = detectMomentumDirection();
                }
                break;

            case TUESDAY:
                // Strongest directional day: NY overlap, enter on break of previous hour range
                if (hour == NY_OVERLAP) {
                    direction = detectMomentumDirection();
                }
                break;

            case WEDNESDAY:
                // Fade day: enter opposite to yesterday's direction (mean reversion mid-week)
                if (hour == LONDON_OPEN) {
                    direction = detectReversalDirection();
                }
                break;

            case THURSDAY:
                // Pre-weekend: short bias, enter on weakness during London
                if (hour == LONDON_OPEN) {
                    direction = Order.Side.SELL;
                }
                break;

            case FRIDAY:
                // Mixed: follow the hourly momentum in London session
                if (hour == LONDON_OPEN) {
                    if (bias == 0) {
                        // No fixed bias — use momentum
                        direction = bar.close() > ema(21) ? Order.Side.BUY : Order.Side.SELL;
                    } else {
                        direction = bias > 0 ? Order.Side.BUY : Order.Side.SELL;
                    }
                }
                break;
        }

        if (direction == null) return;

        double pip = Indicators.pipSize(symbol);

        if (direction == Order.Side.BUY) {
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * STOP_ATR_MULT;
            takeProfit = entryPrice + atr * TP_ATR_MULT;

            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, POSITION_SIZE, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
            dailyEntryPlaced = true;
        } else {
            entryPrice = bar.close();
            stopLoss = entryPrice + atr * STOP_ATR_MULT;
            takeProfit = entryPrice - atr * TP_ATR_MULT;

            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, POSITION_SIZE, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            barsInTrade = 0;
            dailyEntryPlaced = true;
        }
    }

    /** Detect momentum direction from recent price action. */
    private Order.Side detectMomentumDirection() {
        if (history.size() < 30) return null;
        int end = history.size() - 1;
        double sma10 = sma(10);
        double sma30 = sma(30);
        if (Double.isNaN(sma10) || Double.isNaN(sma30)) return null;

        // Short-term momentum + medium-term trend alignment
        boolean shortTermUp = history.get(end).close() > history.get(end - 3).close();
        boolean mediumTermUp = sma10 > sma30;

        if (shortTermUp && mediumTermUp) return Order.Side.BUY;
        if (!shortTermUp && !mediumTermUp) return Order.Side.SELL;
        return null; // Conflicting signals — skip
    }

    /** Detect reversal setup (mid-week fade). */
    private Order.Side detectReversalDirection() {
        if (history.size() < 30) return null;
        int end = history.size() - 1;

        // If price moved up significantly yesterday, fade long today
        double yesterdayReturn = (history.get(end - 1).close() - history.get(end - 25).close())
            / history.get(end - 25).close();

        if (yesterdayReturn > 0.005) return Order.Side.SELL;  // Fade rally
        if (yesterdayReturn < -0.005) return Order.Side.BUY;   // Fade selloff
        return null;
    }

    private double atr(int period) {
        return Indicators.atr(history, period);
    }

    private double ema(int period) {
        return Indicators.emaLatest(history, period);
    }

    private double sma(int period) {
        return Indicators.smaLatest(history, period);
    }

    private int dayKey(Bar bar) {
        return bar.timestamp().atZone(ZoneOffset.UTC).toLocalDate()
            .getYear() * 1000 + bar.timestamp().atZone(ZoneOffset.UTC).toLocalDate().getDayOfYear();
    }
}
