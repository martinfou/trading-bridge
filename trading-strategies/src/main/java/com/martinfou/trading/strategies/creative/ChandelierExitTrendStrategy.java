package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

public class ChandelierExitTrendStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 1000;
    
    private double highestHigh = 0;
    private double lowestLow = Double.MAX_VALUE;
    private double trailStop = 0;
    
    public ChandelierExitTrendStrategy() { this("ChandelierTrend", "GBP/JPY"); }
    public ChandelierExitTrendStrategy(String name) { this(name, "GBP/JPY"); }
    public ChandelierExitTrendStrategy(String name, String symbol) { this.name = name; this.symbol = symbol; }
    @Override public String name() { return name; }
    
    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 55) return; // Need enough for SMA(50)
        double atr = calculateATR(14);
        double sma50 = calculateSMA(50);
        
        // Manage active trade with chandelier trail
        if (inTrade) {
            barsHeld++;
            if (barsHeld >= 20) { closePosition(bar.close()); return; }
            
            // Update trailing stop
            if (tradeDirection == Order.Side.BUY) {
                if (bar.high() > highestHigh) {
                    highestHigh = bar.high();
                    trailStop = highestHigh - 2.5 * atr;
                }
                // Check trail stop
                if (bar.low() <= trailStop) { closePosition(bar.close()); return; }
                // Hard stop
                if (bar.low() <= entryPrice - 2.5 * atr) { closePosition(bar.close()); return; }
            } else {
                if (bar.low() < lowestLow) {
                    lowestLow = bar.low();
                    trailStop = lowestLow + 2.5 * atr;
                }
                if (bar.high() >= trailStop) { closePosition(bar.close()); return; }
                if (bar.high() >= entryPrice + 2.5 * atr) { closePosition(bar.close()); return; }
            }
            return;
        }
        
        // Entry: strong momentum bar with trend confirmation
        double range = bar.high() - bar.low();
        double rangePct = (bar.close() - bar.low()) / range; // 0-1 where 1 = close at high
        
        if (range > atr * 1.0) {
            if (rangePct > 0.75 && bar.close() > sma50) {
                // Strong bullish momentum + uptrend
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); barsHeld = 0;
                highestHigh = bar.high(); trailStop = bar.high() - 2.5 * atr;
            } else if (rangePct < 0.25 && bar.close() < sma50) {
                // Strong bearish momentum + downtrend
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); barsHeld = 0;
                lowestLow = bar.low(); trailStop = bar.low() + 2.5 * atr;
            }
        }
    }
    
    private void closePosition(double price) {
        pending.add(new Order(symbol, tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY, Order.Type.MARKET, positionSize, price).closeOnly());
        inTrade = false;
    }
    
    private double calculateSMA(int period) {
        int size = history.size(); double sum = 0; int count = 0;
        for (int i = Math.max(0, size - period); i < size; i++) { sum += history.get(i).close(); count++; }
        return count > 0 ? sum / count : 0;
    }
    
    private double calculateATR(int period) {
        int size = history.size(); double sum = 0; int count = 0;
        for (int i = Math.max(1, size - period); i < size; i++) {
            Bar prev = history.get(i - 1); Bar curr = history.get(i);
            double tr = Math.max(curr.high() - curr.low(), Math.max(Math.abs(curr.high() - prev.close()), Math.abs(curr.low() - prev.close())));
            sum += tr; count++;
        }
        return count > 0 ? sum / count : 1.0;
    }
    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending); pending.clear(); return copy;
    }
    @Override public void reset() {
        history.clear(); pending.clear(); inTrade = false; tradeDirection = Order.Side.BUY; entryPrice = 0; barsHeld = 0;
        highestHigh = 0; lowestLow = Double.MAX_VALUE; trailStop = 0;
    }
}
