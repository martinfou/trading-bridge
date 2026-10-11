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
 *
 * <p><b>That quantity is INTENT, and it is only arithmetically right when the pair is quoted in the
 * account's own currency.</b> For a cross (AUD/JPY, GBP/CHF) the risk budget is denominated in USD
 * while the stop distance is denominated in the quote currency, so the division needs the
 * quote→account factor to be exact. The class cannot fetch one; the live runner does, and re-derives
 * every unit from the NAV and the risk % through
 * {@code RiskSizing.unitsForRisk(..., quoteToAccountFactor, ...)} with the factor taken from the
 * broker's homeConversions (LiveStrategyRunner.quoteToAccountFactor). Live sizing is therefore
 * authoritative — do not read this class's quantity as the live position size. A backtest that sizes
 * from the strategy's own quantity will under-size a JPY cross by the factor.
 *
 * <h3>Known limitations on the live path</h3>
 * Both were measured by independent review; neither loses money, and both are documented rather than
 * papered over.
 * <ul>
 *   <li><b>The entry is consumed on the NEXT bar</b>, because nothing else acknowledges a fill (see
 *       onBar). If the runner ever feeds two of this strategy's bars before dispatching pending
 *       orders — a stall of more than one bar, or a restart at the cursor edge — the entry can be
 *       cleared before it was sent and the setup is missed. Missing a trade, never doubling one.</li>
 *   <li><b>A restart between the event and the week-end</b> replays the event bar during the warm-up:
 *       the entry is emitted and then consumed inside the replay, so the strategy finishes the replay
 *       ENTERED while the broker is flat. No order reaches the venue, and the close it emits at the
 *       week-end is refused for lack of a tracked position (logged). Deploy before the first event of
 *       the week, and after any mid-week restart check the monitor for ENTERED strategies with no
 *       matching broker trade.</li>
 *   <li><b>The temporal close stays queued after it fires.</b> The EXPIRED phase is a deliberate no-op
 *       (clearing there could drop the close if two bars ever arrive in one tick, and dropping an
 *       exit leaves a position open — worse than noise). The runner refuses the repeat with an error
 *       log once the account is flat, so an expired week logs that error every tick until its
 *       services are removed. That is noise, not a position.</li>
 * </ul>
 */
public abstract class NewsWeeklyStrategy implements Strategy {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    protected static final double DEFAULT_CAPITAL = LotSizing.DEFAULT_STARTING_CAPITAL; // $1,000

    protected final String name;
    protected final String symbol;
    protected final double quantity;
    protected final List<Order> pending = new ArrayList<>();

    private final Instant eventStart;
    private final Instant weekEnd;
    private final int stopLossPips;
    private final int takeProfitPips;
    private final int trailingStopPips;
    private final boolean bidirectional;

    private Phase phase = Phase.WAITING;
    private Order.Side entrySide;
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

        // Mandatory protective stop. Not a style choice: the live entry guard refuses any entry whose
        // order carries no stop, and without a stop distance there is no risk denominator to size
        // from. A stopless strategy used to be authorable here and would then be refused (or sized
        // from the 1,000-unit micro-lot) at runtime; failing at construction moves that from a
        // deploy-time surprise to a build-time error.
        if (stopLossPips <= 0) {
            throw new IllegalArgumentException("NewsWeeklyStrategy '" + name
                + "' requires stopLossPips > 0 (a stopless entry is refused by the live path and has "
                + "no risk denominator). Received: " + stopLossPips);
        }

