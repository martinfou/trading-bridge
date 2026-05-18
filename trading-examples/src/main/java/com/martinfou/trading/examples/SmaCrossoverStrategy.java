package com.martinfou.trading.examples;

import com.martinfou.trading.core.*;
import java.util.*;

public class SmaCrossoverStrategy implements Strategy {
    private final String name;
    private final int fastPeriod, slowPeriod;
    private final String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();
    private boolean hasPosition = false;

    public SmaCrossoverStrategy(String name, String symbol, int fastPeriod, int slowPeriod) {
        this.name = name;
        this.symbol = symbol;
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < slowPeriod + 1) return;

        double fastSma = calculateSMA(fastPeriod);
        double slowSma = calculateSMA(slowPeriod);
        double prevFast = calculatePrevSMA(fastPeriod);
        double prevSlow = calculatePrevSMA(slowPeriod);

        // Golden cross: fast crosses ABOVE slow
        if (prevFast <= prevSlow && fastSma > slowSma && !hasPosition) {
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, 1000, bar.close()));
            hasPosition = true;
        }
        // Death cross: fast crosses BELOW slow
        else if (prevFast >= prevSlow && fastSma < slowSma && hasPosition) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, 1000, bar.close()));
            hasPosition = false;
        }
    }

    @Override public void onTick(double bid, double ask, long volume) {}

    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    @Override public void reset() { history.clear(); pending.clear(); hasPosition = false; }

    private double calculateSMA(int period) {
        int size = history.size();
        double sum = 0;
        for (int i = size - period; i < size; i++) sum += history.get(i).close();
        return sum / period;
    }

    private double calculatePrevSMA(int period) {
        int size = history.size() - 1;
        double sum = 0;
        for (int i = size - period; i < size; i++) sum += history.get(i).close();
        return sum / period;
    }
}
