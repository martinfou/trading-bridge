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
 * <h3>Entry modes</h3>
 * <ul>
 *   <li><b>Directional</b> — enters immediately at event time in a fixed direction (ECB, BoJ, NZD Fade)</li>
 *   <li><b>Bidirectional</b> — waits 1 bar, reads the bar's direction, enters WITH the momentum
 *       (CPI: bearish event bar → SELL, bullish event bar → BUY)</li>
 * </ul>
 *
 * <h3>Exit modes</h3>
 * <ul>
 *   <li><b>Fixed SL</b> — STOP order at stopLossPips + onBar check</li>
 *   <li><b>Trailing SL</b> — recalculated from extremePrice each bar</li>
 *   <li><b>Fixed TP</b> — LIMIT order or end-of-week close</li>
 * </ul>
 *
 * <h3>Position sizing</h3>
 * quantity = nearest 100 units to {@code (capital × riskPct) / (stopLossPips × pipValue)}.
 * Default capital: $1,000.
 */
public abstract class NewsWeeklyStrategy implements Strategy {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    protected static final double DEFAULT_CAPITAL = LotSizing.DEFAULT_STARTING_CAPITAL; // $1,000

    protected final String name;
    protected final String symbol;
    protected final double quantity;
    protected final List<Order> pending = new ArrayList<>();
    protected final List<Bar> history = new ArrayList<>();

    private final Instant eventStart;
    private final Instant weekEnd;
    private final int stopLossPips;
    private final int takeProfitPips;
    private final int trailingStopPips;
    private final boolean bidirectional;

    private Phase phase = Phase.WAITING;
    private Order.Side entrySide;
    private double entryPrice;
    private double stopPrice;
    private double extremePrice;

    private enum Phase { WAITING, ENTERED, EXPIRED }

    // ====== Constructors ======

    /** Directional mode: enters at event in a fixed direction. Quantity from risk %. */
    protected NewsWeeklyStrategy(String name, String symbol,
                                  Instant eventStart, Instant weekEnd,
                                  int stopLossPips, int takeProfitPips,
                                  Order.Side entrySide,
                                  double riskPct) {
        this(name, symbol, eventStart, weekEnd, stopLossPips, takeProfitPips,
             entrySide, riskPct, DEFAULT_CAPITAL, 0, false);
    }

    /** Directional with trailing stop. */
    protected NewsWeeklyStrategy(String name, String symbol,
                                  Instant eventStart, Instant weekEnd,
                                  int stopLossPips, int takeProfitPips,
                                  Order.Side entrySide,
                                  double riskPct, int trailingStopPips) {
        this(name, symbol, eventStart, weekEnd, stopLossPips, takeProfitPips,
             entrySide, riskPct, DEFAULT_CAPITAL, trailingStopPips, false);
    }

    /** Bidirectional mode: reads event bar direction, then enters with momentum. */
    protected NewsWeeklyStrategy(String name, String symbol,
                                  Instant eventStart, Instant weekEnd,
                                  int stopLossPips, int takeProfitPips,
                                  double riskPct) {
        this(name, symbol, eventStart, weekEnd, stopLossPips, takeProfitPips,
             null, riskPct, DEFAULT_CAPITAL, 0, true);
    }

    /** Full constructor. */
    private NewsWeeklyStrategy(String name, String symbol,
                                Instant eventStart, Instant weekEnd,
                                int stopLossPips, int takeProfitPips,
                                Order.Side entrySide,
                                double riskPct, double capital,
                                int trailingStopPips, boolean bidirectional) {
        this.name = name;
        this.symbol = symbol;
        this.eventStart = eventStart;
        this.weekEnd = weekEnd;
        this.stopLossPips = stopLossPips;
        this.takeProfitPips = takeProfitPips;
        this.entrySide = entrySide;
        this.trailingStopPips = trailingStopPips;
        this.bidirectional = bidirectional;

        // Calculate quantity from risk budget
        if (stopLossPips > 0) {
            double raw = (capital * riskPct) / (stopLossPips * pipValue(symbol));
            this.quantity = roundUnits(raw);
        } else {
            this.quantity = LotSizing.lotsToUnits(0.01);
        }
    }

    /** Round to nearest 100 units (keeps positions practical). */
    private static double roundUnits(double units) {
        return Math.max(100, Math.round(units / 100.0) * 100.0);
    }

    // ====== Strategy interface ======

    @Override
    public String name() { return name; }

    @Override
    public void onTick(double bid, double ask, long volume) {}

    @Override
    public List<Order> getPendingOrders() { return List.copyOf(pending); }

