package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

public class VolatilitySpikeFadeStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 1000;
    private boolean pendingFade = false;
    private Order.Side fadeDirection = Order.Side.BUY;
    
    public VolatilitySpikeFadeStrategy() { this("VolSpikeFade", "GBP/JPY"); }
    public VolatilitySpikeFadeStrategy(String name) { this(name, "GBP/JPY"); }
    public VolatilitySpikeFadeStrategy(String name, String symbol) { this.name = name; this.symbol = symbol; }
    @Override public String name() { return name; }
    
    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 25) return;
        double atr = calculateATR(14);
        
        // If we had a pending fade from previous bar, execute at this bar's open
        if (pendingFade && !inTrade) {
            pending.add(new Order(symbol, fadeDirection, Order.Type.MARKET, positionSize, bar.open()));
            inTrade = true; tradeDirection = fadeDirection; entryPrice = bar.open(); barsHeld = 0;
            pendingFade = false;
        }
        
        // Manage active trade
        if (inTrade) {
            barsHeld++;
            if (barsHeld >= 4) { closePosition(bar.close()); return; }
            if (tradeDirection == Order.Side.BUY) {
                if (bar.low() <= entryPrice - atr * 1.5) { closePosition(bar.close()); return; }
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar.close()); return; }
            } else {
                if (bar.high() >= entryPrice + atr * 1.5) { closePosition(bar.close()); return; }
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar.close()); return; }
            }
            return;
        }
        
        // Detect volatility spike
        double avgRange = 0; int cnt = 0;
        for (int i = Math.max(0, history.size() - 21); i < history.size() - 1; i++) {
            avgRange += (history.get(i).high() - history.get(i).low()); cnt++;
        }
        if (cnt > 0) avgRange /= cnt;
        
        double currentRange = bar.high() - bar.low();
        if (currentRange > avgRange * 2.5) {
            pendingFade = true;
            fadeDirection = (bar.close() > bar.open()) ? Order.Side.SELL : Order.Side.BUY;
        }
    }
    
    private void closePosition(double price) {
        pending.add(new Order(symbol, tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY, Order.Type.MARKET, positionSize, price).closeOnly());
        inTrade = false;
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
        history.clear(); pending.clear(); inTrade = false; tradeDirection = Order.Side.BUY; entryPrice = 0; barsHeld = 0; pendingFade = false;
    }
}
