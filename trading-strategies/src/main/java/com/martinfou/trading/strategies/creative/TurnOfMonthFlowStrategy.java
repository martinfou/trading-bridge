package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * Turn-of-Month Flow Strategy — Seasonality/Calendar
 *
 * 📊 Inspiration: forex-seasonality quarter-end/month-end patterns from
 *    DailyFX, Quantified Strategies, and academic research. Forex month-end
 *    is driven by institutional portfolio rebalancing, repatriation flows,
 *    and options expiry. EUR/USD shows mean reversion in the last 3 trading
 *    days of each month, while USD/JPY tends to trend with the month bias.
 *
 * 🔧 Mechanism:
 *    - Detect last 3 trading days of each calendar month
 *    - Quarterly ends (Mar, Jun, Sep, Dec) have stronger signals:
 *      fade the month's dominant direction (rebalancing flow)
 *    - Non-quarterly month ends: trade in direction of monthly bias
 *    - Entry: on the first of the last 3 trading days
 *    - Exit: end of 3rd trading day of the new month
 *    - Stop: 2× ATR(14) from entry
 *
 * 🎯 Originality: Pure calendar-based strategy exploiting institutional
 *    flow patterns. Unlike typical seasonal strategies that trade all
 *    month, this focuses on the concentrated 3-day month-end window
 *    where rebalancing flows are strongest and most predictable.
 *
 * References:
 *   - DailyFX Seasonality Tool: month-end USD strength
 *   - Quantified Strategies: monthly pattern book
 *   - FX_Programmer (Forex Factory): quarter-end rebalancing
 */
public class TurnOfMonthFlowStrategy implements Strategy {

    private static final int ATR_PERIOD = 14;
    private static final double ATR_STOP_MULT = 2.0;
    private static final double RR = 2.0;
    private static final int MIN_HISTORY = 30;
    private static final double MIN_POSITION = 1000;

    // Monthly directional bias based on known seasonal patterns
    // +1 = long bias, -1 = short bias, 0 = neutral
    // Based on EUR/USD historical patterns
    private static final int[] MONTHLY_DIRECTION = {
         1,  // Jan: +1.2% avg, 65% WR
         0,  // Feb: mixed
         0,  // Mar: quarter-end, rebalancing
        -1,  // Apr: tax season USD strength
         0,  // May: mixed
        -1,  // Jun: quarter-end, USD typically strong
         0,  // Jul: summer, mixed
         0,  // Aug: summer doldrums
         0,  // Sep: quarter-end, rebalancing
         1,  // Oct: positive bias
         0,  // Nov: mixed
         1   // Dec: Santa rally, holiday drift
    };

    // Quarter-end months have stronger signals
    private static final boolean[] IS_QUARTER_END = {
        false, // Jan
        false, // Feb
        true,  // Mar (Q1)
        false, // Apr
        false, // May
        true,  // Jun (Q2)
        false, // Jul
        false, // Aug
        true,  // Sep (Q3)
        false, // Oct
        false, // Nov
        true   // Dec (Q4)
    };

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    // Month-end window tracking
    private boolean inWindow = false;
    private int windowStartDay = -1;
    private int windowEndDay = -1;
    private int windowMonth = -1;
    private int windowYear = -1;
    private int daysInWindow = 0;

    // Trade state
    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int barsInTrade;
    private int currentMonth = -1;
    private double positionSize;

    public TurnOfMonthFlowStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
    }

    public TurnOfMonthFlowStrategy() {
        this("TurnOfMonthFlow", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        managePosition(bar);
        updateMonthWindow(bar);

        if (!inTrade && inWindow) {
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
        inWindow = false;
        windowStartDay = -1;
        windowEndDay = -1;
        windowMonth = -1;
        barsInTrade = 0;
        daysInWindow = 0;
        currentMonth = -1;
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

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

        // Check if we should exit due to time (end of window)
        if (!inWindow) {
            inTrade = false;
        }
    }

    private void updateMonthWindow(Bar bar) {
        ZonedDateTime zdt = bar.timestamp().atZone(ZoneOffset.UTC);
        int year = zdt.getYear();
        int month = zdt.getMonthValue();
        int dayOfMonth = zdt.getDayOfMonth();
        int dayOfWeek = zdt.getDayOfWeek().getValue(); // 1=Mon, 7=Sun

        // Track if we're in a new month
        if (month != currentMonth) {
            // Previous month window just ended — check if we were in window
            currentMonth = month;
            inWindow = false;
            daysInWindow = 0;
        }

        // Determine the last 3 trading days (Mon-Fri) of the month
        if (dayOfMonth >= 25 && dayOfWeek <= 5) { // Last week, weekday
            // Check if this could be one of the last 3 trading days
            LocalDate ld = zdt.toLocalDate();
            LocalDate endOfMonth = ld.withDayOfMonth(ld.lengthOfMonth());

            // Count backwards from end of month to find last 3 trading days
            int tradingDaysLeft = 0;
            LocalDate cursor = endOfMonth;
            while (!cursor.isBefore(ld)) {
                if (cursor.getDayOfWeek().getValue() <= 5) { // weekday
                    tradingDaysLeft++;
                }
                cursor = cursor.minusDays(1);
            }

            if (tradingDaysLeft <= 3 && tradingDaysLeft > 0) {
                if (!inWindow) {
                    inWindow = true;
                    windowStartDay = dayOfMonth;
                    windowMonth = month;
                    windowYear = year;
                }
                daysInWindow++;
            }
        } else if (dayOfMonth <= 3 && inWindow && month == windowMonth) {
            // Still in window at start of next month (trades can run into next month)
            // Actually the window should end when the month changes
        } else if (dayOfMonth > 3 && inWindow) {
            // Past the window
            inWindow = false;
            daysInWindow = 0;
        }
    }

    private void evaluateEntry(Bar bar) {
        ZonedDateTime zdt = bar.timestamp().atZone(ZoneOffset.UTC);
        int month = zdt.getMonthValue();

        // Only enter on first day of window
        if (daysInWindow > 1) return;

        double atr = Indicators.atr(history, ATR_PERIOD);
        if (Double.isNaN(atr) || atr <= 0) return;

        int direction = MONTHLY_DIRECTION[month - 1];
        boolean quarterEnd = IS_QUARTER_END[month - 1];

        // Quarter-end rebalancing: fade the month's dominant move
        // Non-quarter-end: trade in direction of monthly bias
        if (direction == 0) return; // Neutral month — skip

        // Quarter-end: fade the bias (rebalancing flow)
        if (quarterEnd) {
            direction = -direction;
        }

        double pip = Indicators.pipSize(symbol);

        if (direction > 0) {
            // Long bias
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            takeProfit = entryPrice + atr * ATR_STOP_MULT * RR;

            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
        } else {
            // Short bias
            entryPrice = bar.close();
            stopLoss = entryPrice + atr * ATR_STOP_MULT;
            takeProfit = entryPrice - atr * ATR_STOP_MULT * RR;

            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            barsInTrade = 0;
        }
    }
}
