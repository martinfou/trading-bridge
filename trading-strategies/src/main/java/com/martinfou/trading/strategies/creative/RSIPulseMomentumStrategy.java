package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * RSIPulseMomentum — RSI(3) drift + EMA(200) trend filter + manual ATR SL/TP
 *
 * 📊 Data insight: RSI(3) drift above 60 / below 40 signals short-term momentum
 *    bursts. EMA(200) on H1 acts as the major trend filter (similar to Daily
 *    EMA(200) in the proven LtRSI3Momentum). Manual SL/TP management avoids
 *    engine sync issues.
 *
 * 🔧 Mechanism:
 *    - RSI(3) > 60 AND close > EMA(200) → BUY momentum drift
 *    - RSI(3) < 40 AND close < EMA(200) → SELL momentum drift
 *    - Manual SL = 1.5× ATR(14), TP = 2.5× ATR(14)
 *    - RSI crossing back through 60/40 → early exit on momentum fade
 *    - closeOnly() exits to properly close opposite positions
 *    - Max 1 trade per day per proven LtRSI3 pattern
 *
 * 🎯 Originality: LtRSI3Momentum adapted for H1 (tighter SL/TP, same
 *    RSI+EMA(200) concept). Uses closeOnly() for reliable position closure.
 */
public class RSIPulseMomentumStrategy implements Strategy {

    private static final int RSI_PERIOD = 3;
    private static final int EMA_PERIOD = 200;
    private static final int ATR_PERIOD = 14;
    private static final double RSI_BULLISH = 60.0;
    private static final double RSI_BEARISH = 40.0;
    private static final double SL_MULT = 1.5;
    private static final double TP_MULT = 2.5;
    private static final double REFERENCE_CAPITAL = 1_000;
    private static final double RISK_PCT = 0.008;
    private static final int MIN_HISTORY = EMA_PERIOD + ATR_PERIOD + 10;

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

    public RSIPulseMomentumStrategy() {
        this("RSIPulseMomentum", "EUR_USD");
    }

    public RSIPulseMomentumStrategy(String name) {
        this(name, "EUR_USD");
    }

    public RSIPulseMomentumStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        // Compute indicators on OLD history first (avoid look-ahead bias)
        if (history.size() < MIN_HISTORY - 1) { history.add(bar); return; }
        double rsi = Indicators.rsi(history, RSI_PERIOD);
        double ema200 = Indicators.emaLatest(history, EMA_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);
        history.add(bar);

        int barDay = bar.timestamp().atZone(java.time.ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) {
            tradesToday = 0;
            lastTradeDay = barDay;
        }

        if (inTrade) {
            managePosition(bar);
        } else {
            if (tradesToday >= 1) return;
            evaluateEntry(bar, rsi, ema200, atr);
        }
    }

    private void evaluateEntry(Bar bar, double rsi, double ema200, double atr) {
        if (Double.isNaN(rsi) || Double.isNaN(ema200) || Double.isNaN(atr) || atr <= 0) return;

        if (rsi > RSI_BULLISH && bar.close() > ema200) {
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * SL_MULT;
            takeProfit = entryPrice + atr * TP_MULT;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET,
                Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol), entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            tradesToday++;
        } else if (rsi < RSI_BEARISH && bar.close() < ema200) {
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
        // Check stop loss (intra-bar)
        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);

        // Check take profit (intra-bar)
        boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= takeProfit)
            || (tradeDirection == Order.Side.SELL && bar.low() <= takeProfit);

        if (stopHit) {
            exitPosition(stopLoss);
            return;
        }
        if (tpHit) {
            exitPosition(takeProfit);
            return;
        }

        // Check RSI re-entry into neutral zone (momentum fade)
        double rsi = Indicators.rsi(history, RSI_PERIOD);
        if (Double.isNaN(rsi)) return;

        if (tradeDirection == Order.Side.BUY && rsi <= RSI_BULLISH) {
            exitPosition(bar.close());
        } else if (tradeDirection == Order.Side.SELL && rsi >= RSI_BEARISH) {
            exitPosition(bar.close());
        }
    }

    private void exitPosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, 1000, price).closeOnly());
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
        lastTradeDay = -1;
        tradesToday = 0;
    }
}
