package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * ADX Trend Filter Strategy — Trend Following + Volatility
 *
 * 📊 Data insight: ADX (Average Directional Index) measures trend strength,
 *    not direction. When ADX(14) > 25 the market is trending; when < 20 it's
 *    ranging. DI+ and DI- lines show direction. Combining ADX > 25 with
 *    DI+/DI- cross provides high-probability entries with strong momentum.
 *
 * 🔧 Mechanism: ADX trend filter + DI cross entries.
 *    - Calculate ADX(14), DI+(14), DI-(14)
 *    - Only trade when ADX > 25 (trending) AND ADX rising
 *    - DI+ > DI- AND DI+ rising → BUY on pullback to 10 EMA
 *    - DI- > DI+ AND DI- rising → SELL on rally to 10 EMA
 *    - Exit when ADX falls below 20 or DI cross reverses
 *
 * 🎯 Originality: Pure trend-strength filtering. Unlike momentum strategies
 *    that assume the trend continues, this only enters when the trend is
 *    strong AND still developing. Works across all assets — ADX is
 *    normalized and comparable.
 */
public class ADXTrendFilterStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 1000;

    public ADXTrendFilterStrategy() {
        this("ADXTrendFilter", "GBP/JPY");
    }

    public ADXTrendFilterStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public ADXTrendFilterStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 30) return;

        double atr = calculateATR(14);
        double adx = calculateADX(14);

        // Manage active trade
        if (inTrade) {
            barsHeld++;
            if (barsHeld >= 6 || adx < 18) { closePosition(bar); return; }

            if (tradeDirection == Order.Side.BUY) {
                if (bar.high() >= entryPrice + atr * 1.8) { closePosition(bar); return; }
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar); return; }
            } else {
                if (bar.low() <= entryPrice - atr * 1.8) { closePosition(bar); return; }
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar); return; }
            }
            return;
        }

        // Only trade when ADX > 25 (strong trend)
        if (adx < 25) return;

        double[] di = calculateDI(14);
        double diPlus = di[0];
        double diMinus = di[1];

        // Need ADX rising vs 3 bars ago for confirmation
        double adxPrev = 0;
        if (history.size() > 18) {
            int prevIdx = history.size() - 4;
            if (prevIdx >= 15) {
                adxPrev = calculateADXAt(14, prevIdx);
            }
        }

        double ema10 = calculateEMA(10);

        // DI+ > DI- → bullish trend
        if (diPlus > diMinus && diPlus > 25 && adx > adxPrev) {
            // Buy on pullback to EMA10
            double touchZone = atr * 0.3;
            if (bar.low() <= ema10 + touchZone && bar.close() > ema10 && bar.close() > bar.open()) {
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); barsHeld = 0;
            }
        }
        // DI- > DI+ → bearish trend
        else if (diMinus > diPlus && diMinus > 25 && adx > adxPrev) {
            double touchZone = atr * 0.3;
            if (bar.high() >= ema10 - touchZone && bar.close() < ema10 && bar.close() < bar.open()) {
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); barsHeld = 0;
            }
        }
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()));
        inTrade = false;
    }

    private double calculateADX(int period) {
        return calculateADXAt(period, history.size() - 1);
    }

    private double calculateADXAt(int period, int idx) {
        if (idx < period * 2) return 0;

        int start = idx - period * 2 + 1;
        if (start < 1) return 0;

        // Calculate +DM, -DM, TR for each bar
        double[] plusDM = new double[period * 2];
        double[] minusDM = new double[period * 2];
        double[] tr = new double[period * 2];

        for (int i = 0; i < period * 2 && start + i < history.size(); i++) {
            int barIdx = start + i;
            Bar curr = history.get(barIdx);
            Bar prev = history.get(barIdx - 1);

            double upMove = curr.high() - prev.high();
            double downMove = prev.low() - curr.low();

            if (upMove > downMove && upMove > 0) plusDM[i] = upMove; else plusDM[i] = 0;
            if (downMove > upMove && downMove > 0) minusDM[i] = downMove; else minusDM[i] = 0;

            tr[i] = Math.max(curr.high() - curr.low(),
                Math.max(Math.abs(curr.high() - prev.close()), Math.abs(curr.low() - prev.close())));
        }

        // Smooth using Wilder's method
        double avgPlusDM = 0, avgMinusDM = 0, avgTR = 0;
        for (int i = 0; i < period; i++) { avgPlusDM += plusDM[i]; avgMinusDM += minusDM[i]; avgTR += tr[i]; }
        avgPlusDM /= period; avgMinusDM /= period; avgTR /= period;

        for (int i = period; i < period * 2; i++) {
            avgPlusDM = (avgPlusDM * (period - 1) + plusDM[i]) / period;
            avgMinusDM = (avgMinusDM * (period - 1) + minusDM[i]) / period;
            avgTR = (avgTR * (period - 1) + tr[i]) / period;
        }

        if (avgTR == 0) return 0;
        double diPlus = 100 * avgPlusDM / avgTR;
        double diMinus = 100 * avgMinusDM / avgTR;
        double dx = Math.abs(diPlus - diMinus) / (diPlus + diMinus) * 100;

        return dx;
    }

    private double[] calculateDI(int period) {
        int idx = history.size() - 1;
        if (idx < period + 1) return new double[]{0, 0};

        double upSum = 0, downSum = 0, trSum = 0;
        for (int i = idx - period + 1; i <= idx; i++) {
            Bar curr = history.get(i);
            Bar prev = history.get(i - 1);
            double upMove = curr.high() - prev.high();
            double downMove = prev.low() - curr.low();

            if (upMove > downMove && upMove > 0) upSum += upMove;
            if (downMove > upMove && downMove > 0) downSum += downMove;

            trSum += Math.max(curr.high() - curr.low(),
                Math.max(Math.abs(curr.high() - prev.close()), Math.abs(curr.low() - prev.close())));
        }

        return new double[]{100 * upSum / trSum, 100 * downSum / trSum};
    }

    private double calculateEMA(int period) {
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
