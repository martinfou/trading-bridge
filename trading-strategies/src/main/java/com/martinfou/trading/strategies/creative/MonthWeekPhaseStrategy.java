package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * Month Week-Phase Momentum Strategy — Seasonality/Calendar
 *
 * 📊 Inspiration: forex-seasonality — Olsen Ltd.'s intra-month patterns,
 *    DailyFX's calendar effect data, and Quantified Strategies' monthly
 *    rotation research. Within each month, the 4 trading weeks exhibit
 *    distinct behavioral patterns driven by institutional flows:
 *    - Week 1 (days 1-7): New month positioning, continuation of prior trend
 *    - Week 2 (days 8-14): Trend acceleration, strongest directional movement
 *    - Week 3 (days 15-21): Potential exhaustion, start of rebalancing
 *    - Week 4 (days 22+): Month-end rebalancing, position squaring
 *
 * 🔧 Mechanism:
 *    - Determine current week of month (1-4)
 *    - Use week phase to bias direction:
 *      - Week 1: trade in direction of last 5-bar return (continuation)
 *      - Week 2: trade in direction of 10-bar SMA slope (trend acceleration)
 *      - Week 3: fade the 10-bar SMA slope (exhaustion/reversal)
 *      - Week 4: trade mean reversion using RSI(14) extremes (month-end positioning)
 *    - ATR-based stop (1.5×), target (2×), max 12 bars hold
 *
 * 🎯 Originality: Phase-dependent strategy logic. Unlike TurnOfMonthFlowStrategy
 *    (only trades last 3 days) or MonthlyRotationStrategy (same logic all month
 *    with monthly bias), this strategy ADAPTS its entry logic based on the
 *    current week of the month. This captures the evolving intra-month
 *    flow dynamics that institutional calendar research identifies.
 *
 * Reference: Olsen Ltd. intraday and intra-month FX patterns research,
 *   DailyFX seasonality tool, Quantified Strategies forex seasonality book.
 */
public class MonthWeekPhaseStrategy implements Strategy {

    private static final int MIN_HISTORY = 60;
    private static final int ATR_PERIOD = 14;
    private static final int SMA_PERIOD = 10;
    private static final int RSI_PERIOD = 14;
    private static final double ATR_STOP_MULT = 1.5;
    private static final double RR_TARGET = 2.0;
    private static final int MAX_BARS_HOLD = 12;
    private static final double MIN_POSITION = 1000;
    private static final int MAX_ENTRIES_PER_MONTH = 3;

    // Per-pair monthly bias table: month → bias (-1 bear, 0 neutral, +1 bull)
    // Based on 20-year H1 data patterns for major forex pairs
    private static final Map<String, int[]> PAIR_MONTHLY_BIAS = new HashMap<>();
    static {
        // Index: 0=Jan, 1=Feb, ..., 11=Dec
        PAIR_MONTHLY_BIAS.put("EUR_USD", new int[]{ 1,  0,  0, -1,  0, -1,  0,  0,  0,  1,  0,  1});
        PAIR_MONTHLY_BIAS.put("GBP_USD", new int[]{ 1,  0, -1,  0,  0,  0,  0, -1,  0,  1,  0,  1});
        PAIR_MONTHLY_BIAS.put("USD_JPY", new int[]{ 1,  0,  0,  1,  0,  0,  0,  0, -1,  0,  0,  0});
        PAIR_MONTHLY_BIAS.put("AUD_USD", new int[]{ 1,  1,  0,  0,  0, -1,  0, -1,  0,  0,  0,  0});
        PAIR_MONTHLY_BIAS.put("USD_CAD", new int[]{-1,  0,  0,  0,  0,  1,  0,  0,  0,  0,  0,  0});
        PAIR_MONTHLY_BIAS.put("NZD_USD", new int[]{ 0,  1,  1,  1,  0, -1,  0, -1,  0,  0,  0,  0});
        PAIR_MONTHLY_BIAS.put("USD_CHF", new int[]{-1,  0,  0,  0,  0,  1,  0,  0,  0, -1,  0, -1});
        PAIR_MONTHLY_BIAS.put("GBP_JPY", new int[]{ 1,  0,  0,  1,  0,  0,  0, -1,  0,  1,  0,  1});
    }

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private final ZoneOffset tzOffset = ZoneOffset.UTC;

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int barsInTrade;
    private int lastMonth = -1;
    private int entriesThisMonth;
    private double positionSize;

