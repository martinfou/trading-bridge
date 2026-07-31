package com.martinfou.trading.examples;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * TurnOfMonthFlowWindowStrategy — Deep dive V2 variant of TurnOfMonthFlowStrategy.
 *
 * Fixes applied on top of the active TurnOfMonthFlowStrategy:
 *   1. tradeCountThisMonth reset on month change (critical: was never reset,
 *      limiting the strategy to a single trade over 20 years)
 *   2. UTC day counting instead of America/New_York (timezone pitfall)
 *
 * Conceptual change: entry allowed on ANY bar of the 3-day month-end window
 * (not only the very first bar), capped at 1 trade per month. Tests whether
 * the month-end flow edge exists when entries are not restricted to the
 * 00:00 UTC low-liquidity slot.
 */
public class TurnOfMonthFlowWindowStrategy implements Strategy {

    private static final int MIN_HISTORY = 60;
    private static final int ATR_PERIOD = 14;
    private static final int RANGE_MEDIAN = 20;
    private static final double ATR_STOP_MULT = 1.5;
    private static final double RR_TARGET = 2.0;
    private static final int MAX_BARS_HOLD = 10;
    private static final double MIN_POSITION = 1000;
    private static final int COOLDOWN_BARS = 5;
    private static final int WINDOW_DAYS = 3;

    private static final boolean[] IS_QUARTER_END = {
        false, false, true, false, false, true, false, false, true, false, false, true
    };

    private static final Map<String, int[]> MONTHLY_BIAS = new HashMap<>();
    static {
        MONTHLY_BIAS.put("EUR_USD", new int[]{ 1, 0, 0, 0, 0, 0, 0, -1, -1, 0, 0, 1});
        MONTHLY_BIAS.put("GBP_USD", new int[]{ 1, 0, -1, 0, 0, 0, 0, 0, 0, 0, 0, 1});
        MONTHLY_BIAS.put("USD_JPY", new int[]{ 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0});
        MONTHLY_BIAS.put("AUD_USD", new int[]{ 1, 1, 1, 0, 0, 0, 0, 0, -1, 0, 0, 0});
        MONTHLY_BIAS.put("USD_CAD", new int[]{-1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        MONTHLY_BIAS.put("NZD_USD", new int[]{ 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0});
        MONTHLY_BIAS.put("GBP_JPY", new int[]{ 0, 0, 0, 1, 0, 0, 0, -1, 0, 1, 0, 1});
    }

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private final ZoneOffset utc = ZoneOffset.UTC;

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
    private int windowMonth = -1;
    private int tradeCountThisMonth;
    private int lastTrackedYear = -1;
    private int lastTrackedMonth = -1;

    private int[] monthlyBias;

    public TurnOfMonthFlowWindowStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
        this.monthlyBias = MONTHLY_BIAS.getOrDefault(symbol, new int[]{0,0,0,0,0,0,0,0,0,0,0,0});
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        int barMonth = bar.timestamp().atZone(utc).getMonthValue();
        int barYear = bar.timestamp().atZone(utc).getYear();

        if (lastTrackedYear != barYear || lastTrackedMonth != barMonth) {
            lastTrackedYear = barYear;
            lastTrackedMonth = barMonth;
            tradeCountThisMonth = 0;
            inWindow = false;
            windowMonth = -1;
        }

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
        windowMonth = -1;
        tradeCountThisMonth = 0;
        lastTrackedYear = -1;
        lastTrackedMonth = -1;
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

        if (stopHit || tpHit || barsInTrade >= MAX_BARS_HOLD || !inWindow) {
            closePosition(bar.close());
            return;
        }

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
        if (tradeCountThisMonth >= 1) return; // one trade per month max

        int bias = computeEffectiveBias(barMonth);
        if (bias == 0) return;

        double atr = atr();
        if (Double.isNaN(atr) || atr <= 0) return;

        // Momentum confirmation: bar closes in the bias direction
        boolean validEntry;
        if (bias > 0) {
            validEntry = bar.close() > bar.open() || bar.close() > history.get(history.size() - 2).close();
        } else {
            validEntry = bar.close() < bar.open() || bar.close() < history.get(history.size() - 2).close();
        }
        if (!validEntry) return;

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

    private int computeEffectiveBias(int month) {
        int bias = monthlyBias[month - 1];
        if (bias == 0) return 0;
        return IS_QUARTER_END[month - 1] ? -bias : bias;
    }

    private void updateMonthWindow(Bar bar) {
        ZonedDateTime zdt = bar.timestamp().atZone(utc);
        int year = zdt.getYear();
        int month = zdt.getMonthValue();
        int dayOfMonth = zdt.getDayOfMonth();

        if (dayOfMonth < 25) {
            inWindow = false;
            return;
        }

        LocalDate ld = zdt.toLocalDate();
        LocalDate endOfMonth = ld.withDayOfMonth(ld.lengthOfMonth());

        int tradingDaysLeft = 0;
        LocalDate cursor = endOfMonth;
        while (!cursor.isBefore(ld)) {
            if (cursor.getDayOfWeek().getValue() <= 5) {
                tradingDaysLeft++;
            }
            cursor = cursor.minusDays(1);
        }

        boolean wasInWindow = inWindow;
        inWindow = (tradingDaysLeft <= WINDOW_DAYS && tradingDaysLeft > 0);

        if (inWindow && !wasInWindow) {
            windowMonth = month;
        }
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
