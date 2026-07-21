package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * EngulfingReversalStrategy — Candle pattern reversal with EMA trend filter (H1)
 *
 * 📊 Concept: Bullish and bearish engulfing patterns are high-probability
 *    reversal signals, especially when they occur in the direction of the
 *    prevailing trend. The EMA(50)/EMA(200) crossover provides the macro
 *    trend direction, and engulfing patterns serve as entry triggers.
 *    Inline seasonal bias prevents trading against historical patterns.
 *
 * 🔧 Mechanism:
 *    - EMA(50) > EMA(200) = uptrend → only take bullish engulfing signals
 *    - EMA(50) < EMA(200) = downtrend → only take bearish engulfing signals
 *    - Indicators.bullishEngulfing() / bearishEngulfing() for entry
 *    - Inline seasonal bias must not oppose the trade direction (RÈGLE CRITIQUE)
 *    - ATR(14)-based SL (1.5× ATR) and TP (3.0× ATR = 2:1 RR)
 *    - Position sizing via calcRiskPosition (0.8% risk per trade)
 *    - Max 1 trade per day, cooldown after exit
 *    - All indicators computed BEFORE history.add(bar) — zero look-ahead bias
 */
public class EngulfingReversalStrategy implements Strategy {

    private static final int EMA_MID = 50;
    private static final int EMA_SLOW = 200;
    private static final int ATR_PERIOD = 14;
    private static final double SL_MULT = 1.5;
    private static final double TP_MULT = 3.0;
    private static final double REFERENCE_CAPITAL = 50_000;
    private static final double RISK_PCT = 0.008;
    private static final int MIN_HISTORY = Math.max(EMA_SLOW, ATR_PERIOD) + 5;
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

    public EngulfingReversalStrategy() { this("EngulfingReversal", "EUR_USD"); }
    public EngulfingReversalStrategy(String name) { this(name, "EUR_USD"); }
    public EngulfingReversalStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        // Need at least 2 bars to detect engulfing (prev + cur)
        if (history.size() < MIN_HISTORY - 1) { history.add(bar); return; }

        // 1. Compute indicators on PAST history (no look-ahead bias)
        double ema50 = Indicators.emaLatest(history, EMA_MID);
        double ema200 = Indicators.emaLatest(history, EMA_SLOW);
        double atr = Indicators.atr(history, ATR_PERIOD);
        Bar prev = history.get(history.size() - 1);

        // RÈGLE CRITIQUE — Inline SeasonalityFilter
        Order.Side bias = getSeasonalBias(symbol, bar.timestamp());

        // 2. Add current bar
        history.add(bar);

        if (Double.isNaN(ema50) || Double.isNaN(ema200) || Double.isNaN(atr) || atr <= 0) return;

        int barDay = bar.timestamp().atZone(ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        if (inTrade) {
            managePosition(bar);
        } else {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 1) return;
            evaluateEntry(bar, prev, ema50, ema200, atr, bias);
        }
    }

    private void evaluateEntry(Bar bar, Bar prev, double ema50, double ema200,
                                double atr, Order.Side bias) {
        double close = bar.close();
        double pip = Indicators.pipSize(symbol);

        // Uptrend: only take bullish engulfing
        if (ema50 > ema200 && Indicators.isBullishEngulfing(prev, bar)
            && bar.close() > ema50 && bias != Order.Side.SELL) {
            entryPrice = close;
            stopLoss = Math.min(prev.low(), bar.low()) - atr * 0.3;
            takeProfit = entryPrice + atr * TP_MULT;
            double qty = Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol);
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, qty, entryPrice));
            inTrade = true; tradeDirection = Order.Side.BUY; tradesToday++;
        }
        // Downtrend: only take bearish engulfing
        else if (ema50 < ema200 && Indicators.isBearishEngulfing(prev, bar)
            && bar.close() < ema50 && bias != Order.Side.BUY) {
            entryPrice = close;
            stopLoss = Math.max(prev.high(), bar.high()) + atr * 0.3;
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

    /** Inline SeasonalityFilter — mirrors SeasonalityFilter.getBias(). */
    private Order.Side getSeasonalBias(String sym, java.time.Instant now) {
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
