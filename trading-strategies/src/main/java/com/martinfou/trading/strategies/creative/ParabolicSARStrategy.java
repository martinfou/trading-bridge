package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Parabolic SAR Trend Strategy — Trend Following + Custom Indicator
 *
 * 📊 Data insight: The Parabolic SAR (Stop and Reverse) was designed by
 *    Welles Wilder to identify trend direction and provide trailing stops.
 *    When SAR is below price → uptrend. When SAR is above price → downtrend.
 *    SAR flips mark potential trend changes. Combining SAR flips with
 *    a trend filter (ADX or EMA slope) reduces whipsaws.
 *
 * 🔧 Mechanism:
 *    - Calculate Parabolic SAR (acceleration factor 0.02, max 0.20)
 *    - BUY when SAR flips from above to below price AND EMA(20) sloping up
 *    - SELL when SAR flips from below to above price AND EMA(20) sloping down
 *    - Trail stop using SAR value (follows price automatically)
 *    - Exit when SAR flips direction
 *
 * 🎯 Originality: The Parabolic SAR is rarely implemented in H1 multi-asset
 *    backtesting frameworks. The SAR's adaptive trailing stop nature makes
 *    it ideal for capturing trends while protecting gains.
 */
public class ParabolicSARStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double positionSize = 1000;

    // SAR state
    private double sar = 0;
    private double ep = 0;    // extreme point
    private double af = 0.02; // acceleration factor
    private boolean sarUp = false;  // true if SAR is above price (downtrend)

    public ParabolicSARStrategy() {
        this("ParabolicSAR", "GBP/JPY");
    }

    public ParabolicSARStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public ParabolicSARStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 25) return;

        double atr = calculateATR(14);
        double prevSar = sar;

        // Update Parabolic SAR
        updateSAR(bar);

        // EMA slope filter
        double ema20 = calculateEMA(20);
        double ema20Prev = 0;
        if (history.size() > 23) {
            double k = 2.0 / 21.0;
            ema20Prev = history.get(0).close();
            for (int i = 1; i < history.size() - 3; i++)
                ema20Prev = history.get(i).close() * k + ema20Prev * (1 - k);
        } else {
            return;
        }
        boolean emaUp = ema20 > ema20Prev;
        boolean emaDown = ema20 < ema20Prev;

        // Manage active trade
        if (inTrade) {
            // SAR flip = exit
            boolean sarFlip = (tradeDirection == Order.Side.BUY && sar > bar.close()) ||
                              (tradeDirection == Order.Side.SELL && sar < bar.close());
            if (sarFlip) { closePosition(bar); return; }

            // Trail with SAR
            if (tradeDirection == Order.Side.BUY) {
                if (bar.low() <= Math.max(entryPrice - atr * 1.5, sar)) { closePosition(bar); return; }
                if (bar.high() >= entryPrice + atr * 2.5) { closePosition(bar); return; }
            } else {
                if (bar.high() >= Math.min(entryPrice + atr * 1.5, sar)) { closePosition(bar); return; }
                if (bar.low() <= entryPrice - atr * 2.5) { closePosition(bar); return; }
            }
            return;
        }

        // Detect SAR flip
        boolean sarFlippedUp = (prevSar > 0 && sarUp == false && sar < bar.close()) ||
                               (prevSar > 0 && sarUp == false && bar.close() > ep * 0.999);
        boolean sarFlippedDown = (prevSar > 0 && sarUp == true && sar > bar.close()) ||
                                (prevSar > 0 && sarUp == true && bar.close() < ep * 1.001);

        // BUY: SAR flips to below price + EMA up
        if (sarFlippedUp && emaUp && !sarUp) {
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); return;
        }

        // SELL: SAR flips to above price + EMA down
        if (sarFlippedDown && emaDown && sarUp) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); return;
        }
    }

    private void updateSAR(Bar bar) {
        if (history.size() < 3) return;

        if (sar == 0) {
            // Initialize
            if (history.get(0).low() <= history.get(1).low()) {
                // Initial downtrend
                sarUp = true;
                sar = history.get(0).high();
                ep = history.get(0).low();
            } else {
                sarUp = false;
                sar = history.get(0).low();
                ep = history.get(0).high();
            }
            return;
        }

        // Calculate new SAR
        double newSar = sar + af * (ep - sar);

        // Ensure SAR doesn't violate prior bars
        if (sarUp) {
            // In downtrend: SAR is above price
            // Cannot move above prior two bars' lows
            for (int i = Math.max(0, history.size() - 3); i < history.size() - 1; i++) {
                newSar = Math.min(newSar, history.get(i).low());
            }
            if (bar.low() < newSar) newSar = bar.low();

            // Check for flip to uptrend
            if (bar.low() <= sar) { // SAR above price and price crosses through it = flip
                sarUp = false;
                sar = ep; // flip: SAR goes to previous extreme
                ep = bar.high();
                af = 0.02;
            } else {
                sar = newSar;
                if (bar.low() < ep) {
                    ep = bar.low();
                    af = Math.min(af + 0.02, 0.20);
                }
            }
        } else {
            // In uptrend: SAR is below price
            for (int i = Math.max(0, history.size() - 3); i < history.size() - 1; i++) {
                newSar = Math.max(newSar, history.get(i).high());
            }
            if (bar.high() > newSar) newSar = bar.high();

            if (bar.high() >= sar) { // price crosses through SAR from below = flip to downtrend
                sarUp = true;
                sar = ep;
                ep = bar.low();
                af = 0.02;
            } else {
                sar = newSar;
                if (bar.high() > ep) {
                    ep = bar.high();
                    af = Math.min(af + 0.02, 0.20);
                }
            }
        }
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()).closeOnly());
        inTrade = false;
    }

    private double calculateEMA(int period) {
        double k = 2.0 / (period + 1);
        double ema = history.get(0).close();
        for (int i = 1; i < history.size(); i++)
            ema = history.get(i).close() * k + ema * (1 - k);
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
        tradeDirection = Order.Side.BUY; entryPrice = 0;
        sar = 0; ep = 0; af = 0.02; sarUp = false;
    }
}
