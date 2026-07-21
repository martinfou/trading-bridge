package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * StreakRsiConfluenceStrategy — Streak + RSI(3) confluence mean reversion (H1)
 *
 * ❌ REJECTED 2026-07-21: PF 0.88-0.93 on 3 pairs (3400+ trades each).
 *    RSI(3) + streak filter doesn't create a stronger edge than either
 *    signal alone. Consistent small losses on all pairs. 0/3 passed QG.
 *
 * 📊 Concept: When RSI(3) enters oversold (< 20) AND there have been 3+
 *    consecutive bearish bars, the selling pressure is both extreme (RSI)
 *    AND persistent (streak) — creating a high-probability reversal setup.
 *    Similarly for overbought (> 80) + 3+ consecutive bullish bars.
 *    Neither signal alone is reliable, but the confluence of both
 *    filters out false extremes and captures genuine exhaustion.
 *
 * 🔧 Mechanism:
 *    - RSI(3) for momentum exhaustion (faster than RSI-14, slower than RSI-2)
 *    - Consecutive bearish bar count > 2 for long entries
 *    - Consecutive bullish bar count > 2 for short entries
 *    - EMA(200) macro trend filter: prefer BUY in uptrend, SELL in downtrend
 *    - Inline seasonal bias must not oppose trade direction
 *    - ATR(14)-based SL (1.2× ATR) and TP (2.5× ATR ≈ 2:1 RR)
 *    - Position sizing via calcRiskPosition (0.8% risk, $50K capital)
 *    - Max 1 trade/day, cooldown after exit
 *    - All indicators computed BEFORE history.add(bar) — zero look-ahead bias
 */
public class StreakRsiConfluenceStrategy implements Strategy {

    private static final int RSI_PERIOD = 3;
    private static final int EMA_TREND = 200;
    private static final int ATR_PERIOD = 14;

    /** RSI below this = oversold → potential long */
    private static final double RSI_OVERSOLD = 20.0;
    /** RSI above this = overbought → potential short */
    private static final double RSI_OVERBOUGHT = 80.0;
    /** Min consecutive same-direction bars to confirm exhaustion */
    private static final int MIN_STREAK = 3;

    private static final double SL_MULT = 1.2;
    private static final double TP_MULT = 2.5;
    private static final double REFERENCE_CAPITAL = 50_000;
    private static final double RISK_PCT = 0.008;
    private static final int MIN_HISTORY = Math.max(EMA_TREND, ATR_PERIOD) + 5 + RSI_PERIOD;
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
    /** Count of consecutive bullish (close > open) bars */
    private int bullStreak = 0;
    /** Count of consecutive bearish (close < open) bars */
    private int bearStreak = 0;

    public StreakRsiConfluenceStrategy() { this("StreakRsiConfluence", "EUR_USD"); }
    public StreakRsiConfluenceStrategy(String name) { this(name, "EUR_USD"); }
    public StreakRsiConfluenceStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        // Need minimum history plus at least 1 bar to start streaks
        if (history.size() < MIN_HISTORY - 1) {
            // Track streaks from the beginning
            if (!history.isEmpty()) {
                Bar prev = history.get(history.size() - 1);
                updateStreaks(prev);
            }
            history.add(bar);
            return;
        }

        // Get previous bar for streak calculation
        Bar prev = history.get(history.size() - 1);
        updateStreaks(prev);

        // 1. Compute indicators on PAST history (ZERO look-ahead bias)
        double rsi = Indicators.rsi(history, RSI_PERIOD);
        double ema200 = Indicators.emaLatest(history, EMA_TREND);
        double atr = Indicators.atr(history, ATR_PERIOD);

        // 2. Seasonality bias (RÈGLE CRITIQUE)
        Order.Side bias = getSeasonalBias(symbol, bar.timestamp());

        // 3. Add current bar (AFTER indicators computed)
        history.add(bar);

        if (Double.isNaN(rsi) || Double.isNaN(ema200) || Double.isNaN(atr) || atr <= 0) return;

        int barDay = bar.timestamp().atZone(ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        if (inTrade) {
            managePosition(bar);
        } else {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 1) return;
            evaluateEntry(bar, rsi, ema200, atr, bias);
        }
    }

    private void updateStreaks(Bar bar) {
        if (bar.close() > bar.open()) {
            bullStreak++;
            bearStreak = 0;
        } else if (bar.close() < bar.open()) {
            bearStreak++;
            bullStreak = 0;
        } else {
            // Doji — streak doesn't change
        }
    }

    private void evaluateEntry(Bar bar, double rsi, double ema200,
                                double atr, Order.Side bias) {
        double close = bar.close();
        boolean macroUptrend = close > ema200;

        // Long: RSI oversold + bearish exhaustion streak
        if (rsi < RSI_OVERSOLD && bearStreak >= MIN_STREAK
            && macroUptrend && bias != Order.Side.SELL) {
            entryPrice = close;
            stopLoss = entryPrice - atr * SL_MULT;
            takeProfit = entryPrice + atr * TP_MULT;
            double qty = Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol);
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, qty, entryPrice));
            inTrade = true; tradeDirection = Order.Side.BUY; tradesToday++;
        }
        // Short: RSI overbought + bullish exhaustion streak
        else if (rsi > RSI_OVERBOUGHT && bullStreak >= MIN_STREAK
            && !macroUptrend && bias != Order.Side.BUY) {
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
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, 1000, price).closeOnly());
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
        bullStreak = 0; bearStreak = 0;
    }
}
