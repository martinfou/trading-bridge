package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * SmaTrendReversal — Simple mean reversion within SMA trend (H1)
 *
 * 📊 Concept: Mean reversion trades within the prevailing trend direction
 *    have a statistical edge. When RSI reaches oversold levels in an uptrend,
 *    or overbought levels in a downtrend, the price tends to snap back.
 *    Uses SMA(50) as the trend filter and RSI(14) for entry timing.
 *    Inline seasonality bias provides an additional macro filter.
 *
 * 🔧 Mechanism:
 *    - SMA(50) trend filter: close > SMA(50) = uptrend, close < SMA(50) = downtrend
 *    - RSI(14) < 30 + uptrend + close > SMA(50) → BUY mean reversion
 *    - RSI(14) > 70 + downtrend + close < SMA(50) → SELL mean reversion
 *    - Inline seasonal bias must not oppose trade direction (RÈGLE CRITIQUE)
 *    - ATR(14)-based SL (1.0× ATR) and TP (2.0× ATR = 2:1 RR)
 *    - Position sizing via calcRiskPosition (0.8% risk per trade)
 *    - Max 1 trade per day
 *    - All indicators computed BEFORE history.add(bar) — zero look-ahead bias
 */
public class SmaTrendReversal implements Strategy {

    private static final int SMA_PERIOD = 50;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final double RSI_OVERSOLD = 30.0;
    private static final double RSI_OVERBOUGHT = 70.0;
    private static final double SL_MULT = 1.0;
    private static final double TP_MULT = 2.0;
    private static final double REFERENCE_CAPITAL = 50_000;
    private static final double RISK_PCT = 0.008;
    private static final int MIN_HISTORY = Math.max(SMA_PERIOD, ATR_PERIOD) + 5;
    private static final int COOLDOWN_BARS = 2;

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

    public SmaTrendReversal() { this("SmaTrendReversal", "EUR_USD"); }
    public SmaTrendReversal(String name) { this(name, "EUR_USD"); }
    public SmaTrendReversal(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        if (history.size() < MIN_HISTORY - 1) { history.add(bar); return; }

        // 1. Compute indicators on past history only (no look-ahead bias)
        double sma50 = Indicators.smaLatest(history, SMA_PERIOD);
        double rsi = Indicators.rsi(history, RSI_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);

        // RÈGLE CRITIQUE — Inline SeasonalityFilter
        Order.Side bias = getSeasonalBias(symbol, bar.timestamp());

        // 2. Add current bar
        history.add(bar);

        if (Double.isNaN(sma50) || Double.isNaN(rsi) || Double.isNaN(atr) || atr <= 0 || sma50 <= 0) return;

        int barDay = bar.timestamp().atZone(ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        if (inTrade) {
            managePosition(bar);
        } else {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 1) return;
            evaluateEntry(bar, sma50, rsi, atr, bias);
        }
    }

    private void evaluateEntry(Bar bar, double sma50, double rsi, double atr, Order.Side bias) {
        double close = bar.close();

        // BUY: RSI oversold in uptrend (close above SMA50), bias not opposed
        if (close > sma50 && rsi < RSI_OVERSOLD && bias != Order.Side.SELL) {
            entryPrice = close;
            stopLoss = entryPrice - atr * SL_MULT;
            takeProfit = entryPrice + atr * TP_MULT;
            double qty = Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol);
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, qty, entryPrice));
            inTrade = true; tradeDirection = Order.Side.BUY; tradesToday++;
        }
        // SELL: RSI overbought in downtrend (close below SMA50), bias not opposed
        else if (close < sma50 && rsi > RSI_OVERBOUGHT && bias != Order.Side.BUY) {
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
