package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * AugustRiskFadeStrategy — August curse fade on risk pairs (H1)
 *
 * 📊 CONCEPT: Trade the "August curse" — the well-documented tendency of
 *    risk-sensitive FX pairs (AUD_USD, NZD_USD, GBP_USD) to fall in August.
 *    Statistical check (AugustRiskCheck, 2026-08-12) on 2006-2026 H1:
 *      - AUD_USD: Aug avg -1.39%, hit 21% (79% of years DOWN), IS hit 20% → OOS 22%
 *      - NZD_USD: Aug avg -1.60%, hit 28%, IS 20% → OOS 38%
 *      - GBP_USD: Aug avg -1.03%, hit 32%, IS 30% → OOS 33%
 *      - Control (neighbor months): July is BULLISH (hit 56-63%) → August is
 *        genuinely distinct, NOT a long trend artifact (unlike XAU Aug).
 *    This is the pure seasonal drift edge: SELL at August open, BUY at close.
 *
 * 🔧 MECHANISM:
 *    1. Window: Aug 1 00:00 UTC → Aug 31 23:00 UTC (trading days counted in UTC)
 *    2. Entry: SELL at market on first bar of August (no indicator filter)
 *    3. Exit: BUY closeOnly on last bar of August (forced at window close)
 *    4. Fixed quantity 10K units — no ATR-based sizing (leverage artifact on
 *       monthly holds: ATR H1 sizing × 1-month position = ~7x leverage, DD 146%)
 *    5. Max 1 trade per year — structural for a pure seasonal window strategy
 *
 * 🎯 Rationale: after the look-ahead fix, all simple H1 strategies produce
 *    PF ~0.9 after costs. Pure calendar drift (1-2 trades/year) is the only
 *    pattern class where costs are negligible relative to the move.
 */
public class AugustRiskFadeStrategy implements Strategy {

    // --- Parameters ---
    private static final double QUANTITY = 10_000;   // fixed units — no leverage artifact
    private static final int MIN_HISTORY = 5;

    private final String name;
    private final String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private boolean inTrade = false;
    private int lastTradeYear = -1;

    // Window state
    private boolean inAugustWindow = false;

    public AugustRiskFadeStrategy() { this("AugustRiskFade", "AUD_USD"); }
    public AugustRiskFadeStrategy(String name) { this(name, "AUD_USD"); }
    public AugustRiskFadeStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        // Need minimum history
        if (history.size() < MIN_HISTORY - 1) {
            history.add(bar);
            return;
        }
        history.add(bar);

        ZonedDateTime zdt = bar.timestamp().atZone(ZoneId.of("UTC"));
        int year = zdt.getYear();
        int month = zdt.getMonthValue();

        boolean windowOpened = false;
        boolean windowClosed = false;

        // Detect August window transitions (UTC-based trading-day counting)
        if (!inAugustWindow && month == 8) {
            inAugustWindow = true;
            windowOpened = true;
        }
        if (inAugustWindow && month != 8) {
            inAugustWindow = false;
            windowClosed = true;
        }

        // --- MANAGE EXISTING POSITION ---
        if (inTrade) {
            // Window closed (Sept 1) → force exit at market
            if (windowClosed) {
                forceExit(bar.close());
                return;
            }
        }

        // --- ENTER AT AUGUST OPEN ---
        if (windowOpened && !inTrade && year != lastTradeYear) {
            lastTradeYear = year;
            enterTrade(bar);
        }
    }

    private void enterTrade(Bar bar) {
        pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, QUANTITY, bar.close()));
        inTrade = true;
    }

    private void forceExit(double price) {
        pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, QUANTITY, price).closeOnly());
        inTrade = false;
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
        inAugustWindow = false;
        lastTradeYear = -1;
    }
}
