package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Triple EMA Crossover Strategy — Trend Following
 *
 * 📊 Data insight: In trending markets, multiple EMAs filter out false signals.
 *    EMA(9) crossing above both EMA(21) and EMA(55) → strong uptrend.
 *    When all three align (9 > 21 > 55 for bull, 9 < 21 < 55 for bear),
 *    the trend is established and pullbacks to EMA(21) offer high-RR entries.
 *
 * 🔧 Mechanism: Trend confirmation via EMA alignment + pullback entry.
 *    - Calculate EMA(9), EMA(21), EMA(55)
 *    - Bullish: EMA9 > EMA21 > EMA55 → buy on pullback to EMA21
 *    - Bearish: EMA9 < EMA21 < EMA55 → sell on rally to EMA21
 *    - Exit when EMA alignment breaks or at ATR target
 *
 * 🎯 Originality: Unlike simple MA cross, this waits for full alignment and
 *    uses the middle EMA as entry trigger. Pullback entry improves RR vs
 *    breakout entry. Works on any trending instrument.
 */
public class TripleEMACrossoverStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 1000;

    public TripleEMACrossoverStrategy() {
        this("TripleEMACrossover", "GBP/JPY");
    }

    public TripleEMACrossoverStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public TripleEMACrossoverStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 60) return;

        double ema9 = calculateEMA(9);
        double ema21 = calculateEMA(21);
        double ema55 = calculateEMA(55);
        double atr = calculateATR(14);

        // Manage active trade
        if (inTrade) {
            barsHeld++;

            if (barsHeld >= 6) { closePosition(bar); return; }

            if (tradeDirection == Order.Side.BUY) {
                if (bar.high() >= entryPrice + atr * 2.0) { closePosition(bar); return; }
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar); return; }
                // Exit if EMA alignment breaks
                if (ema9 < ema21) { closePosition(bar); return; }
            } else {
                if (bar.low() <= entryPrice - atr * 2.0) { closePosition(bar); return; }
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar); return; }
                if (ema9 > ema21) { closePosition(bar); return; }
            }
            return;
        }

        // Bullish alignment: EMA9 > EMA21 > EMA55
        boolean bullAlign = ema9 > ema21 && ema21 > ema55;
        // Bearish alignment: EMA9 < EMA21 < EMA55
        boolean bearAlign = ema9 < ema21 && ema21 < ema55;

        if (bullAlign) {
            // Buy on pullback to near EMA21
            double touchBuffer = atr * 0.3;
            if (bar.low() <= ema21 + touchBuffer && bar.close() > ema21 && bar.close() > bar.open()) {
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); barsHeld = 0;
            }
        } else if (bearAlign) {
            double touchBuffer = atr * 0.3;
            if (bar.high() >= ema21 - touchBuffer && bar.close() < ema21 && bar.close() < bar.open()) {
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); barsHeld = 0;
            }
        }
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()).closeOnly());
        inTrade = false;
    }

    private double calculateEMA(int period) {
        if (history.size() < period) return history.getLast().close();
        double k = 2.0 / (period + 1);
        double ema = history.get(0).close();
        for (int i = 1; i < history.size(); i++) {
            ema = history.get(i).close() * k + ema * (1 - k);
        }
        return ema;
    }

    private double calculateATR(int period) {
        int size = history.size();
        double sum = 0; int count = 0;
        for (int i = Math.max(1, size - period); i < size; i++) {
            Bar prev = history.get(i - 1); Bar curr = history.get(i);
            double tr = Math.max(curr.high() - curr.low(),
                Math.max(Math.abs(curr.high() - prev.close()), Math.abs(curr.low() - prev.close())));
            sum += tr; count++;
        }
        return count > 0 ? sum / count : 1.0;
    }

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending); pending.clear(); return copy;
    }
    @Override public void reset() {
        history.clear(); pending.clear(); inTrade = false;
        tradeDirection = Order.Side.BUY; entryPrice = 0; barsHeld = 0;
    }
}
