package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

public class ConsecutiveBarExhaustionStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 10000;
    
    public ConsecutiveBarExhaustionStrategy() { this("ConsecBarExhaust", "GBP/JPY"); }
    public ConsecutiveBarExhaustionStrategy(String name) { this(name, "GBP/JPY"); }
    public ConsecutiveBarExhaustionStrategy(String name, String symbol) { this.name = name; this.symbol = symbol; }
    
    @Override public String name() { return name; }
    
    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 20) return;
        double atr = calculateATR(14);
        
        if (inTrade) {
            barsHeld++;
            if (barsHeld >= 5) { closePosition(bar.close()); return; }
            if (tradeDirection == Order.Side.BUY) {
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar.close()); return; }
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar.close()); return; }
            } else {
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar.close()); return; }
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar.close()); return; }
            }
            return;
        }
        
        // Count consecutive same-direction bars
        int consUp = 0, consDown = 0;
        for (int i = history.size() - 2; i >= Math.max(0, history.size() - 6); i--) {
            Bar b = history.get(i);
            if (b.close() > b.open()) { consUp++; consDown = 0; }
            else if (b.close() < b.open()) { consDown++; consUp = 0; }
            else break;
        }
        
        // Check for exhaustion after 3+ consecutive bars
        double body = Math.abs(bar.close() - bar.open());
        double upperWick = bar.high() - Math.max(bar.close(), bar.open());
        double lowerWick = Math.min(bar.close(), bar.open()) - bar.low();
        
        if (consUp >= 3 && upperWick > body * 1.2 && bar.close() < bar.high() - body * 0.3) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); barsHeld = 0;
        } else if (consDown >= 3 && lowerWick > body * 1.2 && bar.close() > bar.low() + body * 0.3) {
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); barsHeld = 0;
        }
    }
    
    private void closePosition(double price) {
        pending.add(new Order(symbol, tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY, Order.Type.MARKET, positionSize, price));
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
        history.clear(); pending.clear(); inTrade = false; tradeDirection = Order.Side.BUY; entryPrice = 0; barsHeld = 0;
    }
}