        double raw = (capital * riskPct) / (stopLossPips * pipValue(symbol));
        this.quantity = roundUnits(raw);
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
        phase = Phase.WAITING;
        stopPrice = 0;
        extremePrice = 0;
        if (bidirectional) entrySide = null;
    }

    // ====== Bar logic ======

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        double pipVal = pipValue(symbol);

        switch (phase) {

            // — Enter or analyze — //
            case WAITING:
                // Inert outside the strategy's own week. Without this, a late start (or the runner's
                // warm-up replay beginning after the event) enters on a bar the market has already
                // left behind, and the strategy then believes it holds a position the broker never
                // received. The week-end is the hard ceiling, checked before the event floor.
                if (bar.timestamp().compareTo(weekEnd) >= 0) {
                    phase = Phase.EXPIRED;
                    break;
                }
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

                // Fixed SL: the stop rode the entry order (see doEntry), so the venue holds it and
                // the engine exits on it. What stays here is the in-process check, which turns a
                // crossed stop into an explicit close instead of waiting for the venue.
                if (trailingStopPips == 0) {
                    boolean hit = entrySide == Order.Side.SELL
                        ? bar.high() >= stopPrice
                        : bar.low() <= stopPrice;
                    // The entry order is DISPATCHED and must be consumed here. Nothing consumes it
                    // otherwise: getPendingOrders() hands out an immutable copy and no engine calls
                    // back to acknowledge a fill, so leaving it queued re-submits a fresh
                    // risk-sized entry on EVERY bar — a position per hour for as long as the stop
                    // and the week-end hold. Clearing is not an optimisation.
                    pending.clear();
                    if (hit) {
                        pending.add(closeOrder(entrySide));
                        phase = Phase.EXPIRED;
                    }
                }

                // Trailing stop. Note for whoever writes the first trailing news strategy: doEntry
                // attaches the initial stop to the ENTRY order (stop-on-fill at the venue) AND this
                // branch re-emits a close-only STOP every bar, so the venue would hold two stops that
                // drift apart as this one trails. No deployed news strategy uses trailingStopPips > 0;
                // review that combination before the first one does.
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
                        Order.Type.STOP, quantity, stopPrice).asCloseOnly());
                }
                break;

            case EXPIRED:
                // no-op
                break;
        }
    }

    // ====== Entry helper ======

    private void doEntry(Order.Side side, double price, double pipVal) {
        extremePrice = price;

        double tp = 0;
        if (stopLossPips > 0) {
            stopPrice = side == Order.Side.BUY
                ? price - stopLossPips * pipVal
                : price + stopLossPips * pipVal;
        }
        if (takeProfitPips > 0) {
            tp = side == Order.Side.BUY
                ? price + takeProfitPips * pipVal
                : price - takeProfitPips * pipVal;
        }

        // The stop rides the ENTRY order, never a separate close-only order:
        //  - live, LiveStrategyRunner refuses any entry whose order carries no stop
        //    (rejectEntryWithoutStop) and passes order.stopLoss() to the venue as a stop-on-fill
        //    (LiveStrategyRunner.executeTrade -> placeMarketOrder), which the runner also tracks
        //    locally in its own ActiveTrade. The take profit is deliberately NOT sent to the venue:
        //    the target lives in the runner's local monitor (it is lost if the runner goes down).
        //  - backtest, BacktestEngine stores the order's stopLoss/takeProfit on the position and
        //    exits on them.
        // Emitting a second close-only STOP for the same size used to double-protect the position
        // at the broker and to spam "Unsupported order type: LIMIT" on every bar for the target.
        // The order below is consumed by the ENTERED branch on the next bar: see onBar().
        Order entry = new Order(symbol, side, Order.Type.MARKET, quantity, 0);
        if (stopLossPips > 0) entry = entry.withStopLoss(stopPrice);
        if (tp > 0) entry = entry.withTakeProfit(tp);

        pending.clear();
        pending.add(entry);
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
            Order.Type.MARKET, quantity, 0).asCloseOnly();
    }

    // ====== Static helpers ======

    protected static double pipValue(String sym) {
        // Any JPY-QUOTED pair prices a pip at 0.01, not 0.0001. Only USD_JPY was special-cased
        // before, so GBP_JPY / AUD_JPY fell through to the EUR/USD pip: the stop distance and the
        // risk-based quantity were both off by a factor of 100.
        return sym != null && sym.endsWith("_JPY") ? 0.01 : 0.0001;
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
