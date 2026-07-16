package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * RSI3MeanReversion — RSI(3) extreme mean reversion with trend filter (H1)
 *
 * 📊 Concept: RSI(3) at extreme levels (>75 or <25) signals short-term
 *    overextension. On H1, such extremes represent 3 consecutive directional
 *    closes, which are followed by a pullback ~55-65% of the time. The EMA(100)
 *    filter ensures we only trade IN the direction of the larger trend — buying
 *    oversold conditions in uptrends and selling overbought in downtrends.
 *    Tight SL/TP captures the mean reversion snap-back.
 *
 * 🔧 Mechanism:
 *    - RSI(3) < 25 (oversold) + close > EMA(100) (uptrend) → LONG pullback
 *    - RSI(3) > 75 (overbought) + close < EMA(100) (downtrend) → SHORT pullback
 *    - Manual SL = 1.2× ATR(14), TP = 1.6× ATR(14)
 *    - closeOnly() exits for reliable position closure
 *    - Max 1 trade per day
 */
public class RSI3MeanReversion implements Strategy {

    private static final int RSI_PERIOD = 3;
    private static final int ATR_PERIOD = 14;
    private static final int EMA_PERIOD = 100;
    private static final double RSI_OVERSOLD = 25.0;
    private static final double RSI_OVERBOUGHT = 75.0;
    private static final double SL_MULT = 1.2;
    private static final double TP_MULT = 2.0;
    private static final double REFERENCE_CAPITAL = 10_000;
    private static final double RISK_PCT = 0.008;
    private static final int MIN_HISTORY = EMA_PERIOD + ATR_PERIOD + 10;
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

    public RSI3MeanReversion() {
        this("RSI3MeanReversion", "EUR_USD");
    }

    public RSI3MeanReversion(String name) {
        this(name, "EUR_USD");
    }

    public RSI3MeanReversion(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        int barDay = bar.timestamp().atZone(java.time.ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) {
            tradesToday = 0;
            lastTradeDay = barDay;
        }

        if (inTrade) {
            managePosition(bar);
        } else {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 1) return;
            evaluateEntry(bar);
        }
    }

    private void evaluateEntry(Bar bar) {
        double rsi = Indicators.rsi(history, RSI_PERIOD);
        double ema = Indicators.emaLatest(history, EMA_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);

        if (Double.isNaN(rsi) || Double.isNaN(ema) || Double.isNaN(atr) || atr <= 0) return;

        // Mean reversion: trade WITH the trend (EMA filter)
        // In uptrend: buy oversold conditions
        if (rsi < RSI_OVERSOLD && bar.close() > ema) {
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * SL_MULT;
            takeProfit = entryPrice + atr * TP_MULT;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET,
                Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol), entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            tradesToday++;
        }
        // In downtrend: sell overbought conditions
        else if (rsi > RSI_OVERBOUGHT && bar.close() < ema) {
            entryPrice = bar.close();
            stopLoss = entryPrice + atr * SL_MULT;
            takeProfit = entryPrice - atr * TP_MULT;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET,
                Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol), entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            tradesToday++;
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
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
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
        lastTradeDay = -1;
        tradesToday = 0;
        cooldownBars = 0;
    }
}
