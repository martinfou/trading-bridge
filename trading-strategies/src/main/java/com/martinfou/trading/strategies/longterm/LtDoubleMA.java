package com.martinfou.trading.strategies.longterm;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;

import java.time.ZoneId;
import java.util.*;

/**
 * LtDoubleMA — Golden Cross / Death Cross Strategy (Long Term).
 *
 * 📊 Concept: EMA(50) / SMA(200) crossover on H1 bars.
 *   - Golden cross: EMA(50) crosses above SMA(200) → BUY
 *   - Death cross: EMA(50) crosses below SMA(200) → SELL
 *
 * 🔧 Mechanism:
 *   - Track previous bar's EMA(50) and SMA(200) to detect crossover
 *   - SL = 2 × ATR(14), TP = 4 × ATR(14)
 *   - Max 1 trade per day
 *   - Exit on opposite cross, SL/TP hit, or max hold
 *   - closeOnly() on all exit orders
 */
public class LtDoubleMA implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double entryStop = 0;
    private double entryTarget = 0;
    private int barsHeld = 0;
    private int lastTradeDay = -1;

    private double prevEma50 = Double.NaN;
    private double prevSma200 = Double.NaN;

    private static final int FAST_PERIOD = 50;
    private static final int SLOW_PERIOD = 200;
    private static final int ATR_PERIOD = 14;
    private static final double SL_ATR = 2.0;
    private static final double TP_ATR = 4.0;
    private static final int MAX_BARS_HOLD = 480; // ~20 days in H1
    private static final int MIN_HISTORY = SLOW_PERIOD + 1;

    public LtDoubleMA(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        // Daily trade limit: max 1 per day
        int barDay = bar.timestamp().atZone(ZoneId.of("America/New_York")).getDayOfYear();
        if (inTrade && barDay != lastTradeDay) {
            // New day while in trade — keep tracking position
        }

        double ema50 = Indicators.emaLatest(history, FAST_PERIOD);
        double sma200 = Indicators.smaLatest(history, SLOW_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);

        if (Double.isNaN(ema50) || Double.isNaN(sma200) || Double.isNaN(atr) || atr <= 0) {
            // Store current values for next bar's crossover detection
            prevEma50 = ema50;
            prevSma200 = sma200;
            return;
        }

        if (inTrade) {
            barsHeld++;
            boolean crossedOut = false;

            // Death cross exit (if long) / Golden cross exit (if short)
            if (tradeDirection == Order.Side.BUY) {
                if (!Double.isNaN(prevEma50) && !Double.isNaN(prevSma200)
                    && prevEma50 >= prevSma200 && ema50 < sma200) {
                    // Death cross while long → exit
                    closePosition(bar.close());
                    crossedOut = true;
                }
            } else {
                if (!Double.isNaN(prevEma50) && !Double.isNaN(prevSma200)
                    && prevEma50 <= prevSma200 && ema50 > sma200) {
                    // Golden cross while short → exit
                    closePosition(bar.close());
                    crossedOut = true;
                }
            }

            if (crossedOut) {
                prevEma50 = ema50;
                prevSma200 = sma200;
                return;
            }

            // Stop loss
            if (tradeDirection == Order.Side.BUY && bar.low() <= entryStop) {
                closePosition(entryStop);
                prevEma50 = ema50;
                prevSma200 = sma200;
                return;
            }
            if (tradeDirection == Order.Side.SELL && bar.high() >= entryStop) {
                closePosition(entryStop);
                prevEma50 = ema50;
                prevSma200 = sma200;
                return;
            }

            // Take profit
            if (tradeDirection == Order.Side.BUY && bar.high() >= entryTarget) {
                closePosition(entryTarget);
                prevEma50 = ema50;
                prevSma200 = sma200;
                return;
            }
            if (tradeDirection == Order.Side.SELL && bar.low() <= entryTarget) {
                closePosition(entryTarget);
                prevEma50 = ema50;
                prevSma200 = sma200;
                return;
            }

            // Max hold
            if (barsHeld >= MAX_BARS_HOLD) {
                closePosition(bar.close());
                prevEma50 = ema50;
                prevSma200 = sma200;
                return;
            }

            prevEma50 = ema50;
            prevSma200 = sma200;
            return;
        }

        // --- Entry logic ---
        // Max 1 trade per day
        if (barDay == lastTradeDay) {
            prevEma50 = ema50;
            prevSma200 = sma200;
            return;
        }

        // Detect crossover using previous bar's values
        if (!Double.isNaN(prevEma50) && !Double.isNaN(prevSma200)) {
            // Golden cross: EMA(50) crosses ABOVE SMA(200)
            if (prevEma50 <= prevSma200 && ema50 > sma200) {
                entryPrice = bar.close();
                entryStop = entryPrice - atr * SL_ATR;
                entryTarget = entryPrice + atr * TP_ATR;
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, 1000, entryPrice)
                    .withStopLoss(entryStop).withTakeProfit(entryTarget));
                inTrade = true;
                tradeDirection = Order.Side.BUY;
                barsHeld = 0;
                lastTradeDay = barDay;
            }
            // Death cross: EMA(50) crosses BELOW SMA(200)
            else if (prevEma50 >= prevSma200 && ema50 < sma200) {
                entryPrice = bar.close();
                entryStop = entryPrice + atr * SL_ATR;
                entryTarget = entryPrice - atr * TP_ATR;
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, 1000, entryPrice)
                    .withStopLoss(entryStop).withTakeProfit(entryTarget));
                inTrade = true;
                tradeDirection = Order.Side.SELL;
                barsHeld = 0;
                lastTradeDay = barDay;
            }
        }

        prevEma50 = ema50;
        prevSma200 = sma200;
    }

    private void closePosition(double price) {
        pending.add(new Order(symbol, tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
            Order.Type.MARKET, 1000, price).closeOnly());
        inTrade = false;
    }

    @Override
    public void onTick(double bid, double ask, long volume) {}

    @Override
    public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        tradeDirection = Order.Side.BUY;
        entryPrice = 0;
        entryStop = 0;
        entryTarget = 0;
        barsHeld = 0;
        lastTradeDay = -1;
        prevEma50 = Double.NaN;
        prevSma200 = Double.NaN;
    }
}
