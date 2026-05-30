package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Fractal Pattern Breakout Strategy — Pattern Recognition + Breakout
 *
 * 📊 Data insight: Bill Williams' fractal pattern identifies natural
 *    turning points in price action. A bullish fractal is formed when
 *    a bar has a lower low than both bars before and after it (5-bar
 *    pattern: middle bar low < bars on each side). These fractal levels
 *    act as natural support/resistance. When price breaks through a
 *    recent fractal level, it signals trend continuation.
 *
 * 🔧 Mechanism:
 *    - Detect 5-bar fractal patterns (up fractal: high, down fractal: low)
 *    - Up fractal (resistance): bar.high[i] > bar.high[i-2..i+2]
 *    - Down fractal (support): bar.low[i] < bar.low[i-2..i+2]
 *    - Store last N fractals as key price levels
 *    - BUY when price breaks above the most recent up fractal level
 *    - SELL when price breaks below the most recent down fractal level
 *    - Stop at ATR, target 2×ATR
 *
 * 🎯 Originality: Implements Williams Fractals — a genuine pattern recognition
 *    technique rarely implemented. Natural S/R levels from fractal geometry.
 */
public class FractalPatternStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    // Fractal levels
    private final List<Double> upFractals = new ArrayList<>();   // resistance levels
    private final List<Double> downFractals = new ArrayList<>(); // support levels
    private final List<Integer> upFractalBars = new ArrayList<>();
    private final List<Integer> downFractalBars = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double positionSize = 1000;

    // Fractal that triggered this trade
    private double triggerLevel = 0;

    public FractalPatternStrategy() {
        this("FractalPattern", "GBP/JPY");
    }

    public FractalPatternStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public FractalPatternStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 10) return;

        double atr = calculateATR(14);

        // Detect fractals (need 5-bar pattern: i-2, i-1, i, i+1, i+2)
        if (history.size() >= 5) {
            int midIdx = history.size() - 3; // 2 bars ago is the midpoint
            if (midIdx >= 2) {
                // Up fractal: high higher than 2 bars on each side
                Bar mid = history.get(midIdx);
                boolean upFractal = true;
                boolean downFractal = true;

                for (int offset = -2; offset <= 2; offset++) {
                    if (offset == 0) continue;
                    int idx = midIdx + offset;
                    if (idx < 0 || idx >= history.size()) continue;
                    Bar other = history.get(idx);
                    if (mid.high() <= other.high()) upFractal = false;
                    if (mid.low() >= other.low()) downFractal = false;
                }

                if (upFractal) {
                    upFractals.add(mid.high());
                    upFractalBars.add(midIdx);
                }
                if (downFractal) {
                    downFractals.add(mid.low());
                    downFractalBars.add(midIdx);
                }
            }
        }

        if (upFractals.size() < 1 && downFractals.size() < 1) return;

        // Manage active trade
        if (inTrade) {
            if (tradeDirection == Order.Side.BUY) {
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar); return; }
                // Exit when price reaches next up fractal (resistance) or 2×ATR
                double target = Math.min(entryPrice + atr * 2.0, findNearestUpFractal(entryPrice));
                if (bar.high() >= target) { closePosition(bar); return; }
            } else {
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar); return; }
                double target = Math.max(entryPrice - atr * 2.0, findNearestDownFractal(entryPrice));
                if (bar.low() <= target) { closePosition(bar); return; }
            }
            return;
        }

        // Get most recent fractals
        double latestUpFractal = upFractals.isEmpty() ? 0 : upFractals.get(upFractals.size() - 1);
        double latestDownFractal = downFractals.isEmpty() ? 0 : downFractals.get(downFractals.size() - 1);

        // BUY: break above latest up fractal with momentum
        if (latestUpFractal > 0 && bar.high() > latestUpFractal && bar.close() > bar.open()) {
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close();
            triggerLevel = latestUpFractal; return;
        }

        // SELL: break below latest down fractal with momentum
        if (latestDownFractal > 0 && bar.low() < latestDownFractal && bar.close() < bar.open()) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close();
            triggerLevel = latestDownFractal; return;
        }
    }

    private double findNearestUpFractal(double price) {
        double nearest = Double.MAX_VALUE;
        for (double f : upFractals) {
            if (f > price && f < nearest) nearest = f;
        }
        return nearest == Double.MAX_VALUE ? price * 1.02 : nearest;
    }

    private double findNearestDownFractal(double price) {
        double nearest = -Double.MAX_VALUE;
        for (double f : downFractals) {
            if (f < price && f > nearest) nearest = f;
        }
        return nearest == -Double.MAX_VALUE ? price * 0.98 : nearest;
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
        history.clear(); pending.clear(); upFractals.clear(); downFractals.clear();
        upFractalBars.clear(); downFractalBars.clear();
        inTrade = false; tradeDirection = Order.Side.BUY; entryPrice = 0; triggerLevel = 0;
    }
}
