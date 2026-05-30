package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * EMA Pullback Strategy — Trend Following with Pullback/Rally Entries
 *
 * 📊 Data insight: In strong trends, price often pulls back to a key moving
 *    average before resuming the trend direction. Waiting for these pullbacks
 *    provides better risk-reward than breakout entries.
 *
 * 🔧 Mechanism: SMA(20) as EMA proxy for trend filter + pullback re-entry.
 *    - Calculate SMA(20) and ATR(14)
 *    - Trend filter: close > SMA(20) for 3+ consecutive bars → uptrend.
 *      close < SMA(20) for 3+ bars → downtrend.
 *    - In uptrend, wait for a pullback (close < SMA(20)), then re-enter on
 *      the next bar that closes back above SMA(20) with a bullish close.
 *    - In downtrend, wait for a rally (close > SMA(20)), then re-enter on
 *      the next bar that closes back below SMA(20) with a bearish close.
 *    - SL: 1.5×ATR, TP: 2.5×ATR (bigger target for trend trades)
 *    - Max hold: 12 bars
 *
 * 🎯 Originality: Unlike simple MA cross strategies, this intentionally
 *    waits for a counter-trend bar (pullback/rally) and then re-enters
 *    with confirmation. The 3-bar trend filter prevents whipsaws, and
 *    the asymmetric SL/TP (1.5 vs 2.5) captures trend extensions.
 */
public class EMAPullbackStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 1000;

    // Trend tracking state
    private boolean inTrend = false;
    private Order.Side trendDirection = null; // BUY = uptrend (above SMA), SELL = downtrend (below SMA)
    private int trendBars = 0;

    // Pullback re-entry state
    private boolean waitingForReentry = false;
    private boolean pullbackDetected = false;
    private Order.Side reentryDirection = null; // the trend direction we want to re-enter

    public EMAPullbackStrategy() {
        this("EMAPullback", "GBP/JPY");
    }

    public EMAPullbackStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public EMAPullbackStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 25) return;

        double sma20 = calculateSMA(20);
        double atr = calculateATR(14);

        // --- Manage active trade ---
        if (inTrade) {
            barsHeld++;

            // Max hold exit
            if (barsHeld >= 12) { closePosition(bar.close()); return; }

            // SL / TP checks
            if (tradeDirection == Order.Side.BUY) {
                if (bar.low() <= entryPrice - atr * 1.5) { closePosition(bar.close()); return; }
                if (bar.high() >= entryPrice + atr * 2.5) { closePosition(bar.close()); return; }
            } else {
                if (bar.high() >= entryPrice + atr * 1.5) { closePosition(bar.close()); return; }
                if (bar.low() <= entryPrice - atr * 2.5) { closePosition(bar.close()); return; }
            }
            return;
        }

        boolean aboveSma = bar.close() > sma20;
        boolean belowSma = bar.close() < sma20;

        // --- Re-entry check (from pullback/rally on previous bar) ---
        if (waitingForReentry && reentryDirection != null) {
            if (reentryDirection == Order.Side.BUY) {
                // Uptrend pullback: wait for bar to close above SMA with bullish close
                if (aboveSma && bar.close() > bar.open()) {
                    pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                    inTrade = true;
                    tradeDirection = Order.Side.BUY;
                    entryPrice = bar.close();
                    barsHeld = 0;
                    waitingForReentry = false;
                    pullbackDetected = false;
                    reentryDirection = null;
                    // Reset trend tracking so we don't re-enter immediately again
                    resetTrendState();
                    return;
                }
            } else {
                // Downtrend rally: wait for bar to close below SMA with bearish close
                if (belowSma && bar.close() < bar.open()) {
                    pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                    inTrade = true;
                    tradeDirection = Order.Side.SELL;
                    entryPrice = bar.close();
                    barsHeld = 0;
                    waitingForReentry = false;
                    pullbackDetected = false;
                    reentryDirection = null;
                    resetTrendState();
                    return;
                }
            }

            // If this bar continued against the trend (e.g., second pullback bar),
            // keep waiting but update state tracking
        }

        // --- Trend tracking ---
        if (aboveSma) {
            if (trendDirection == Order.Side.BUY) {
                // Continuing above SMA
                trendBars++;
            } else {
                // Direction changed to above SMA
                // If we were in an established downtrend, this is a rally
                if (trendDirection == Order.Side.SELL && inTrend) {
                    pullbackDetected = true;
                    waitingForReentry = true;
                    reentryDirection = Order.Side.SELL; // want to re-enter the downtrend
                }
                trendDirection = Order.Side.BUY;
                trendBars = 1;
                inTrend = false;
            }
        } else if (belowSma) {
            if (trendDirection == Order.Side.SELL) {
                // Continuing below SMA
                trendBars++;
            } else {
                // Direction changed to below SMA
                // If we were in an established uptrend, this is a pullback
                if (trendDirection == Order.Side.BUY && inTrend) {
                    pullbackDetected = true;
                    waitingForReentry = true;
                    reentryDirection = Order.Side.BUY; // want to re-enter the uptrend
                }
                trendDirection = Order.Side.SELL;
                trendBars = 1;
                inTrend = false;
            }
        } else {
            // Price exactly at SMA — reset trend
            resetTrendState();
        }

        // Check if trend is established (3+ consecutive bars)
        if (trendBars >= 3 && !inTrend && trendDirection != null) {
            inTrend = true;
        }
    }

    private void resetTrendState() {
        inTrend = false;
        trendDirection = null;
        trendBars = 0;
    }

    private void closePosition(double price) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, price));
        inTrade = false;
    }

    private double calculateSMA(int period) {
        int size = history.size();
        double sum = 0;
        int count = 0;
        for (int i = Math.max(0, size - period); i < size; i++) {
            sum += history.get(i).close();
            count++;
        }
        return count > 0 ? sum / count : 0;
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
        barsHeld = 0;
        inTrend = false;
        trendDirection = null;
        trendBars = 0;
        waitingForReentry = false;
        pullbackDetected = false;
        reentryDirection = null;
    }
}
