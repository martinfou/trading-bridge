package com.martinfou.trading.strategies.longterm;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * LtPullbackEntry — Pullback Entry in Trend Direction
 *
 * Trend determined by EMA(200) / SMA(100) direction.
 * In uptrend: buy when price pulls back near EMA(50) with RSI(14) < 40 (oversold pullback).
 * In downtrend: sell when price pulls back near EMA(50) with RSI(14) > 60 (overbought pullback).
 *
 * Exit on opposite signal, SL=2xATR(14), TP=4xATR(14), max hold 480 bars (~20 days on H1).
 * Uses closeOnly() on all exits.
 *
 * Inspiration: Ed Seykota (trend is your friend) + Larry Hite (position sizing)
 */
public class LtPullbackEntry implements Strategy {

    private static final double EMA_FAST = 50;
    private static final int SMA_TREND = 100;
    private static final int ATR_PERIOD = 14;
    private static final int RSI_PERIOD = 14;
    private static final double ATR_MULT_SL = 2.0;
    private static final double ATR_MULT_TP = 4.0;
    private static final double MAX_POSITION = 2000;
    private static final double EMA_PROXIMITY = 0.001; // 0.1% proximity threshold
    private static final int RSI_OVERSOLD = 40;
    private static final int RSI_OVERBOUGHT = 60;
    private static final int MAX_HOLD_BARS = 480;
    private static final java.time.ZoneId TZ = java.time.ZoneId.of("America/New_York");

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side direction = Order.Side.BUY;
    private double entryPrice = 0;
    private double entrySl = 0;
    private double entryTp = 0;
    private int barsInTrade = 0;
    private int tradesToday = 0;
    private int lastTradeDay = -1;

    public LtPullbackEntry() { this("LtPullbackEntry", "EUR_USD"); }
    public LtPullbackEntry(String name) { this(name, "EUR_USD"); }
    public LtPullbackEntry(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        int size = history.size();
        if (size < SMA_TREND + ATR_PERIOD + 5) return;

        int barDay = bar.timestamp().atZone(TZ).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        double close = bar.close();
        double ema200 = Indicators.emaLatest(history, 200);
        double ema50 = Indicators.emaLatest(history, (int)EMA_FAST);
        double sma100 = Indicators.smaLatest(history, SMA_TREND);
        double rsi = Indicators.rsi(history, RSI_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);

        if (Double.isNaN(ema200) || Double.isNaN(ema50) || Double.isNaN(sma100)
            || Double.isNaN(rsi) || Double.isNaN(atr) || atr == 0) return;

        boolean isUptrend = close > sma100;
        boolean isDowntrend = close < sma100;
        double emaProximity = Math.abs(close - ema50) / ema50;

        if (inTrade) {
            barsInTrade++;
            boolean hitSl = direction == Order.Side.BUY ? close <= entrySl : close >= entrySl;
            boolean hitTp = direction == Order.Side.BUY ? close >= entryTp : close <= entryTp;

            // Exit on opposite signal, SL, TP, or max hold
            boolean oppositeExit = (direction == Order.Side.BUY && !isUptrend)
                                || (direction == Order.Side.SELL && !isDowntrend);
            if (oppositeExit || hitSl || hitTp || barsInTrade >= MAX_HOLD_BARS) {
                exitTrade(close);
                return;
            }
            return;
        }

        if (tradesToday >= 1) return;

        // Uptrend: buy on pullback near EMA(50) with RSI oversold
        if (isUptrend && emaProximity < EMA_PROXIMITY && rsi < RSI_OVERSOLD) {
            double stopLoss = close - atr * ATR_MULT_SL;
            double takeProfit = close + atr * ATR_MULT_TP;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, MAX_POSITION, close)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            entryPrice = close; entrySl = stopLoss; entryTp = takeProfit;
            direction = Order.Side.BUY; inTrade = true; tradesToday++; barsInTrade = 0;
        }
        // Downtrend: sell on pullback near EMA(50) with RSI overbought
        else if (isDowntrend && emaProximity < EMA_PROXIMITY && rsi > RSI_OVERBOUGHT) {
            double stopLoss = close + atr * ATR_MULT_SL;
            double takeProfit = close - atr * ATR_MULT_TP;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, MAX_POSITION, close)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            entryPrice = close; entrySl = stopLoss; entryTp = takeProfit;
            direction = Order.Side.SELL; inTrade = true; tradesToday++; barsInTrade = 0;
        }
    }

    @Override public void onTick(double bid, double ask, long volume) {}

    private void exitTrade(double price) {
        Order.Side exitSide = direction == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, 1000, price).closeOnly());
        inTrade = false;
    }

    @Override
    public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear(); pending.clear();
        inTrade = false; tradesToday = 0; lastTradeDay = -1;
        entryPrice = 0; entrySl = 0; entryTp = 0; barsInTrade = 0;
    }
}
