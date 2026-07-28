package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * VolWindowMonthEnd — Variation of TurnOfMonthFlowStrategy + PostNewsAbsorption.
 *
 * 📊 Variation concept:
 *   TurnOfMonthFlowStrategy trades every month-end window unconditionally.
 *   PostNewsAbsorptionStrategy detects volatility expansions but has no calendar awareness.
 *   This combines both: trade month-end windows ONLY when preceded by a volatility spike
 *   (e.g., central bank event, NFP, etc.). The hypothesis is that month-end flows are
 *   amplified after catalyst events, whereas quiet month-ends are noise.
 *
 * 🔧 Mechanism:
 *   - Detect last 3 trading days of each month (same as TurnOfMonthFlowStrategy)
 *   - Volatility pre-condition: at least one bar in the last 5 with range > 1.5× median(20)
 *     (proxy for "something happened" — CB meeting, NFP, shock)
 *   - Direction: month-end flow direction based on monthly bias
 *   - Entry: first bar of window that meets volatility condition
 *   - Exit: ATR trailing stop (1.5×), max 8 bars, window close
 *
 * 🎯 Originality vs existing:
 *   TurnOfMonthFlowStrategy: same window detection, but adds volatility pre-filter.
 *   PostNewsAbsorptionStrategy: same range/median detection, but restricted to moon-end.
 *   MidMonthExhaustionStrategy: mid-month only, opposite window.
 *   MonthPhaseMomentumStrategy: phase 3 uses profit-taking fade, not volatility.
 */
public class VolWindowMonthEndStrategy implements Strategy {

    private static final int MIN_HISTORY = 60;
    private static final int ATR_PERIOD = 14;
    private static final int RANGE_MEDIAN_BARS = 20;
    private static final double EXPANSION_MULT = 1.5;
    private static final int EXPANSION_LOOKBACK = 5;
    private static final double ATR_STOP_MULT = 1.5;
    private static final int MAX_BARS_HOLD = 8;
    private static final double MIN_POSITION = 1000;

    // Month-end window: last 3 trading days
    private static final int WINDOW_DAYS = 3;

