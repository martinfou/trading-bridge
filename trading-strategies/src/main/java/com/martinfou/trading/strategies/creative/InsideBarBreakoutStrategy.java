package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

public class InsideBarBreakoutStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 1000;
    private boolean wasInsideBar = false;
    private double insideBarHigh = 0;
    private double insideBarLow = 0;

    public InsideBarBreakoutStrategy() {
        this("InsideBarBreakout", "GBP/JPY");
    }

    public InsideBarBreakoutStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public InsideBarBreakoutStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 25) return;

        double atr = calculateATR(14);
        double sma20 = calculateSMA(20);

        if (inTrade) {
            barsHeld++;
            if (barsHeld >= 10) {
                closePosition(bar.close());
                return;
            }
            if (tradeDirection == Order.Side.BUY) {
                if (bar.low() <= entryPrice - atr * 1.0) {
                    closePosition(bar.close());
                    return;
                }
                if (bar.high() >= entryPrice + atr * 2.0) {
                    closePosition(bar.close());
                    return;
                }
            } else {
                if (bar.high() >= entryPrice + atr * 1.0) {
                    closePosition(bar.close());
                    return;
                }
                if (bar.low() <= entryPrice - atr * 2.0) {
                    closePosition(bar.close());
                    return;
                }
            }
            return;
        }

        // Check for breakout after inside bar
        if (wasInsideBar) {
            wasInsideBar = false;
            if (bar.high() > insideBarHigh && bar.close() > bar.open() && bar.close() > sma20) {
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true;
                tradeDirection = Order.Side.BUY;
                entryPrice = bar.close();
                barsHeld = 0;
                insideBarHigh = 0;
                insideBarLow = 0;
                return;
            } else if (bar.low() < insideBarLow && bar.close() < bar.open() && bar.close() < sma20) {
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true;
                tradeDirection = Order.Side.SELL;
                entryPrice = bar.close();
                barsHeld = 0;
                insideBarHigh = 0;
                insideBarLow = 0;
                return;
            }
            insideBarHigh = 0;
            insideBarLow = 0;
        }

        // Detect inside bar (need at least 2 bars in history)
        if (history.size() >= 2) {
            Bar prev = history.get(history.size() - 2);
            if (bar.high() <= prev.high() && bar.low() >= prev.low()) {
                wasInsideBar = true;
                insideBarHigh = prev.high();
                insideBarLow = prev.low();
            }
        }
    }

    private void closePosition(double price) {
        pending.add(new Order(symbol,
                tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
                Order.Type.MARKET, positionSize, price));
        inTrade = false;
    }

    private double calculateSMA(int period) {
        int size = history.size();
        double sum = 0;
        int count = 0;
        for (int i = Math.max(0, size - period); i < size; i++) {
            sum += history.get(i).close();
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    private double calculateATR(int period) {
        int size = history.size();
        double sum = 0;
        int count = 0;
        for (int i = Math.max(1, size - period); i < size; i++) {
            Bar prev = history.get(i - 1);
            Bar curr = history.get(i);
            double tr = Math.max(curr.high() - curr.low(),
                    Math.max(Math.abs(curr.high() - prev.close()),
                            Math.abs(curr.low() - prev.close())));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 1.0;
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
    }

    @Override
    public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        tradeDirection = Order.Side.BUY;
        entryPrice = 0;
        barsHeld = 0;
        wasInsideBar = false;
        insideBarHigh = 0;
        insideBarLow = 0;
    }
}
