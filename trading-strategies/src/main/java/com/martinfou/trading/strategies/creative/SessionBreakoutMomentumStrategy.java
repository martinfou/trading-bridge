package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.*;
import java.util.*;

public class SessionBreakoutMomentumStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 1000;
    private static final ZoneOffset TZ = ZoneOffset.ofHours(2);
    private int sessionDay = -1;
    private boolean collectingRange = false;
    private double orHigh = 0;
    private double orLow = Double.MAX_VALUE;
    private int rangeBarsCollected = 0;
    private int barsAfterLondonOpen = 0;
    
    public SessionBreakoutMomentumStrategy() { this("SessionBreakoutMomentum", "GBP/JPY"); }
    public SessionBreakoutMomentumStrategy(String name) { this(name, "GBP/JPY"); }
    public SessionBreakoutMomentumStrategy(String name, String symbol) { this.name = name; this.symbol = symbol; }
    @Override public String name() { return name; }
    
    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 25) return;
        double atr = calculateATR(14);
        OffsetDateTime odt = OffsetDateTime.ofInstant(bar.timestamp(), TZ);
        int hour = odt.getHour();
        int day = odt.getDayOfYear();
        if (day != sessionDay) { sessionDay = day; collectingRange = false; orHigh = 0; orLow = Double.MAX_VALUE; rangeBarsCollected = 0; barsAfterLondonOpen = 0; }
        
        // Manage active trade
        if (inTrade) {
            barsHeld++;
            if (barsHeld >= 10) { closePosition(bar.close()); return; }
            if (tradeDirection == Order.Side.BUY) {
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar.close()); return; }
                if (bar.high() >= entryPrice + atr * 2.0) { closePosition(bar.close()); return; }
            } else {
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar.close()); return; }
                if (bar.low() <= entryPrice - atr * 2.0) { closePosition(bar.close()); return; }
            }
            return;
        }
        
        // Collect OR during London open (8-10 UTC+2)
        if (hour >= 8 && hour <= 10) {
            collectingRange = true;
            if (bar.high() > orHigh) orHigh = bar.high();
            if (bar.low() < orLow) orLow = bar.low();
            rangeBarsCollected++;
        }
        
        // After OR collected, look for breakout
        if (collectingRange && hour > 10 && rangeBarsCollected >= 2) {
            collectingRange = false;
            double rangeWidth = orHigh - orLow;
            if (rangeWidth < atr * 0.3) return;
            if (bar.high() > orHigh && bar.close() > bar.open() && (bar.high() - bar.low()) > atr * 0.6) {
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); barsHeld = 0;
            } else if (bar.low() < orLow && bar.close() < bar.open() && (bar.high() - bar.low()) > atr * 0.6) {
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); barsHeld = 0;
            }
        }
        
        // Track bars after London open - skip if no breakout within 10 bars
        if (hour >= 8 && !collectingRange && !inTrade) barsAfterLondonOpen++;
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
        sessionDay = -1; collectingRange = false; orHigh = 0; orLow = Double.MAX_VALUE; rangeBarsCollected = 0; barsAfterLondonOpen = 0;
    }
}