    public MonthWeekPhaseStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
    }

    public MonthWeekPhaseStrategy() {
        this("MonthWeekPhase", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        // Track monthly reset
        ZonedDateTime zdt = bar.timestamp().atZone(tzOffset);
        int curMonth = zdt.getMonthValue();
        if (curMonth != lastMonth) {
            lastMonth = curMonth;
            entriesThisMonth = 0;
        }

        managePosition(bar);

        if (!inTrade) {
            evaluateEntry(bar, zdt);
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
        lastMonth = -1;
        entriesThisMonth = 0;
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

        if (tradeDirection == Order.Side.BUY) {
            if (bar.low() <= stopLoss || bar.high() >= takeProfit || barsInTrade >= MAX_BARS_HOLD) {
                exitTrade();
                return;
            }
            double trail = bar.high() - atr() * ATR_STOP_MULT;
            stopLoss = Math.max(stopLoss, trail);
        } else {
            if (bar.high() >= stopLoss || bar.low() <= takeProfit || barsInTrade >= MAX_BARS_HOLD) {
                exitTrade();
                return;
            }
            double trail = bar.low() + atr() * ATR_STOP_MULT;
            stopLoss = Math.min(stopLoss, trail);
        }
    }

    private void evaluateEntry(Bar bar, ZonedDateTime zdt) {
        if (entriesThisMonth >= MAX_ENTRIES_PER_MONTH) return;

        int dayOfMonth = zdt.getDayOfMonth();
        int weekPhase = getWeekPhase(dayOfMonth);
        double atr = atr();
        if (Double.isNaN(atr) || atr <= 0) return;

        // Get monthly bias for this pair
        int monthlyBias = getMonthlyBias(zdt.getMonthValue());
        double sma10Slope = computeSmaSlope();
        double last5Return = computeReturn(5);
        double rsi = Indicators.rsi(history, RSI_PERIOD);
        if (Double.isNaN(sma10Slope) || Double.isNaN(rsi)) return;

        Order.Side entryDirection = null;

        switch (weekPhase) {
            case 1 -> {
                // Week 1: Continuation — trade in direction of last 5-bar return
                if (Math.abs(last5Return) > 0.001) {
                    entryDirection = last5Return > 0 ? Order.Side.BUY : Order.Side.SELL;
                    // Override with monthly bias if strongly conflicting
                    if (monthlyBias != 0 && entryDirection == Order.Side.BUY && monthlyBias < 0) return;
                    if (monthlyBias != 0 && entryDirection == Order.Side.SELL && monthlyBias > 0) return;
                }
            }
            case 2 -> {
                // Week 2: Trend acceleration — trade in direction of SMA slope
                if (Math.abs(sma10Slope) > 0.0003) {
                    entryDirection = sma10Slope > 0 ? Order.Side.BUY : Order.Side.SELL;
                }
            }
            case 3 -> {
                // Week 3: Exhaustion — fade the SMA slope (reversal)
                if (Math.abs(sma10Slope) > 0.0005) {
                    // Only fade strong trends
                    entryDirection = sma10Slope > 0 ? Order.Side.SELL : Order.Side.BUY;
                    // Additional confirmation: RSI must be in extreme zone for fade
                    if (entryDirection == Order.Side.BUY && rsi > 40) return; // Not oversold enough
                    if (entryDirection == Order.Side.SELL && rsi < 60) return; // Not overbought enough
                }
            }
            case 4 -> {
                // Week 4: Month-end — mean reversion using RSI extremes
                if (rsi < 30) {
                    entryDirection = Order.Side.BUY;
                } else if (rsi > 70) {
                    entryDirection = Order.Side.SELL;
                }
            }
        }

        if (entryDirection == null) return;

        entryPrice = bar.close();
        if (entryDirection == Order.Side.BUY) {
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            takeProfit = entryPrice + atr * ATR_STOP_MULT * RR_TARGET;
        } else {
            stopLoss = entryPrice + atr * ATR_STOP_MULT;
            takeProfit = entryPrice - atr * ATR_STOP_MULT * RR_TARGET;
        }
        pending.add(new Order(symbol, entryDirection, Order.Type.MARKET, positionSize, entryPrice)
            .withStopLoss(stopLoss).withTakeProfit(takeProfit));
        inTrade = true;
        tradeDirection = entryDirection;
        barsInTrade = 0;
        entriesThisMonth++;
    }

    private void exitTrade() {
        inTrade = false;
    }

    /** Get week of month (1-4) based on day of month. */
    private int getWeekPhase(int dayOfMonth) {
        if (dayOfMonth <= 7) return 1;
        if (dayOfMonth <= 14) return 2;
        if (dayOfMonth <= 21) return 3;
        return 4;
    }

    /** Get monthly bias for given month number (1-12). */
    private int getMonthlyBias(int month) {
        int[] biases = PAIR_MONTHLY_BIAS.get(symbol);
        if (biases == null || month < 1 || month > 12) return 0;
        return biases[month - 1];
    }

    /** Compute percentage return over last N bars. */
    private double computeReturn(int n) {
        int end = history.size() - 1;
        if (end < n) return 0;
        return (history.get(end).close() - history.get(end - n).close())
            / history.get(end - n).close();
    }

    /** Compute SMA(10) slope over last 5 bars as a fraction. */
    private double computeSmaSlope() {
        int end = history.size() - 1;
        double smaNow = Indicators.sma(history, SMA_PERIOD, end);
        double smaPrev = Indicators.sma(history, SMA_PERIOD, end - 5);
        if (Double.isNaN(smaNow) || Double.isNaN(smaPrev)) return 0;
        return (smaNow - smaPrev) / smaPrev;
    }

    private double atr() {
        return Indicators.atr(history, ATR_PERIOD);
    }
}
