package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * Turn-of-Month Flow Strategy — Seasonality/Calendar
 *
 * 📊 Inspiration: forex-seasonality — FX_Programmer's quarterly rebalancing,
 *    Olsen Ltd.'s calendar effect research, and the month-end window detection
 *    from the seasonality skill reference (month-end-window-detection.md).
 *
 * 🔧 Mechanism:
 *    - Detect last 3 trading days of each month
 *    - Use per-pair monthly bias matrix to determine direction
 *    - Quarter-end (Mar/Jun/Sep/Dec): FADE the monthly bias
 *      (institutional rebalancing reverses the trend)
 *    - Non-quarter end: TRADE WITH the monthly bias
 *    - Enter on first bar of the window only
 *    - Exit: ATR trailing stop (1.5×), max 10 bars, or window closes
 *
 * 🎯 Originality: Unlike MonthWeekPhaseStrategy (week-of-month phase logic)
 *    and MonthlyRotationStrategy (same logic all month with monthly bias),
 *    this strategy trades ONLY the last-3-days window with a distinct
 *    quarter-end rebalancing fade mechanism.
 *
 * Reference: forex-seasonality skill, month-end-window-detection.md,
 *   "Major Known Seasonal Patterns" table from the skill.
 */
public class TurnOfMonthFlowStrategy implements Strategy {

    private static final int MIN_HISTORY = 60;
    private static final int ATR_PERIOD = 14;
    private static final int RANGE_MEDIAN = 20;
    private static final double ATR_STOP_MULT = 1.5;
    private static final double RR_TARGET = 2.0;
    private static final int MAX_BARS_HOLD = 10;
    private static final double MIN_POSITION = 1000;
    private static final int COOLDOWN_BARS = 5;

    // Quarter-end months: Mar(3), Jun(6), Sep(9), Dec(12)
    private static final boolean[] IS_QUARTER_END = {
        false, false, true,  // Jan, Feb, Mar
        false, false, true,  // Apr, May, Jun
        false, false, true,  // Jul, Aug, Sep
        false, false, true   // Oct, Nov, Dec
    };

    // Monthly bias by pair from forex-seasonality skill research:
    // +1 = long bias, -1 = short bias, 0 = neutral
    // Index: 0=Jan, 1=Feb, ..., 11=Dec
    // EUR/USD: Jan+, Aug/Sep-, Dec+
    private static final Map<String, int[]> MONTHLY_BIAS = new HashMap<>();
    static {
        MONTHLY_BIAS.put("EUR_USD", new int[]{ 1,  0,  0,  0,  0,  0,  0, -1, -1,  0,  0,  1});
        MONTHLY_BIAS.put("GBP_USD", new int[]{ 1,  0, -1,  0,  0,  0,  0,  0,  0,  0,  0,  1});
        MONTHLY_BIAS.put("USD_JPY", new int[]{ 1,  0,  0,  1,  0,  0,  0,  0,  0,  0,  0,  0});
        MONTHLY_BIAS.put("AUD_USD", new int[]{ 1,  1,  1,  0,  0,  0,  0,  0, -1,  0,  0,  0});
        MONTHLY_BIAS.put("USD_CAD", new int[]{-1,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0});
        MONTHLY_BIAS.put("NZD_USD", new int[]{ 0,  1,  1,  1,  0,  0,  0,  0,  0,  0,  0,  0});
        MONTHLY_BIAS.put("USD_CHF", new int[]{ 0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0});
        MONTHLY_BIAS.put("EUR_JPY", new int[]{ 0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0,  0});
        MONTHLY_BIAS.put("GBP_JPY", new int[]{ 0,  0,  0,  1,  0,  0,  0, -1,  0,  1,  0,  1});
    }

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private final ZoneId nyZone = ZoneId.of("America/New_York");

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int barsInTrade;
    private double highestSinceEntry;
    private double lowestSinceEntry;
    private double positionSize;
    private int cooldownBars;
    private boolean inWindow = false;
    private int daysInWindow = 0;
    private int windowMonth = -1;
    private int tradeCountThisMonth;

    // Bias for this pair
    private int[] monthlyBias;

    public TurnOfMonthFlowStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
        this.monthlyBias = MONTHLY_BIAS.getOrDefault(symbol, new int[]{0,0,0,0,0,0,0,0,0,0,0,0});
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

        // Track month for trade counting
        int barMonth = bar.timestamp().atZone(nyZone).getMonthValue();

