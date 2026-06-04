package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * MonthPhaseMomentum — Seasonality/Calendar
 *
 * 📊 Inspiration: forex-seasonality (DailyFX, Quantified Strategies),
 *    forex-sentiment (institutional flow patterns by month phase).
 *    First week of month = capital inflows (pension funds, institutional
 *    rebalancing). Last week = profit-taking, portfolio rebalancing.
 *
 * 🔧 Mechanism:
 *    - Track trading day within month (1-based, counting only trading days)
 *    - Phase 1 (days 1-5): Momentum in direction of prior month's trend
 *    - Phase 2 (days 6-20): Trend continuation with EMA filter
 *    - Phase 3 (days 21+): Reversal fade (profit-taking bias)
 *    - Entry via daily close breakout above/below 10-EMA
 *    - Exit: ATR trailing stop at 1.5×, max 8 bars hold
 *
 * 🎯 Novelty: Month-phase-aware direction bias. No existing strategy
 *    changes behavior by week-of-month position.
 */
public class MonthPhaseMomentumStrategy implements Strategy {

    private static final int ATR_PERIOD = 14;
    private static final double ATR_SL_MULT = 1.5;
    private static final double RR_TARGET = 2.0;
    private static final int MAX_BARS_HOLD = 8;
    private static final int MIN_HISTORY = 30;
    private static final int COOLDOWN_BARS = 5;
    private static final int FAST_EMA = 10;
    private static final double MIN_POSITION = 1000;

    // Month phase boundaries (trading days within month)
    private static final int PHASE1_END = 5;    // First week
    private static final int PHASE2_END = 20;   // End of trading month

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

    // Month phase state
    private int tradingDayOfMonth = 0;
    private int lastProcessedDay = -1;
    private int lastProcessedMonth = -1;
    private double priorMonthReturn = 0;
    private double monthStartPrice = 0;
    private boolean monthStarted = false;

    public MonthPhaseMomentumStrategy() {
        this("MonthPhaseMomentum", "EUR/USD");
    }

    public MonthPhaseMomentumStrategy(String name, String symbol) {
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

        ZonedDateTime zdt = bar.timestamp().atZone(tz);
        int day = zdt.getDayOfYear();
        int month = zdt.getMonthValue();

        // Detect new trading day
        if (day != lastProcessedDay) {
            // Detect month change
            if (lastProcessedMonth > 0 && month != lastProcessedMonth) {
                // New month: calculate prior month return
                if (monthStarted && monthStartPrice > 0) {
                    priorMonthReturn = (bar.open() - monthStartPrice) / monthStartPrice;
                }
                // Reset month tracking
                tradingDayOfMonth = 0;
                monthStartPrice = bar.open();
                monthStarted = true;
            }

            // Detect weekend gap (day change with no month change)
            if (lastProcessedDay > 0 && day == lastProcessedDay + 1) {
                tradingDayOfMonth++;
            } else if (lastProcessedDay > 0 && day > lastProcessedDay + 1) {
                // Skipped days (weekend/holiday) — still count as next trading day
                tradingDayOfMonth++;
            } else if (tradingDayOfMonth == 0) {
                tradingDayOfMonth = 1;
            }

            lastProcessedDay = day;
            lastProcessedMonth = month;
        }

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

    private void evaluateEntry(Bar bar) {
        if (tradingDayOfMonth < 1) return;

        double atr = Indicators.atr(history, ATR_PERIOD);
        if (Double.isNaN(atr) || atr <= 0) return;

        double ema10 = Indicators.emaLatest(history, FAST_EMA);
        if (Double.isNaN(ema10)) return;

        double close = bar.close();
        int phase = getPhase(tradingDayOfMonth);

        switch (phase) {
            case 1 -> {
                // Phase 1: Momentum in direction of prior month's trend
                if (priorMonthReturn > 0 && close > ema10) {
                    // Bullish continuation: buy
                    goLong(bar, atr);
                } else if (priorMonthReturn < 0 && close < ema10) {
                    // Bearish continuation: sell
                    goShort(bar, atr);
                }
            }
            case 2 -> {
                // Phase 2: Pure trend continuation
                boolean trendUp = close > ema10 && close > Indicators.sma(history, 20, history.size() - 1);
                boolean trendDown = close < ema10 && close < Indicators.sma(history, 20, history.size() - 1);

                if (trendUp) {
                    goLong(bar, atr);
                } else if (trendDown) {
                    goShort(bar, atr);
                }
            }
            case 3 -> {
                // Phase 3: Reversal fade — fade the prior month direction
                if (priorMonthReturn > 0 && close < ema10) {
                    // Was bullish, now fading: sell
                    goShort(bar, atr);
                } else if (priorMonthReturn < 0 && close > ema10) {
                    // Was bearish, now fading: buy
                    goLong(bar, atr);
                }
            }
        }
    }

    private void goLong(Bar bar, double atr) {
        entryPrice = bar.close();
        stopLoss = entryPrice - atr * ATR_SL_MULT;
        takeProfit = entryPrice + atr * ATR_SL_MULT * RR_TARGET;
        highestSinceEntry = entryPrice;
        pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice));
        inTrade = true;
        tradeDirection = Order.Side.BUY;
        barsInTrade = 0;
        cooldownBars = 0;
    }

    private void goShort(Bar bar, double atr) {
        entryPrice = bar.close();
        stopLoss = entryPrice + atr * ATR_SL_MULT;
        takeProfit = entryPrice - atr * ATR_SL_MULT * RR_TARGET;
        lowestSinceEntry = entryPrice;
        pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice));
        inTrade = true;
        tradeDirection = Order.Side.SELL;
        barsInTrade = 0;
        cooldownBars = 0;
    }

    private int getPhase(int tradingDay) {
        if (tradingDay <= PHASE1_END) return 1;
        if (tradingDay <= PHASE2_END) return 2;
        return 3;
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
        lastProcessedMonth = -1;
        priorMonthReturn = 0;
        monthStartPrice = 0;
        monthStarted = false;
    }
}
