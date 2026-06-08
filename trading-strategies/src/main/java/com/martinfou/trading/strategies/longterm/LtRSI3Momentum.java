package com.martinfou.trading.strategies.longterm;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * LtRSI3Momentum — RSI(3) Drift Following (Long-Term)
 *
 * 📊 Continuous RSI drift following strategy. Uses RSI(3) to detect directional
 *    momentum drift rather than extreme-based mean reversion.
 *
 * 🔧 Mechanism:
 *    - RSI(3) > 60 with price above EMA(200) → bullish momentum → BUY
 *    - RSI(3) < 40 with price below EMA(200) → bearish momentum → SELL
 *    - Exit when RSI(3) crosses back into the 40-60 neutral zone
 *    - SL = 2× ATR(14), TP = 4× ATR(14)
 *    - Max 1 trade per day
 *    - Close-only exits
 */
public class LtRSI3Momentum implements Strategy {

    private static final int MIN_HISTORY = 210;
    private static final int RSI_PERIOD = 3;
    private static final int EMA_PERIOD = 200;
    private static final int ATR_PERIOD = 14;
    private static final double RSI_BULLISH = 60.0;
    private static final double RSI_BEARISH = 40.0;
    private static final double SL_MULT = 2.0;
    private static final double TP_MULT = 4.0;
    private static final double MIN_POSITION = 1000;
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

    public LtRSI3Momentum(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    public LtRSI3Momentum() {
        this("LtRSI3Momentum", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        // Daily trade counter (reset on new day)
        int barDay = bar.timestamp().atZone(java.time.ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) {
            tradesToday = 0;
            lastTradeDay = barDay;
        }

        if (inTrade) {
            managePosition(bar);
        } else {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 1) return; // max 1 trade per day
            evaluateEntry(bar);
        }
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

    private void evaluateEntry(Bar bar) {
        double rsi = Indicators.rsi(history, RSI_PERIOD);
        double ema200 = Indicators.emaLatest(history, EMA_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);

        if (Double.isNaN(rsi) || Double.isNaN(ema200) || Double.isNaN(atr) || atr <= 0) return;

        if (rsi > RSI_BULLISH && bar.close() > ema200) {
            // Bullish momentum drift — enter LONG
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * SL_MULT;
            takeProfit = entryPrice + atr * TP_MULT;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, MIN_POSITION, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            tradesToday++;
        } else if (rsi < RSI_BEARISH && bar.close() < ema200) {
            // Bearish momentum drift — enter SHORT
            entryPrice = bar.close();
            stopLoss = entryPrice + atr * SL_MULT;
            takeProfit = entryPrice - atr * TP_MULT;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, MIN_POSITION, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            tradesToday++;
        }
    }

    private void managePosition(Bar bar) {
        // Check stop loss
        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);

        // Check take profit
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

        // Check RSI re-entry into neutral zone (40-60)
        double rsi = Indicators.rsi(history, RSI_PERIOD);
        if (Double.isNaN(rsi)) return;

        if (tradeDirection == Order.Side.BUY && rsi <= RSI_BULLISH) {
            // RSI crossed back below 60 → momentum fading → exit
            exitPosition(bar.close());
        } else if (tradeDirection == Order.Side.SELL && rsi >= RSI_BEARISH) {
            // RSI crossed back above 40 → momentum fading → exit
            exitPosition(bar.close());
        }
    }

    private void exitPosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, MIN_POSITION, price).closeOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }
}
