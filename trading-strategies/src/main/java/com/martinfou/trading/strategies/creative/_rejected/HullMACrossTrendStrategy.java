package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * HullMACrossTrendStrategy — Hull Moving Average crossover trend follower (H1)
 *
 * ❌ REJECTED 2026-07-21: PF 0.91 on EUR_USD (4743 trades). HMA crossovers
 *    too frequent, no edge after costs. 0/3 pairs passed quality gate.
 *
 * 📊 Concept: The Hull Moving Average (Alan Hull, 2005) reduces lag vs
 *    standard EMAs by using weighted moving averages with sqrt(period).
 *    Crossovers of fast HMA(20) and slow HMA(55) generate trend signals
 *    that react faster to changes yet produce fewer whipsaws.
 *    Inline seasonal bias prevents trading against calendar patterns.
 *
 * 🔧 Mechanism:
 *    - HMA(20) fast line, HMA(55) slow line
 *    - HMA fast > HMA slow = uptrend → BUY signals
 *    - HMA fast < HMA slow = downtrend → SELL signals
 *    - Entry only when cross JUST happened (cross confirmed within last 3 bars)
 *    - ATR(14)-based SL (1.5× ATR) and TP (3.0× ATR = 2:1 RR)
 *    - Position sizing via calcRiskPosition (0.8% risk per trade, $50K capital)
 *    - Max 1 trade/day, cooldown after exit
 *    - All indicators computed BEFORE history.add(bar) — zero look-ahead bias
 *    - Inline SeasonalityFilter — must not oppose trade direction
 */
public class HullMACrossTrendStrategy implements Strategy {

    private static final int HMA_FAST = 20;
    private static final int HMA_SLOW = 55;
    private static final int ATR_PERIOD = 14;
    private static final double SL_MULT = 1.5;
    private static final double TP_MULT = 3.0;
    private static final double REFERENCE_CAPITAL = 50_000;
    private static final double RISK_PCT = 0.008;
    private static final int MIN_HISTORY = Math.max(HMA_SLOW + ATR_PERIOD, 120);
    private static final int COOLDOWN_BARS = 3;
    /** Max bars since cross to still enter (avoid stale signals) */
    private static final int CROSS_CONFIRM_BARS = 3;

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
    /** How many bars since the last cross — 0 = this bar IS the cross */
    private int barsSinceCross = Integer.MAX_VALUE;
    private int lastCrossBar = -1;

    public HullMACrossTrendStrategy() { this("HullMACross", "EUR_USD"); }
    public HullMACrossTrendStrategy(String name) { this(name, "EUR_USD"); }
    public HullMACrossTrendStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        if (history.size() < MIN_HISTORY - 1) { history.add(bar); return; }

        // 1. Compute indicators on PAST history (ZERO look-ahead bias)
        double hmaFast = hma(history, HMA_FAST);
        double hmaSlow = hma(history, HMA_SLOW);
        double hmaFastPrev = hmaPrevious(history, HMA_FAST);
        double hmaSlowPrev = hmaPrevious(history, HMA_SLOW);
        double atr = Indicators.atr(history, ATR_PERIOD);

        // 2. Check for crossover
        boolean crossUp = hmaFastPrev <= hmaSlowPrev && hmaFast > hmaSlow;
        boolean crossDn = hmaFastPrev >= hmaSlowPrev && hmaFast < hmaSlow;

        // 3. Track bars since last cross
        if (crossUp || crossDn) {
            barsSinceCross = 0;
            lastCrossBar = history.size();
        } else if (lastCrossBar >= 0) {
            barsSinceCross = history.size() - lastCrossBar;
        }

        // 4. Seasonality bias (RÈGLE CRITIQUE)
        Order.Side bias = getSeasonalBias(symbol, bar.timestamp());

        // 5. Add current bar (AFTER indicators computed)
        history.add(bar);

        if (Double.isNaN(hmaFast) || Double.isNaN(hmaSlow) || Double.isNaN(atr) || atr <= 0) return;

        int barDay = bar.timestamp().atZone(ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        if (inTrade) {
            managePosition(bar);
        } else {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 1) return;
            if (barsSinceCross > CROSS_CONFIRM_BARS) return;
            evaluateEntry(bar, hmaFast, hmaSlow, atr, bias);
        }
    }

    private void evaluateEntry(Bar bar, double hmaFast, double hmaSlow,
                                double atr, Order.Side bias) {
        boolean uptrend = hmaFast > hmaSlow;

        if (uptrend && bias != Order.Side.SELL) {
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * SL_MULT;
            takeProfit = entryPrice + atr * TP_MULT;
            double qty = Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol);
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, qty, entryPrice));
            inTrade = true; tradeDirection = Order.Side.BUY; tradesToday++;
        } else if (!uptrend && bias != Order.Side.BUY) {
            entryPrice = bar.close();
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
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, 1000, price).closeOnly());
        inTrade = false; cooldownBars = COOLDOWN_BARS;
    }

    // ── Hull Moving Average ──────────────────────────────────────

    /**
     * Hull Moving Average (HMA) — Latest value.
     * HMA = WMA(sqrt(n), 2*WMA(n/2, price) - WMA(n, price))
     */
    private static double hma(List<Bar> bars, int period) {
        int end = bars.size() - 1;
        return hmaAt(bars, period, end);
    }

    /**
     * HMA — Value at previous bar index.
     */
    private static double hmaPrevious(List<Bar> bars, int period) {
        int end = bars.size() - 2;
        if (end < period) return Double.NaN;
        return hmaAt(bars, period, end);
    }

    private static double hmaAt(List<Bar> bars, int period, int end) {
        int half = period / 2;
        int sqrtPeriod = (int) Math.round(Math.sqrt(period));
        int need = period + half + sqrtPeriod + 5;
        if (end < need) return Double.NaN;

        double wmaFull = wmaAt(bars, period, end);
        double wmaHalf = wmaAt(bars, half, end);
        double raw = 2 * wmaHalf - wmaFull;
        return wmaOnValues(bars, sqrtPeriod, end, raw);
    }

    /** Weighted Moving Average of close prices. */
    private static double wmaAt(List<Bar> bars, int period, int end) {
        double sum = 0;
        int weightSum = 0;
        int w = 1;
        for (int i = end - period + 1; i <= end; i++) {
            sum += bars.get(i).close() * w;
            weightSum += w;
            w++;
        }
        return sum / weightSum;
    }

    /**
     * WMA of a modified series (2*WMA(n/2) - WMA(n)).
     * We compute this as a WMA of close prices but with the
     * transformed values stored temporarily.
     */
    private static double wmaOnValues(List<Bar> bars, int period, int end, double currentRaw) {
        if (period <= 1) return currentRaw;

        // Build the raw series: 2*WMA(half) - WMA(full) for each point
        int half = period; // The raw series period for this step is the sqrt(n)
        int fullPeriod = half * 2; // Approx: if sqrt(n)=k, then full period ~ k*2

        // Since we need the raw series going back, compute it iteratively
        double sum = 0;
        int weightSum = 0;
        int w = 1;

        for (int i = end - period + 1; i < end; i++) {
            double hf = wmaAt(bars, half, i);
            double fl = wmaAt(bars, fullPeriod, i);
            double r = 2 * hf - fl;
            sum += r * w;
            weightSum += w;
            w++;
        }
        sum += currentRaw * w;
        weightSum += w;

        return sum / weightSum;
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
        barsSinceCross = Integer.MAX_VALUE; lastCrossBar = -1;
    }
}
