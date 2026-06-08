package com.martinfou.trading.strategies.longterm;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;

import java.time.ZoneId;
import java.util.*;

/**
 * Strategy #9: LtPullbackEntry — Pullback entry in EMA(200) trend direction.
 *
 * 📊 Concept: Trend-following pullback entries on H1 bars.
 *   - Trend: close &gt; SMA(100) = uptrend, close &lt; SMA(100) = downtrend
 *   - Uptrend: buy when price is within 0.1% of EMA(50) AND RSI(14) &lt; 40
 *   - Downtrend: sell when price is within 0.1% of EMA(50) AND RSI(14) &gt; 60
 *   - SL = 2× ATR(14), TP = 4× ATR(14)
 *   - Max 1 trade per day
 *   - Exit on opposite signal or SL/TP
 *   - closeOnly() on all exit orders
 */
public class LtPullbackEntry implements Strategy {

    private static final int EMA_FAST = 50;
    private static final int SMA_TREND = 100;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final double ATR_SL_MULT = 2.0;
    private static final double ATR_TP_MULT = 4.0;
    private static final double PRICE_NEAR_THRESHOLD = 0.001; // 0.1%
    private static final int MIN_HISTORY = 210; // enough for EMA(200) + warm-up
    private static final int MAX_BARS_HOLD = 480; // ~20 days in H1

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice;
    private double entryStop;
    private double entryTarget;
    private int barsHeld;
    private int lastTradeDay = -1;

    public LtPullbackEntry(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    public LtPullbackEntry(String symbol) {
        this("LtPullbackEntry", symbol);
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        int barDay = bar.timestamp().atZone(ZoneId.of("America/New_York")).getDayOfYear();

        if (inTrade) {
            managePosition(bar);
        } else {
            // Max 1 trade per day
            if (barDay == lastTradeDay) return;
            evaluateEntry(bar, barDay);
        }
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
    }

    // ── Entry logic ──────────────────────────────────────────────────────────

    private void evaluateEntry(Bar bar, int barDay) {
        double ema50 = Indicators.emaLatest(history, EMA_FAST);
        double sma100 = Indicators.smaLatest(history, SMA_TREND);
        double rsi = Indicators.rsi(history, RSI_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);

        if (Double.isNaN(ema50) || Double.isNaN(sma100) || Double.isNaN(rsi) || Double.isNaN(atr) || atr <= 0) {
            return;
        }

        double close = bar.close();

        // Check if price is near EMA(50) within 0.1%
        boolean nearEma50 = Math.abs(close - ema50) / Math.max(ema50, 1e-10) <= PRICE_NEAR_THRESHOLD;
        if (!nearEma50) return;

        boolean uptrend = close > sma100;
        boolean downtrend = close < sma100;

        if (uptrend && rsi < 40) {
            // Oversold pullback in uptrend → BUY
            entryPrice = close;
            entryStop = close - atr * ATR_SL_MULT;
            entryTarget = close + atr * ATR_TP_MULT;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, 1000, entryPrice)
                .withStopLoss(entryStop).withTakeProfit(entryTarget));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsHeld = 0;
            lastTradeDay = barDay;
        } else if (downtrend && rsi > 60) {
            // Overbought pullback in downtrend → SELL
            entryPrice = close;
            entryStop = close + atr * ATR_SL_MULT;
            entryTarget = close - atr * ATR_TP_MULT;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, 1000, entryPrice)
                .withStopLoss(entryStop).withTakeProfit(entryTarget));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            barsHeld = 0;
            lastTradeDay = barDay;
        }
    }

    // ── Position management ───────────────────────────────────────────────────

    private void managePosition(Bar bar) {
        barsHeld++;

        double close = bar.close();

        // 1. Opposite signal exit
        if (checkOppositeSignal(bar)) {
            closePosition(close);
            return;
        }

        // 2. Stop loss
        if (tradeDirection == Order.Side.BUY && bar.low() <= entryStop) {
            closePosition(entryStop);
            return;
        }
        if (tradeDirection == Order.Side.SELL && bar.high() >= entryStop) {
            closePosition(entryStop);
            return;
        }

        // 3. Take profit
        if (tradeDirection == Order.Side.BUY && bar.high() >= entryTarget) {
            closePosition(entryTarget);
            return;
        }
        if (tradeDirection == Order.Side.SELL && bar.low() <= entryTarget) {
            closePosition(entryTarget);
            return;
        }

        // 4. Max hold
        if (barsHeld >= MAX_BARS_HOLD) {
            closePosition(close);
        }
    }

    /**
     * Check for opposite signal: exit long when downtrend + near EMA(50) + RSI &gt; 60,
     * exit short when uptrend + near EMA(50) + RSI &lt; 40.
     */
    private boolean checkOppositeSignal(Bar bar) {
        double ema50 = Indicators.emaLatest(history, EMA_FAST);
        double sma100 = Indicators.smaLatest(history, SMA_TREND);
        double rsi = Indicators.rsi(history, RSI_PERIOD);

        if (Double.isNaN(ema50) || Double.isNaN(sma100) || Double.isNaN(rsi)) {
            return false;
        }

        double close = bar.close();
        boolean nearEma50 = Math.abs(close - ema50) / Math.max(ema50, 1e-10) <= PRICE_NEAR_THRESHOLD;
        if (!nearEma50) return false;

        if (tradeDirection == Order.Side.BUY) {
            // Exit long if downtrend + RSI > 60
            return close < sma100 && rsi > 60;
        } else {
            // Exit short if uptrend + RSI < 40
            return close > sma100 && rsi < 40;
        }
    }

    private void closePosition(double price) {
        pending.add(new Order(symbol,
            tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
            Order.Type.MARKET, 1000, price).closeOnly());
        inTrade = false;
    }
}
