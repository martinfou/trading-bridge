package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.ZoneId;
import java.util.*;

/**
 * JPYSpecMomentum — Follows COT bullish speculator positioning on JPY pairs.
 *
 * Rationale: USD/JPY COT spec ratio is 1.50:1 (strongest bullish), USD/CHF
 * is 1.33:1. These are the only two pairs with net spec long. Strategy
 * enters on momentum breakouts during liquid sessions (London/NY overlap),
 * following institutional flow.
 *
 * Best on: USD/JPY, USD/CHF (COT spec long)
 * Entry: 20-period EMA slope positive + breakout above previous bar high
 * Exit: SL at 1.0×ATR, TP at 2.0×ATR (wider for trend), max hold 8 bars
 */
public class JPYSpecMomentumStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double entryStop = 0;
    private double entryTarget = 0;
    private double positionSize = 1000;
    private int cooldownBars = 0;
    private int tradesToday = 0;
    private int lastTradeDay = -1;
    private static final int COOLDOWN_BARS = 8;
    private static final int MAX_TRADES_PER_DAY = 3;
    // London (07:00-16:00 UTC) + NY (13:00-21:00 UTC) overlap for JPY liquidity
    private static final int SESSION_START = 7;
    private static final int SESSION_END = 21;

    public JPYSpecMomentumStrategy() { this("⚡ JPY Spec Momentum", "USD/JPY"); }
    public JPYSpecMomentumStrategy(String name) { this(name, "USD/JPY"); }
    public JPYSpecMomentumStrategy(String name, String symbol) { this.name = name; this.symbol = symbol; }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 24) return;

        // Daily trade counter
        int barDay = bar.timestamp().atZone(ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        double atr = calculateATR(14);
        if (atr <= 0) return;

        if (inTrade) {
            barsHeld++;
            if (tradeDirection == Order.Side.BUY && bar.low() <= entryStop) { closePosition(entryStop); return; }
            if (tradeDirection == Order.Side.SELL && bar.high() >= entryStop) { closePosition(entryStop); return; }
            if (tradeDirection == Order.Side.BUY && bar.high() >= entryTarget) { closePosition(entryTarget); return; }
            if (tradeDirection == Order.Side.SELL && bar.low() <= entryTarget) { closePosition(entryTarget); return; }
            if (barsHeld >= 8) { closePosition(bar.close()); return; }
            return;
        }

        if (cooldownBars > 0) { cooldownBars--; return; }
        if (tradesToday >= MAX_TRADES_PER_DAY) return;

        // Session filter — London/NY for maximum JPY liquidity
        int hour = bar.timestamp().atZone(ZoneId.of("UTC")).getHour();
        if (hour < SESSION_START || hour > SESSION_END) return;

        // Calculate EMAs
        double ema20 = calcEMA(20);
        double ema8 = calcEMA(8);
        double ema8Prev = calcEMA(8, history.size() - 2);
        if (ema20 <= 0 || ema8 <= 0) return;

        // ATR-based breakout threshold
        double breakoutThreshold = atr * 0.3;

        // Long entry: EMA8 > EMA20 (trend up) + price breaking above recent high
        if (bar.close() > ema20 && bar.close() > ema8 && ema8 > ema8Prev) {
            // Check: close > previous bar high (momentum breakout)
            Bar prev = history.get(history.size() - 2);
            if (bar.close() > prev.high() + breakoutThreshold) {
                entryPrice = bar.close();
                entryStop = entryPrice - atr * 1.0;    // SL 1.0×ATR below
                entryTarget = entryPrice + atr * 2.0;  // TP 2.0×ATR above (trend wider)
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                    .withStopLoss(entryStop).withTakeProfit(entryTarget));
                inTrade = true;
                tradeDirection = Order.Side.BUY;
                barsHeld = 0;
                cooldownBars = 0;
                tradesToday++;
            }
        }
        // Short entry: EMA8 < EMA20 (trend down) + price breaking below recent low
        else if (bar.close() < ema20 && bar.close() < ema8 && ema8 < ema8Prev) {
            Bar prev = history.get(history.size() - 2);
            if (bar.close() < prev.low() - breakoutThreshold) {
                entryPrice = bar.close();
                entryStop = entryPrice + atr * 1.0;    // SL 1.0×ATR above
                entryTarget = entryPrice - atr * 2.0;  // TP 2.0×ATR below
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                    .withStopLoss(entryStop).withTakeProfit(entryTarget));
                inTrade = true;
                tradeDirection = Order.Side.SELL;
                barsHeld = 0;
                cooldownBars = 0;
                tradesToday++;
            }
        }
    }

    private void closePosition(double price) {
        pending.add(new Order(symbol, tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
            Order.Type.MARKET, positionSize, price));
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }

    private double calcEMA(int period) {
        return calcEMA(period, history.size());
    }

    private double calcEMA(int period, int upTo) {
        if (upTo < period + 2) return 0;
        // Simple SMA as seed
        double sma = 0;
        int start = upTo - period;
        for (int i = start; i < upTo; i++) {
            sma += history.get(i).close();
        }
        sma /= period;
        // EMA
        double multiplier = 2.0 / (period + 1);
        double ema = sma;
        for (int i = start + 1; i < upTo; i++) {
            ema = (history.get(i).close() - ema) * multiplier + ema;
        }
        return ema;
    }

    private double calculateATR(int period) {
        int size = history.size();
        double sum = 0;
        int count = 0;
        for (int i = Math.max(1, size - period); i < size; i++) {
            Bar prev = history.get(i - 1);
            Bar curr = history.get(i);
            double tr = Math.max(curr.high() - curr.low(),
                Math.max(Math.abs(curr.high() - prev.close()), Math.abs(curr.low() - prev.close())));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 1.0;
    }

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }
    @Override public void reset() {
        history.clear(); pending.clear(); inTrade = false; tradeDirection = Order.Side.BUY;
        entryPrice = 0; barsHeld = 0; entryStop = 0; entryTarget = 0;
        cooldownBars = 0; tradesToday = 0; lastTradeDay = -1;
    }

    public void restoreState(int tradesToday, int lastTradeDay, boolean inTrade,
                             Order.Side tradeDirection, int cooldownBars) {
        this.tradesToday = tradesToday;
        this.lastTradeDay = lastTradeDay;
        this.inTrade = inTrade;
        this.tradeDirection = tradeDirection;
        this.cooldownBars = cooldownBars;
    }
    public int getTradesToday() { return tradesToday; }
    public int getLastTradeDay() { return lastTradeDay; }
    public boolean isInTrade() { return inTrade; }
    public Order.Side getTradeDirection() { return tradeDirection; }
    public int getCooldownBars() { return cooldownBars; }
}