        // Update turn-of-month window state
        updateMonthWindow(bar);

        managePosition(bar);

        if (!inTrade) {
            if (cooldownBars > 0) { cooldownBars--; return; }
            evaluateEntry(bar, barMonth);
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
        inWindow = false;
        daysInWindow = 0;
        windowMonth = -1;
        tradeCountThisMonth = 0;
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
        boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= takeProfit)
            || (tradeDirection == Order.Side.SELL && bar.low() <= takeProfit);

        // Exit when window closes or max bars reached
        if (stopHit || tpHit || barsInTrade >= MAX_BARS_HOLD || !inWindow) {
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

    private void evaluateEntry(Bar bar, int barMonth) {
        if (!inWindow) return;
        if (daysInWindow > 1) return; // first bar of window only
        if (tradeCountThisMonth >= 1) return; // one trade per month max

        int bias = computeEffectiveBias(barMonth);
        if (bias == 0) return;

        double atr = atr();
        if (Double.isNaN(atr) || atr <= 0) return;

        // Enter with momentum confirmation (bar should close in bias direction)
        boolean validEntry;
        if (bias > 0) {
            validEntry = bar.close() > bar.open() || bar.close() > history.get(history.size() - 2).close();
        } else {
            validEntry = bar.close() < bar.open() || bar.close() < history.get(history.size() - 2).close();
        }

        if (!validEntry) return;

        // Above-median range filter for conviction
        if (!hasAboveMedianRange()) return;

        if (bias > 0) {
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            takeProfit = entryPrice + atr * ATR_STOP_MULT * RR_TARGET;
            highestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
        } else {
            entryPrice = bar.close();
            stopLoss = entryPrice + atr * ATR_STOP_MULT;
            takeProfit = entryPrice - atr * ATR_STOP_MULT * RR_TARGET;
            lowestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
        }
        barsInTrade = 0;
        tradeCountThisMonth++;
    }

    /**
     * Compute effective bias for the given month.
     * Quarter-end: FADE the monthly bias (rebalancing reverse).
     * Non-quarter: TRADE WITH the monthly bias.
     */
    private int computeEffectiveBias(int month) {
        int bias = monthlyBias[month - 1];
        if (bias == 0) return 0;

        boolean quarterEnd = IS_QUARTER_END[month - 1];
        if (quarterEnd) {
            return -bias;
        }
        return bias;
    }

    /**
     * Update the month-end window state.
     * Detects last 3 trading days of each month.
     */
    private void updateMonthWindow(Bar bar) {
        ZonedDateTime zdt = bar.timestamp().atZone(nyZone);
        int year = zdt.getYear();
        int month = zdt.getMonthValue();
        int dayOfMonth = zdt.getDayOfMonth();

        // Only check from day 25 onward (last week of month)
        if (dayOfMonth < 25) {
            inWindow = false;
            daysInWindow = 0;
            return;
        }

        LocalDate ld = zdt.toLocalDate();
        LocalDate endOfMonth = ld.withDayOfMonth(ld.lengthOfMonth());

        // Count trading days (Mon-Fri) from current date to end of month
        int tradingDaysLeft = 0;
        LocalDate cursor = endOfMonth;
        while (!cursor.isBefore(ld)) {
            if (cursor.getDayOfWeek().getValue() <= 5) { // weekday
                tradingDaysLeft++;
            }
            cursor = cursor.minusDays(1);
        }

        boolean wasInWindow = inWindow;
        inWindow = (tradingDaysLeft <= 3 && tradingDaysLeft > 0);

        if (inWindow) {
            if (!wasInWindow) {
                daysInWindow = 1;
                windowMonth = month;
            } else {
                daysInWindow++;
            }
        } else {
            daysInWindow = 0;
        }
    }

    private boolean hasAboveMedianRange() {
        int end = history.size() - 1;
        int lookback = Math.min(RANGE_MEDIAN, end);
        if (lookback < 3) return true;

        double[] ranges = new double[lookback];
        for (int i = 0; i < lookback; i++) {
            int idx = end - lookback + 1 + i;
            ranges[i] = history.get(idx).high() - history.get(idx).low();
        }
        double latestRange = history.get(end).high() - history.get(end).low();
        Arrays.sort(ranges);
        double median = ranges[lookback / 2];
        return latestRange >= median;
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, positionSize, price).closeOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }

    private double atr() {
        return Indicators.atr(history, ATR_PERIOD);
    }
}
