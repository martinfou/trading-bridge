package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;

import java.time.*;
import java.util.*;

/**
 * GoldTurtleTrend — Turtle trend-following system on XAU/USD (H1).
 *
 * 📊 CONCEPT: Gold is the only non-FX instrument available in trading-bridge
 *    (XAU_USD H1 2006-2025, 174K bars). Unlike FX pairs (which produce
 *    PF ~0.9 after costs post look-ahead fix), gold has structural
 *    multi-year trends (2006-2012 bull, 2013-2015 bear, 2016-2025 bull).
 *    A classic Turtle system (Donchian 55 entry / Donchian 20 exit) is the
 *    canonical trend-following design to capture those trends with very few
 *    trades — the pattern class where costs are negligible relative to moves.
 *
 *    Rationale vs existing DonchianChannelBreakoutStrategy (creative/):
 *    that strategy is an INTRADAY breakout (max hold 8 bars = 8 hours,
 *    ATR trailing), pre-lookahead-fix, never validated, and structurally
 *    unsuitable for multi-week gold trends. This strategy is a different
 *    concept: long-horizon position trading (holds of weeks), channel-exit
 *    (not time-stop), fixed quantity (no ATR-H1 leverage artifact on long
 *    positions — see AugustRiskFade pitfall).
 *
 * 🔧 MECHANISM (look-ahead safe: channels computed on bars BEFORE current):
 *    1. Entry BUY  when close > high of previous 55 bars
 *       Entry SELL when close < low  of previous 55 bars
 *    2. Exit  BUY  position when close < low  of previous 20 bars (S1)
 *       Exit  SELL position when close > high of previous 20 bars
 *    3. Fixed quantity 10 units (1 unit = 1 oz; ~$43K notional at 2025 prices
 *       on $50K capital — constant quantity, no leverage explosion)
 *    4. Optional SeasonalityFilter guard: XAU has no registered seasonal
 *       pattern, so the filter is neutral by construction (documented).
 *
 * 🎯 Expected outcome: either the multi-year gold trends survive costs
 *    (PF > 1.2, DD < 20%, ≥ 30 trades) or the H1 noise kills this too —
 *    in both cases the result is informative for the "non-FX instrument"
 *    research direction.
 */
public class GoldTurtleTrendStrategy implements Strategy {

    private static final double QUANTITY = 10;       // fixed oz — no ATR leverage artifact
    private static final int DEFAULT_ENTRY_CHANNEL = 55;  // Turtle S1 entry channel
    private static final int DEFAULT_EXIT_CHANNEL = 20;   // Turtle S1 exit channel

    private final String name;
    private final String symbol;
    private final int entryChannel;
    private final int exitChannel;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;

    public GoldTurtleTrendStrategy() { this("GoldTurtleTrend", "XAU_USD"); }
    public GoldTurtleTrendStrategy(String name) { this(name, "XAU_USD"); }
    public GoldTurtleTrendStrategy(String name, String symbol) {
        this(name, symbol, DEFAULT_ENTRY_CHANNEL, DEFAULT_EXIT_CHANNEL);
    }
    public GoldTurtleTrendStrategy(String name, String symbol, int entryChannel, int exitChannel) {
        this.name = name;
        this.symbol = symbol;
        this.entryChannel = entryChannel;
        this.exitChannel = exitChannel;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        int n = history.size();
        if (n < exitChannel + 2) return;

        // Channels on bars BEFORE the current bar (look-ahead safe)
        double prevHigh55 = maxHigh(n - 1, entryChannel);
        double prevLow55  = minLow(n - 1, entryChannel);
        double prevHigh20 = maxHigh(n - 1, exitChannel);
        double prevLow20  = minLow(n - 1, exitChannel);

        // SeasonalityFilter guard (inline mirror — trading-strategies cannot
        // depend on trading-intelligence; same pattern as SeasonalEmaPullback).
        // XAU has no registered seasonal pattern → always neutral (null).
        Order.Side seasonalBias = getSeasonalBias(symbol, bar.timestamp());

        // --- MANAGE EXISTING POSITION (channel exit S1) ---
        if (inTrade) {
            if (tradeDirection == Order.Side.BUY && bar.close() < prevLow20) {
                closePosition(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL && bar.close() > prevHigh20) {
                closePosition(bar);
                return;
            }
            return;
        }

        // --- ENTRY (Donchian 55 breakout, close-based) ---
        if (bar.close() > prevHigh55) {
            if (seasonalBias == null || seasonalBias == Order.Side.BUY) {
                enter(bar, Order.Side.BUY);
            }
        } else if (bar.close() < prevLow55) {
            if (seasonalBias == null || seasonalBias == Order.Side.SELL) {
                enter(bar, Order.Side.SELL);
            }
        }
    }

    private void enter(Bar bar, Order.Side side) {
        pending.add(new Order(symbol, side, Order.Type.MARKET, QUANTITY, bar.close()));
        inTrade = true;
        tradeDirection = side;
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, QUANTITY, bar.close()).closeOnly());
        inTrade = false;
    }

    /** Max high over the `period` bars strictly BEFORE index `end` (exclusive). */
    private double maxHigh(int end, int period) {
        double max = 0;
        int from = Math.max(0, end - period);
        for (int i = from; i < end; i++) {
            if (history.get(i).high() > max) max = history.get(i).high();
        }
        return max;
    }

    /** Min low over the `period` bars strictly BEFORE index `end` (exclusive). */
    private double minLow(int end, int period) {
        double min = Double.MAX_VALUE;
        int from = Math.max(0, end - period);
        for (int i = from; i < end; i++) {
            if (history.get(i).low() < min) min = history.get(i).low();
        }
        return min;
    }

    @Override public void onTick(double bid, double ask, long volume) {}

    /**
     * Inline SeasonalityFilter — miroir de SeasonalityFilter.getBias()
     * (trading-intelligence), copié ici car trading-strategies ne peut pas
     * dépendre de trading-intelligence (cycle Maven). Même pattern que
     * SeasonalEmaPullbackContinuation / EngulfingReversalStrategy.
     */
    protected Order.Side getSeasonalBias(String sym, Instant now) {
        ZonedDateTime zdt = now.atZone(ZoneId.of("America/New_York"));
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();
        String s = sym.replace("_", "");
        if (s.equals("USDCAD") && inWindow(month, day, 10, 12, 11, 26)) return Order.Side.BUY;
        if (s.equals("USDJPY") && inWindow(month, day, 9, 27, 11, 11)) return Order.Side.BUY;
        if (s.equals("GBPUSD") && inWindow(month, day, 3, 11, 4, 25)) return Order.Side.BUY;
        if (s.equals("EURUSD") && inWindow(month, day, 3, 16, 4, 30)) return Order.Side.BUY;
        if (s.equals("AUDUSD") && inWindow(month, day, 6, 4, 7, 19)) return Order.Side.BUY;
        if (s.equals("USDCAD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.SELL;
        if (s.equals("GBPUSD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;
        if (s.equals("EURUSD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;
        return null; // XAU_USD → no registered pattern → neutral
    }

    private boolean inWindow(int month, int day, int sm, int sd, int em, int ed) {
        if (sm > em || (sm == em && sd > ed)) {
            return (month > sm || (month == sm && day >= sd))
                || (month < em || (month == em && day <= ed));
        }
        return (month > sm || (month == sm && day >= sd))
            && (month < em || (month == em && day <= ed));
    }

    @Override public List<Order> getPendingOrders() {
        var copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }
    @Override public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        tradeDirection = Order.Side.BUY;
    }
}
