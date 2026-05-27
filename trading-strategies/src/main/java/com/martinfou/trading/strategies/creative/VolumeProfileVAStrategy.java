package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Volume Profile Value Area Strategy — Volume + Support/Resistance
 *
 * 📊 Data insight: The Volume Profile identifies price levels where the
 *    most trading activity occurred (High Volume Node / HVN). These levels
 *    act as magnets — price tends to return to the value area. The Value
 *    Area (where 70% of volume occurred) serves as dynamic support/resistance.
 *    Breakouts above/below the VA trigger continuation moves.
 *
 * 🔧 Mechanism:
 *    - Track volume each bar (if available) or use candle range as volume proxy
 *    - Build a rolling volume profile over the last 24 bars (1 day H1)
 *    - Identify Point of Control (POC = price level with highest volume)
 *    - Calculate Value Area (levels around POC containing 70% of volume)
 *    - BUY when price pulls back to VA lower edge (value area support)
 *    - SELL when price rallies to VA upper edge (value area resistance)
 *    - Also: trend continuation when price breaks outside VA with momentum
 *
 * 🎯 Originality: Simulates volume profile analysis for multi-asset use,
 *    which is traditionally only used on futures. The rolling window approach
 *    adapts to each asset's volatility automatically.
 */
public class VolumeProfileVAStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private boolean atValueArea = false;
    private boolean atOppositeValueArea = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double positionSize = 10000;

    // Value Area tracking
    private double poc = 0;          // Point of Control
    private double vaHigh = 0;       // Value Area High
    private double vaLow = 0;        // Value Area Low
    private boolean hasVA = false;

    public VolumeProfileVAStrategy() {
        this("VolumeProfileVA", "GBP/JPY");
    }

    public VolumeProfileVAStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public VolumeProfileVAStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 30) return;

        double atr = calculateATR(14);

        // Build rolling volume profile every 24 bars
        // Use 20 price brackets, bar range as volume proxy
        if (history.size() % 24 == 0 || !hasVA) {
            buildVolumeProfile();
        }

        // Manage active trade
        if (inTrade) {
            if (tradeDirection == Order.Side.BUY) {
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar); return; }
                if (bar.high() >= entryPrice + atr * 2.0) { closePosition(bar); return; }
                // Exit if price closes below VA low
                if (hasVA && bar.close() < vaLow - atr * 0.5) { closePosition(bar); return; }
            } else {
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar); return; }
                if (bar.low() <= entryPrice - atr * 2.0) { closePosition(bar); return; }
                if (hasVA && bar.close() > vaHigh + atr * 0.5) { closePosition(bar); return; }
            }
            return;
        }

        if (!hasVA) return;

        double price = bar.close();

        // Check for value area bounces
        boolean nearVALow = price >= vaLow - atr * 0.2 && price <= vaLow + atr * 0.3;
        boolean nearVAHigh = price <= vaHigh + atr * 0.2 && price >= vaHigh - atr * 0.3;
        boolean pocAbovePrice = poc > price;
        boolean pocBelowPrice = poc < price;

        // Mean reversion: buy at VA low when POC is above (bullish structure)
        if (nearVALow && pocAbovePrice && bar.close() > bar.open()) {
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); return;
        }

        // Mean reversion: sell at VA high when POC is below (bearish structure)
        if (nearVAHigh && pocBelowPrice && bar.close() < bar.open()) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); return;
        }

        // Breakout continuation: price breaks above VA high with momentum
        if (price > vaHigh + atr * 0.5 && bar.high() - bar.low() > atr * 0.8 && bar.close() > bar.open()) {
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); return;
        }

        // Breakout continuation: price breaks below VA low with momentum
        if (price < vaLow - atr * 0.5 && bar.high() - bar.low() > atr * 0.8 && bar.close() < bar.open()) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); return;
        }
    }

    private void buildVolumeProfile() {
        int window = Math.min(24, history.size() - 1);
        int start = history.size() - window;

        // Find price range
        double minPrice = Double.MAX_VALUE, maxPrice = Double.MIN_VALUE;
        for (int i = start; i < history.size(); i++) {
            minPrice = Math.min(minPrice, history.get(i).low());
            maxPrice = Math.max(maxPrice, history.get(i).high());
        }

        if (maxPrice <= minPrice) return;

        int numBrackets = 20;
        double bracketSize = (maxPrice - minPrice) / numBrackets;
        if (bracketSize <= 0) return;

        // Accumulate volume (range proxy) in each bracket
        double[] volumeByBracket = new double[numBrackets];
        for (int i = start; i < history.size(); i++) {
            Bar b = history.get(i);
            double midpoint = (b.high() + b.low()) / 2.0;
            int bracket = (int) ((midpoint - minPrice) / bracketSize);
            if (bracket >= numBrackets) bracket = numBrackets - 1;
            if (bracket < 0) bracket = 0;
            // Use range as volume proxy
            volumeByBracket[bracket] += (b.high() - b.low());
        }

        // Find POC (bracket with most volume)
        int pocBracket = 0;
        double maxVol = 0;
        for (int i = 0; i < numBrackets; i++) {
            if (volumeByBracket[i] > maxVol) {
                maxVol = volumeByBracket[i];
                pocBracket = i;
            }
        }

        double totalVolume = 0;
        for (double v : volumeByBracket) totalVolume += v;

        if (totalVolume <= 0) return;

        // Expand around POC to get 70% of volume (Value Area)
        double vaVolume = 0;
        double vaTarget = totalVolume * 0.70;
        int upperBracket = pocBracket;
        int lowerBracket = pocBracket;

        vaVolume += volumeByBracket[pocBracket];

        while (vaVolume < vaTarget) {
            boolean canExpandUp = upperBracket < numBrackets - 1;
            boolean canExpandDown = lowerBracket > 0;

            if (!canExpandUp && !canExpandDown) break;

            if (canExpandUp && canExpandDown) {
                double upVal = volumeByBracket[upperBracket + 1];
                double downVal = volumeByBracket[lowerBracket - 1];
                if (upVal >= downVal) {
                    upperBracket++; vaVolume += upVal;
                } else {
                    lowerBracket--; vaVolume += downVal;
                }
            } else if (canExpandUp) {
                upperBracket++; vaVolume += volumeByBracket[upperBracket];
            } else {
                lowerBracket--; vaVolume += volumeByBracket[lowerBracket];
            }
        }

        poc = minPrice + pocBracket * bracketSize + bracketSize / 2.0;
        vaHigh = minPrice + upperBracket * bracketSize + bracketSize;
        vaLow = minPrice + lowerBracket * bracketSize;
        hasVA = true;
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
        poc = 0; vaHigh = 0; vaLow = 0; hasVA = false;
    }
}
