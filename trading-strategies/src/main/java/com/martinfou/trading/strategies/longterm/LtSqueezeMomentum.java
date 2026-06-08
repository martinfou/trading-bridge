package com.martinfou.trading.strategies.longterm;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * LtSqueezeMomentum — Bollinger Squeeze + RSI Momentum Confirmation
 *
 * Bollinger Bands (20, 2.0) bandwidth squeeze indicates low volatility.
 * During squeeze, RSI(14) determines directional bias:
 *   - RSI > 55 → bullish momentum → BUY
 *   - RSI < 45 → bearish momentum → SELL
 * Exit when bandwidth expands beyond 0.10 or SL/TP hit.
 * SL=2xATR(14), TP=4xATR(14), max hold 240 bars (~10 days on H1).
 * Uses closeOnly() on all exits.
 *
 * Inspiration: Quantified Strategies (squeeze play) + J.W. Henry (trend following)
 */
public class LtSqueezeMomentum implements Strategy {

    private static final int BB_PERIOD = 20;
    private static final double BB_MULT = 2.0;
    private static final double SQUEEZE_THRESHOLD = 0.05;
    private static final double EXPAND_THRESHOLD = 0.10;
    private static final int RSI_PERIOD = 14;
    private static final double RSI_BULL = 55;
    private static final double RSI_BEAR = 45;
    private static final int ATR_PERIOD = 14;
    private static final double ATR_MULT_SL = 2.0;
    private static final double ATR_MULT_TP = 4.0;
    private static final double MAX_POSITION = 2000;
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
    private double entryBandwidth = 0;
    private int barsInTrade = 0;
    private int tradesToday = 0;
    private int lastTradeDay = -1;

    public LtSqueezeMomentum() { this("LtSqueezeMomentum", "EUR_USD"); }
    public LtSqueezeMomentum(String name) { this(name, "EUR_USD"); }
    public LtSqueezeMomentum(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        int size = history.size();
        if (size < BB_PERIOD + 10) return;

        int barDay = bar.timestamp().atZone(TZ).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        double[] bb = Indicators.bollingerWidth(history, BB_PERIOD, BB_MULT);
        double bandwidth = bb[2];
        double rsi = Indicators.rsi(history, RSI_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);
        if (Double.isNaN(bandwidth) || Double.isNaN(rsi) || Double.isNaN(atr) || atr == 0) return;

        double close = bar.close();

        if (inTrade) {
            barsInTrade++;

            // Exit on bandwidth expansion
            boolean expanded = bandwidth > EXPAND_THRESHOLD && bandwidth > entryBandwidth * 1.5;
            boolean hitSl = direction == Order.Side.BUY ? close <= entrySl : close >= entrySl;
            boolean hitTp = direction == Order.Side.BUY ? close >= entryTp : close <= entryTp;

            if (expanded || hitSl || hitTp || barsInTrade >= MAX_HOLD_BARS) {
                exitTrade(close);
                return;
            }
            return;
        }

        if (tradesToday >= 1) return;

        // Only trade during squeeze
        if (bandwidth > SQUEEZE_THRESHOLD) return;

        // RSI confirms squeeze direction
        if (rsi > RSI_BULL) {
            double stopLoss = close - atr * ATR_MULT_SL;
            double takeProfit = close + atr * ATR_MULT_TP;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, MAX_POSITION, close)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            entryPrice = close; entrySl = stopLoss; entryTp = takeProfit;
            entryBandwidth = bandwidth;
            direction = Order.Side.BUY; inTrade = true; tradesToday++; barsInTrade = 0;
        } else if (rsi < RSI_BEAR) {
            double stopLoss = close + atr * ATR_MULT_SL;
            double takeProfit = close - atr * ATR_MULT_TP;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, MAX_POSITION, close)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            entryPrice = close; entrySl = stopLoss; entryTp = takeProfit;
            entryBandwidth = bandwidth;
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
        entryPrice = 0; entrySl = 0; entryTp = 0; entryBandwidth = 0; barsInTrade = 0;
    }
}
