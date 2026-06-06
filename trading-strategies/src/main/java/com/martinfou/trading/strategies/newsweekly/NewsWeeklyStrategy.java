package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.LotSizing;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Base for NEWS/SENTIMENT weekly strategies — zero technical indicators.
 *
 * Strategy triggers at a specific calendar event window (e.g. CPI release,
 * ECB decision, GDP print) and holds until SL/TP hit or end of the week.
 *
 * Backtest timing: uses bar timestamps. If a bar falls inside the event
 * window AND the event has occurred (timestamp >= eventStart), the strategy
 * places its one entry order per week.
 */
public abstract class NewsWeeklyStrategy implements Strategy {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    protected static final double DEFAULT_QTY = LotSizing.lotsToUnits(0.01); // 1,000 units = micro lot

    protected final String name;
    protected final String symbol;
    protected final List<Order> pending = new ArrayList<>();
    protected final List<Bar> history = new ArrayList<>();

    /** The calendar event start (UTC). Entry occurs on the first bar at or after this. */
    private final Instant eventStart;
    /** End of the valid week (Sunday 00:00 UTC after the strategy week). */
    private final Instant weekEnd;
    /** Max pips to hold before stopping out. <= 0 means no fixed SL. */
    private final int stopLossPips;
    /** Min pips to take profit. <= 0 means no fixed TP. */
    private final int takeProfitPips;

    private boolean entrySent;
    private double entryPrice;
    private double stopPrice;
    private double targetPrice;
    private Order.Side entrySide;

    protected NewsWeeklyStrategy(String name, String symbol,
                                  Instant eventStart, Instant weekEnd,
                                  int stopLossPips, int takeProfitPips,
                                  Order.Side entrySide) {
        this.name = name;
        this.symbol = symbol;
        this.eventStart = eventStart;
        this.weekEnd = weekEnd;
        this.stopLossPips = stopLossPips;
        this.takeProfitPips = takeProfitPips;
        this.entrySide = entrySide;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
        // news strategies don't use tick data
    }

    @Override
    public List<Order> getPendingOrders() {
        return List.copyOf(pending);
    }

    @Override
    public void reset() {
        pending.clear();
        history.clear();
        entrySent = false;
        entryPrice = 0;
        stopPrice = 0;
        targetPrice = 0;
    }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) {
            return;
        }
        history.add(bar);

        // End-of-week exit: close any open position on Friday after 17:00 ET
        if (entrySent && bar.timestamp().compareTo(weekEnd) >= 0) {
            Order closeOrder = new Order(symbol,
                entrySide == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
                Order.Type.MARKET, DEFAULT_QTY, 0).closeOnly();
            pending.clear();
            pending.add(closeOrder);
            return;
        }

        // Event window entry: first bar at or after event start
        if (!entrySent && !bar.timestamp().isBefore(eventStart)) {
            double price = bar.open();
            entryPrice = price;
            entrySent = true;

            Order entry = new Order(symbol, entrySide, Order.Type.MARKET, DEFAULT_QTY, 0);
            pending.clear();
            pending.add(entry);

            // If fixed SL/TP, place exit orders simultaneously
            double pipValue = pipValue(symbol);
            if (stopLossPips > 0) {
                stopPrice = entrySide == Order.Side.BUY
                    ? price - stopLossPips * pipValue
                    : price + stopLossPips * pipValue;
            }
            if (takeProfitPips > 0) {
                targetPrice = entrySide == Order.Side.BUY
                    ? price + takeProfitPips * pipValue
                    : price - takeProfitPips * pipValue;
            }
        }
    }

    /** Returns pip value for standard forex pairs. */
    protected static double pipValue(String sym) {
        switch (sym) {
            case "EUR_USD": case "GBP_USD": case "AUD_USD": case "NZD_USD":
                return 0.0001;
            case "USD_JPY":
                return 0.01;
            case "USD_CAD": case "USD_CHF":
                return 0.0001;
            default:
                return 0.0001;
        }
    }

    /** Build UTC instant for a US eastern time (America/New_York) event. */
    protected static Instant nyEvent(int year, int month, int day, int hour, int minute) {
        return java.time.ZonedDateTime.of(year, month, day, hour, minute, 0, 0, NY)
            .toInstant();
    }

    /** End of the trading week: Sunday 00:00 UTC after the strategy's Friday. */
    protected static Instant weekEndAfter(int year, int month, int day) {
        // Find next Sunday after the given date
        java.time.LocalDate date = java.time.LocalDate.of(year, month, day);
        java.time.LocalDate sunday = date.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.SUNDAY));
        return sunday.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }
}
