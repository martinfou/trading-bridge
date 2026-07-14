package com.martinfou.trading.strategies.longterm;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * LtDoubleMA — Golden Cross / Death Cross (EMA 50 / SMA 200)
 *
 * Entry:
 * - EMA(50) crosses above SMA(200) → BUY (golden cross)
 * - EMA(50) crosses below SMA(200) → SELL (death cross)
 *
 * Exit: opposite cross, SL=2xATR(14), TP=4xATR(14), max hold 240 bars (~10 days on H1)
 * Use closeOnly() on all exits.
 *
 * Inspiration: Turtle system (classic) + Ray Dalio (long-term trend)
 */
public class LtDoubleMA implements Strategy {

    private static final int EMA_FAST = 50;
    private static final int SMA_SLOW = 200;
    private static final int ATR_PERIOD = 14;
    private static final double ATR_MULT_SL = 2.0;
    private static final double ATR_MULT_TP = 4.0;
    private static final double REFERENCE_CAPITAL = 10_000;
    private static final double RISK_PCT = 0.01;
    private static final int MAX_HOLD_BARS = 240;
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
    private boolean wasPreviousEmaAboveSma = false;

    public LtDoubleMA() { this("LtDoubleMA", "EUR_USD"); }
    public LtDoubleMA(String name) { this(name, "EUR_USD"); }
    public LtDoubleMA(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        int size = history.size();
        if (size < SMA_SLOW + ATR_PERIOD + 1) return;

        // Day tracking
        int barDay = bar.timestamp().atZone(TZ).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        double ema50 = Indicators.emaLatest(history, EMA_FAST);
        double sma200 = Indicators.smaLatest(history, SMA_SLOW);
        double atr = Indicators.atr(history, ATR_PERIOD);
        if (Double.isNaN(ema50) || Double.isNaN(sma200) || Double.isNaN(atr) || atr == 0) return;

        boolean emaAboveSma = ema50 > sma200;
        boolean crossUp = emaAboveSma && !wasPreviousEmaAboveSma;
        boolean crossDown = !emaAboveSma && wasPreviousEmaAboveSma;
        wasPreviousEmaAboveSma = emaAboveSma;

        double close = bar.close();

        if (inTrade) {
            barsInTrade++;
            boolean hitSl = direction == Order.Side.BUY ? close <= entrySl : close >= entrySl;
            boolean hitTp = direction == Order.Side.BUY ? close >= entryTp : close <= entryTp;

            // Exit on opposite cross, SL, TP, or max hold
            boolean oppositeCross = (direction == Order.Side.BUY && crossDown)
                                 || (direction == Order.Side.SELL && crossUp);
            if (oppositeCross || hitSl || hitTp || barsInTrade >= MAX_HOLD_BARS) {
                exitTrade(close);
                return;
            }
            return;
        }

        if (tradesToday >= 1) return;

        double sl = atr * ATR_MULT_SL;
        double tp = atr * ATR_MULT_TP;

        if (crossUp) {
            double stopLoss = close - sl;
            double takeProfit = close + tp;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, ATR_MULT_SL, symbol), close)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            entryPrice = close; entrySl = stopLoss; entryTp = takeProfit;
            direction = Order.Side.BUY; inTrade = true; tradesToday++; barsInTrade = 0;
        } else if (crossDown) {
            double stopLoss = close + sl;
            double takeProfit = close - tp;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, ATR_MULT_SL, symbol), close)
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
        wasPreviousEmaAboveSma = false;
    }
}