    @Override
    public void reset() {
        pending.clear();
        history.clear();
        phase = Phase.WAITING;
        entryPrice = 0;
        stopPrice = 0;
        extremePrice = 0;
        if (bidirectional) entrySide = null;
    }

    // ====== Bar logic ======

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);

        double pipVal = pipValue(symbol);

        switch (phase) {

            // — Enter or analyze — //
            case WAITING:
                if (bar.timestamp().isBefore(eventStart)) break;

                if (bidirectional) {
                    // Read 1st bar direction after event → determine entry side
                    Order.Side signal = bar.close() > bar.open() ? Order.Side.BUY : Order.Side.SELL;
                    entrySide = signal;
                    // Enter immediately on this bar's close
                    doEntry(signal, bar.close(), pipVal);
                } else {
                    // Fixed direction: enter at open
                    doEntry(entrySide, bar.open(), pipVal);
                }
                phase = Phase.ENTERED;
                break;

            // — In trade: manage stops — //
            case ENTERED:
                trackExtreme(bar);

                // End-of-week close
                if (bar.timestamp().compareTo(weekEnd) >= 0) {
                    pending.clear();
                    pending.add(closeOrder(entrySide));
                    phase = Phase.EXPIRED;
                    break;
                }

                // Fixed SL: check if price crossed stop
                if (trailingStopPips == 0) {
                    boolean hit = entrySide == Order.Side.SELL
                        ? bar.high() >= stopPrice
                        : bar.low() <= stopPrice;
                    if (hit) {
                        pending.clear();
                        pending.add(closeOrder(entrySide));
                        phase = Phase.EXPIRED;
                        break;
                    }
                    // Re-emit the STOP order (engine may have consumed it)
                    pending.clear();
                    pending.add(new Order(symbol,
                        entrySide == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
                        Order.Type.STOP, quantity, stopPrice).closeOnly());
                    if (takeProfitPips > 0) {
                        double tp = entrySide == Order.Side.BUY
                            ? entryPrice + takeProfitPips * pipVal
                            : entryPrice - takeProfitPips * pipVal;
                        pending.add(new Order(symbol,
                            entrySide == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
                            Order.Type.LIMIT, quantity, tp).closeOnly());
                    }
                }

                // Trailing stop
                if (trailingStopPips > 0) {
                    double newStop;
                    if (entrySide == Order.Side.SELL) {
                        newStop = extremePrice + trailingStopPips * pipVal;
                        if (newStop < stopPrice) stopPrice = newStop;
                    } else {
                        newStop = extremePrice - trailingStopPips * pipVal;
                        if (newStop > stopPrice) stopPrice = newStop;
                    }
                    pending.clear();
                    pending.add(new Order(symbol,
                        entrySide == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
                        Order.Type.STOP, quantity, stopPrice).closeOnly());
                }
                break;

            case EXPIRED:
                // no-op
                break;
        }
    }

    // ====== Entry helper ======

    private void doEntry(Order.Side side, double price, double pipVal) {
        entryPrice = price;
        extremePrice = price;

        if (stopLossPips > 0) {
            stopPrice = side == Order.Side.BUY
                ? price - stopLossPips * pipVal
                : price + stopLossPips * pipVal;
        }

        pending.clear();
        pending.add(new Order(symbol, side, Order.Type.MARKET, quantity, 0));

        // Emit initial stop
        if (stopLossPips > 0 && trailingStopPips == 0) {
            pending.add(new Order(symbol,
                side == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
                Order.Type.STOP, quantity, stopPrice).closeOnly());
        }
    }

    private void trackExtreme(Bar bar) {
        if (entrySide == Order.Side.SELL) {
            extremePrice = Math.min(extremePrice, bar.low());
        } else {
            extremePrice = Math.max(extremePrice, bar.high());
        }
    }

    private Order closeOrder(Order.Side currentSide) {
        return new Order(symbol,
            currentSide == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
            Order.Type.MARKET, quantity, 0).closeOnly();
    }

    // ====== Static helpers ======

    protected static double pipValue(String sym) {
        return switch (sym) {
            case "USD_JPY" -> 0.01;
            default -> 0.0001;
        };
    }

    protected static Instant nyEvent(int year, int month, int day, int hour, int minute) {
        return java.time.ZonedDateTime.of(year, month, day, hour, minute, 0, 0, NY).toInstant();
    }

    protected static Instant weekEndAfter(int year, int month, int day) {
        java.time.LocalDate date = java.time.LocalDate.of(year, month, day);
        java.time.LocalDate sunday = date.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.SUNDAY));
        return sunday.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }
}