    // Monthly bias from TurnOfMonthFlowStrategy: 1=long, -1=short, 0=neutral
    // EUR/USD: Jul(7)=0, Aug(8)=-1, Sep(9)=-1
    private static final Map<String, int[]> MONTHLY_BIAS = new HashMap<>();
    static {
        MONTHLY_BIAS.put("EUR_USD", new int[]{ 1, 0, 0, 0, 0, 0, 0, -1, -1, 0, 0, 1});
        MONTHLY_BIAS.put("GBP_USD", new int[]{ 1, 0, -1, 0, 0, 0, 0, 0, 0, 0, 0, 1});
        MONTHLY_BIAS.put("USD_JPY", new int[]{ 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0});
        MONTHLY_BIAS.put("USD_CAD", new int[]{-1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
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
    private int cooldownBars;
    private double positionSize = MIN_POSITION;

    // Month tracking
    private int tradingDayOfMonth = 0;
    private int lastProcessedDay = -1;
    private int currentMonth = -1;

    public VolWindowMonthEndStrategy() {
        this("VolWindowMonthEnd", "EUR_USD");
    }

    public VolWindowMonthEndStrategy(String name, String symbol) {
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

        updateTradingDay(bar);

        // Manage existing position
        if (inTrade) {
            barsInTrade++;
            highestSinceEntry = Math.max(highestSinceEntry, bar.high());
            lowestSinceEntry = Math.min(lowestSinceEntry, bar.low());

            // ATR trailing stop
            double atr = Indicators.atr(history, ATR_PERIOD);
            if (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss) {
                closePosition(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss) {
                closePosition(bar);
                return;
            }

            // Trail stop
            if (tradeDirection == Order.Side.BUY) {
                stopLoss = Math.max(stopLoss, highestSinceEntry - atr * ATR_STOP_MULT);
            } else {
                stopLoss = Math.min(stopLoss, lowestSinceEntry + atr * ATR_STOP_MULT);
            }

            // Max bars hold
            if (barsInTrade >= MAX_BARS_HOLD) {
                closePosition(bar);
            }
            return;
        }

        // Cooldown
        if (cooldownBars > 0) { cooldownBars--; return; }

        // Check if we're in the month-end window (last WINDOW_DAYS trading days)
        int tradingDaysInMonth = estimateTradingDaysInMonth(bar);
        boolean isMonthEnd = tradingDayOfMonth > 0
            && tradingDayOfMonth >= (tradingDaysInMonth - WINDOW_DAYS);

        if (!isMonthEnd) return;

        // Check volatility pre-condition: any bar in last EXPANSION_LOOKBACK exceeded 1.5× median range
        double medianRange = medianRange(RANGE_MEDIAN_BARS);
        boolean volSpike = false;
        for (int i = history.size() - EXPANSION_LOOKBACK; i < history.size(); i++) {
            Bar b = history.get(i);
            if ((b.high() - b.low()) > medianRange * EXPANSION_MULT) {
                volSpike = true;
                break;
            }
        }

        if (!volSpike) return;

        // Determine direction from monthly bias + quarter-end fade
        int month = bar.timestamp().atZone(nyZone).getMonthValue();
        boolean isQuarterEnd = month == 3 || month == 6 || month == 9 || month == 12;
        int[] biases = MONTHLY_BIAS.get(symbol);
        if (biases == null) return;

        int bias = biases[month - 1];
        if (bias == 0) return;

        // Quarter-end: fade the monthly bias (institutional rebalancing)
        // Non-quarter-end: trade with the monthly bias
        Order.Side direction;
        if (isQuarterEnd) {
            direction = bias > 0 ? Order.Side.SELL : Order.Side.BUY;
        } else {
            direction = bias > 0 ? Order.Side.BUY : Order.Side.SELL;
        }

        // Enter on close of current bar if it confirms direction
        double atr = Indicators.atr(history, ATR_PERIOD);
        if (direction == Order.Side.BUY && bar.close() > bar.open()) {
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            takeProfit = entryPrice + atr * ATR_STOP_MULT * 2.0;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice));
            enterTrade(Order.Side.BUY, entryPrice, stopLoss, takeProfit);
        } else if (direction == Order.Side.SELL && bar.close() < bar.open()) {
            entryPrice = bar.close();
            stopLoss = entryPrice + atr * ATR_STOP_MULT;
            takeProfit = entryPrice - atr * ATR_STOP_MULT * 2.0;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice));
            enterTrade(Order.Side.SELL, entryPrice, stopLoss, takeProfit);
        }
    }

    private void enterTrade(Order.Side side, double entry, double sl, double tp) {
        inTrade = true;
        tradeDirection = side;
        entryPrice = entry;
        stopLoss = sl;
        takeProfit = tp;
        barsInTrade = 0;
        highestSinceEntry = entry;
        lowestSinceEntry = entry;
    }

    private void closePosition(Bar bar) {
        pending.add(new Order(symbol,
            tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
            Order.Type.MARKET, positionSize, bar.close()));
        inTrade = false;
        cooldownBars = 3;
    }

    private void updateTradingDay(Bar bar) {
        ZonedDateTime zdt = bar.timestamp().atZone(nyZone);
        int dayOfYear = zdt.getDayOfYear();
        int month = zdt.getMonthValue();

        if (dayOfYear != lastProcessedDay) {
            lastProcessedDay = dayOfYear;
            if (month != currentMonth) {
                currentMonth = month;
                tradingDayOfMonth = 1;
            } else {
                tradingDayOfMonth++;
            }
        }
    }

    /** Rough estimate: most months have 19-22 trading days */
    private int estimateTradingDaysInMonth(Bar bar) {
        ZonedDateTime zdt = bar.timestamp().atZone(nyZone);
        YearMonth ym = YearMonth.from(zdt);
        // Count weekdays in the month
        int count = 0;
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            DayOfWeek dow = ym.atDay(d).getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) count++;
        }
        return count;
    }

    private double medianRange(int period) {
        if (history.size() < period) return 0;
        double[] ranges = new double[period];
        for (int i = 0; i < period; i++) {
            Bar b = history.get(history.size() - 1 - i);
            ranges[i] = b.high() - b.low();
        }
        Arrays.sort(ranges);
        return ranges[period / 2];
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
        tradingDayOfMonth = 0;
        lastProcessedDay = -1;
        currentMonth = -1;
    }
}
