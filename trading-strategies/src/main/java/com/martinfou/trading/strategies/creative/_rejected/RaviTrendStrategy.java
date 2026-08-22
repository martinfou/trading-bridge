package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * RaviTrendStrategy — Range Action Verification Index trend following (H1)
 *
 * ❌ REJECTED 2026-07-21: RAVI 3% → only 7 trades (PF 1.40, too few).
 *    RAVI 0.8% → 734/766 trades (PF 1.00/0.97). No exploitable edge.
 *    0/3 pairs passed quality gate.
 *
 * 📊 Concept: RAVI = ABS(SMA(7) - SMA(65)) / SMA(65) * 100.
 *    When RAVI > 3%, the market is trending (not ranging).
 *    In trending mode, follow the direction of SMA(7) vs SMA(65).
 *    When RAVI < 3%, skip — the market is range-bound.
 *    Inline seasonal bias prevents trading against calendar patterns.
 *
 * 🔧 Mechanism:
 *    - SMA(7) fast, SMA(65) slow (standard RAVI params)
 *    - RAVI > 3% = trending → check SMA direction
 *    - SMA(7) > SMA(65) = uptrend → BUY
 *    - SMA(7) < SMA(65) = downtrend → SELL
 *    - Entry only when both RAVI threshold AND directional condition met AND
 *      no trade today (max 1/day)
 *    - ATR(14)-based SL (1.5× ATR) and TP (3.0× ATR = 2:1 RR)
 *    - Position sizing via calcRiskPosition (0.8% risk, $50K capital)
 *    - Cooldown after exit
 *    - All indicators computed BEFORE history.add(bar) — zero look-ahead bias
 *    - Inline SeasonalityFilter — must not oppose trade direction
 */
public class RaviTrendStrategy implements Strategy {

    private static final int SMA_FAST = 7;
    private static final int SMA_SLOW = 65;
    private static final int ATR_PERIOD = 14;
    /** RAVI threshold: above this = trending */
    private static final double RAVI_THRESHOLD = 0.8;
    private static final double SL_MULT = 1.5;
    private static final double TP_MULT = 3.0;
    private static final double REFERENCE_CAPITAL = 50_000;
    private static final double RISK_PCT = 0.008;
    private static final int MIN_HISTORY = Math.max(SMA_SLOW, ATR_PERIOD) + 5;
    private static final int COOLDOWN_BARS = 3;

    private final String name;
    private final String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int lastTradeDay = -1;
    private int tradesToday = 0;
    private int cooldownBars = 0;

    public RaviTrendStrategy() { this("RaviTrend", "EUR_USD"); }
    public RaviTrendStrategy(String name) { this(name, "EUR_USD"); }
    public RaviTrendStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        if (history.size() < MIN_HISTORY - 1) { history.add(bar); return; }

        // 1. Compute indicators on PAST history (ZERO look-ahead bias)
        double smaFast = Indicators.smaLatest(history, SMA_FAST);
        double smaSlow = Indicators.smaLatest(history, SMA_SLOW);
        double atr = Indicators.atr(history, ATR_PERIOD);

        // 2. Seasonality bias (RÈGLE CRITIQUE)
        Order.Side bias = getSeasonalBias(symbol, bar.timestamp());

        // 3. Add current bar (AFTER indicators computed)
        history.add(bar);

        if (Double.isNaN(smaFast) || Double.isNaN(smaSlow) || smaSlow <= 0
            || Double.isNaN(atr) || atr <= 0) return;

        int barDay = bar.timestamp().atZone(ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        if (inTrade) {
            managePosition(bar);
        } else {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 1) return;
            evaluateEntry(bar, smaFast, smaSlow, atr, bias);
        }
    }

    private void evaluateEntry(Bar bar, double smaFast, double smaSlow,
                                double atr, Order.Side bias) {
        // RAVI = ABS(SMA(7) - SMA(65)) / SMA(65) * 100
        double ravi = Math.abs(smaFast - smaSlow) / smaSlow * 100.0;
        if (ravi < RAVI_THRESHOLD) return; // Not trending — skip

        double close = bar.close();
        boolean uptrend = smaFast > smaSlow;

        if (uptrend && bias != Order.Side.SELL) {
            entryPrice = close;
            stopLoss = entryPrice - atr * SL_MULT;
            takeProfit = entryPrice + atr * TP_MULT;
            double qty = Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol);
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, qty, entryPrice));
            inTrade = true; tradeDirection = Order.Side.BUY; tradesToday++;
        } else if (!uptrend && bias != Order.Side.BUY) {
            entryPrice = close;
            stopLoss = entryPrice + atr * SL_MULT;
            takeProfit = entryPrice - atr * TP_MULT;
            double qty = Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol);
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, qty, entryPrice));
            inTrade = true; tradeDirection = Order.Side.SELL; tradesToday++;
        }
    }

    private void managePosition(Bar bar) {
        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);
        boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= takeProfit)
            || (tradeDirection == Order.Side.SELL && bar.low() <= takeProfit);
        if (stopHit) { exitPosition(stopLoss); return; }
        if (tpHit) { exitPosition(takeProfit); return; }
    }

    private void exitPosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, 1000, price).asCloseOnly());
        inTrade = false; cooldownBars = COOLDOWN_BARS;
    }

    // ── Seasonality Filter (inline) ──────────────────────────────

    /** Inline SeasonalityFilter — mirrors SeasonalityFilter.getBias(). */
    private static Order.Side getSeasonalBias(String sym, java.time.Instant now) {
        ZonedDateTime zdt = now.atZone(ZoneId.of("America/New_York"));
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();
        if (sym.equals("USDCAD") && inWindow(month, day, 10, 12, 11, 26)) return Order.Side.BUY;
        if (sym.equals("USD_JPY") && inWindow(month, day, 9, 27, 11, 11)) return Order.Side.BUY;
        if (sym.equals("GBP_USD") && inWindow(month, day, 3, 11, 4, 25)) return Order.Side.BUY;
        if (sym.equals("EUR_USD") && inWindow(month, day, 3, 16, 4, 30)) return Order.Side.BUY;
        if (sym.equals("AUD_USD") && inWindow(month, day, 6, 4, 7, 19)) return Order.Side.BUY;
        if (sym.equals("USDCAD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.SELL;
        if (sym.equals("GBP_USD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;
        if (sym.equals("EUR_USD") && inWindow(month, day, 4, 1, 4, 30)) return Order.Side.BUY;
        return null;
    }

    private static boolean inWindow(int month, int day, int sm, int sd, int em, int ed) {
        if (sm > em || (sm == em && sd > ed)) {
            return (month > sm || (month == sm && day >= sd))
                || (month < em || (month == em && day <= ed));
        }
        return (month > sm || (month == sm && day >= sd))
            && (month < em || (month == em && day <= ed));
    }

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        var copy = List.copyOf(pending); pending.clear(); return copy;
    }
    @Override public void reset() {
        history.clear(); pending.clear(); inTrade = false;
        lastTradeDay = -1; tradesToday = 0; cooldownBars = 0;
    }
}
