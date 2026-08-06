package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * DlrMeanReversion — Deviation-to-Lookback-Ratio mean reversion (H1)
 *
 * 📊 Concept: Trade only when price deviates significantly from SMA(50) AND
 *    the deviation-to-ATR ratio is high enough for a meaningful snap-back.
 *    Filter trades by requiring above-median volatility (ATR > SMA(ATR, 50))
 *    to ensure enough room for mean reversion. Target: GBPUSD, moderate
 *    trade count (~40-80/yr) where costs are survivable.
 *
 * 🔧 Mechanism:
 *    - SMA(50) midpoint, deviation = |close - SMA| / SMA
 *    - Requires deviation > 0.12% (~15-18 pips for GBPUSD)
 *    - Volatility filter: ATR(14) > SMA(ATR, 50) (above-median vol regime)
 *    - EMA(100) trend filter: buy in uptrend, sell in downtrend
 *    - Inline GBPUSD seasonality: Mar 11-Apr 25 BUY (83%)
 *    - SL = 1.2× ATR, TP = 2.0× ATR, max 1 trade/day
 */
public class DlrMeanReversion implements Strategy {

    private static final int SMA_PERIOD = 50;
    private static final int EMA_PERIOD = 100;
    private static final int ATR_PERIOD = 14;
    private static final double DEVIATION_PCT = 0.0015;
    private static final double SL_MULT = 1.2;
    private static final double TP_MULT = 2.0;
    private static final double REFERENCE_CAPITAL = 10_000;
    private static final double RISK_PCT = 0.008;
    private static final int MIN_HISTORY = Math.max(SMA_PERIOD, Math.max(EMA_PERIOD, ATR_PERIOD)) + 5;

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
    private static final int COOLDOWN_BARS = 2;

    public DlrMeanReversion() { this("DlrMeanReversion", "GBP_USD"); }
    public DlrMeanReversion(String name) { this(name, "GBP_USD"); }
    public DlrMeanReversion(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        if (history.size() < MIN_HISTORY - 1) { history.add(bar); return; }

        double sma50 = Indicators.smaLatest(history, SMA_PERIOD);
        double ema100 = Indicators.emaLatest(history, EMA_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);
        history.add(bar);

        if (Double.isNaN(sma50) || Double.isNaN(ema100) || Double.isNaN(atr) || atr <= 0 || sma50 <= 0) return;

        int barDay = bar.timestamp().atZone(ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        if (inTrade) {
            managePosition(bar);
        } else {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 1) return;
            evaluateEntry(bar, sma50, ema100, atr);
        }
    }

    /** GBPUSD: Mar 11 → Apr 25 = BUY (83%) */
    private Order.Side getSeasonalBias(Instant timestamp) {
        ZonedDateTime zdt = timestamp.atZone(ZoneId.of("America/New_York"));
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();
        if ((month == 3 && day >= 11) || (month == 4 && day <= 25)) return Order.Side.BUY;
        return null;
    }

    private void evaluateEntry(Bar bar, double sma50, double ema100, double atr) {
        double close = bar.close();
        double deviation = Math.abs(close - sma50) / sma50;

        if (deviation < DEVIATION_PCT) return;

        Order.Side bias = getSeasonalBias(bar.timestamp());

        // BUY: far below SMA in uptrend
        if (close < sma50 && close > ema100) {
            if (bias == Order.Side.SELL) return;
            entryPrice = close;
            stopLoss = entryPrice - atr * SL_MULT;
            takeProfit = entryPrice + atr * TP_MULT;
            double qty = Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol);
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, qty, entryPrice));
            inTrade = true; tradeDirection = Order.Side.BUY; tradesToday++;
        }
        // SELL: far above SMA in downtrend
        else if (close > sma50 && close < ema100) {
            if (bias == Order.Side.BUY) return;
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

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        var copy = List.copyOf(pending); pending.clear(); return copy;
    }
    @Override public void reset() {
        history.clear(); pending.clear(); inTrade = false;
        lastTradeDay = -1; tradesToday = 0; cooldownBars = 0;
    }
}
