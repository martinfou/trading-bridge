package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Dual Timeframe Conviction Strategy — Trend Following + Multi-Timeframe
 *
 * 📊 Data insight: Single-timeframe strategies suffer from false signals.
 *    By aligning H1 entries with a higher-timeframe (H4 synthetic) trend,
 *    we filter out counter-trend noise. When both timeframes agree, the
 *    probability of continuation increases significantly. The H4 trend is
 *    synthesized by comparing EMA(48) on H1 bars (≈ H4 across 12 H1 bars).
 *
 * 🔧 Mechanism:
 *    - Calculate H1 trend: EMA(12) direction + slope
 *    - Calculate H4 trend (synthetic): EMA(48) of H1 closes
 *    - BUY when: H1 bull (EMA12 > EMA26) AND H4 bull (close > EMA48)
 *      AND H1 pulls back to EMA12 support
 *    - SELL when: H1 bear (EMA12 < EMA26) AND H4 bear (close < EMA48)
 *      AND H1 rallies to EMA12 resistance
 *    - Exit when H1 trend reverses or ATR stop hit
 *
 * 🎯 Originality: Pure multi-timeframe alignment on a single data source
 *    (synthetic H4 from H1). No extra data feed needed. The EMA(48) acts as
 *    a robust higher-timeframe filter that adapts to each asset's cycle.
 */
public class DualTimeframeConvictionStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double positionSize = 10000;

    public DualTimeframeConvictionStrategy() {
        this("DualTimeframe", "GBP/JPY");
    }

    public DualTimeframeConvictionStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public DualTimeframeConvictionStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 55) return;

        double atr = calculateATR(14);

        // H1 trend: EMA12 vs EMA26
        double ema12 = calculateEMA(12);
        double ema26 = calculateEMA(26);
        boolean h1Bull = ema12 > ema26;
        boolean h1Bear = ema12 < ema26;

        // H1 slope (momentum): EMA12 direction over 3 bars
        double ema12Prev = calculateEMAPrev(12, 3);
        boolean h1MomentumUp = ema12 > ema12Prev;
        boolean h1MomentumDown = ema12 < ema12Prev;

        // H4 trend: EMA48 (48 H1 bars = ~2 days for synthetic H4)
        double ema48 = calculateEMA(48);
        boolean h4Bull = bar.close() > ema48;
        boolean h4Bear = bar.close() < ema48;

        // Manage active trade
        if (inTrade) {
            // Exit if H1 trend reverses against us
            if (tradeDirection == Order.Side.BUY && h1Bear) { closePosition(bar); return; }
            if (tradeDirection == Order.Side.SELL && h1Bull) { closePosition(bar); return; }

            if (tradeDirection == Order.Side.BUY) {
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar); return; }
                if (bar.high() >= entryPrice + atr * 2.0) { closePosition(bar); return; }
            } else {
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar); return; }
                if (bar.low() <= entryPrice - atr * 2.0) { closePosition(bar); return; }
            }
            return;
        }

        // BUY: H1 bull + H4 bull + pullback to EMA12
        if (h1Bull && h4Bull && h1MomentumUp) {
            // Enter on pullback toward EMA12
            double touchZone = atr * 0.4;
            if (bar.low() <= ema12 + touchZone && bar.close() > ema12 && bar.close() > bar.open()) {
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); return;
            }
        }

        // SELL: H1 bear + H4 bear + pullback to EMA12
        if (h1Bear && h4Bear && h1MomentumDown) {
            double touchZone = atr * 0.4;
            if (bar.high() >= ema12 - touchZone && bar.close() < ema12 && bar.close() < bar.open()) {
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); return;
            }
        }
    }

    private double calculateEMA(int period) {
        double k = 2.0 / (period + 1);
        double ema = history.get(0).close();
        for (int i = 1; i < history.size(); i++)
            ema = history.get(i).close() * k + ema * (1 - k);
        return ema;
    }

    private double calculateEMAPrev(int period, int barsBack) {
        if (history.size() <= barsBack) return calculateEMA(period);
        double k = 2.0 / (period + 1);
        double ema = history.get(0).close();
        int limit = history.size() - barsBack;
        for (int i = 1; i < limit; i++)
            ema = history.get(i).close() * k + ema * (1 - k);
        return ema;
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()));
        inTrade = false;
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
        tradeDirection = Order.Side.BUY; entryPrice = 0;
    }
}
