package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

public class WickReversalStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 1000;

    public WickReversalStrategy() { this("WickReversal", "GBP/JPY"); }
    public WickReversalStrategy(String name) { this(name, "GBP/JPY"); }
    public WickReversalStrategy(String name, String symbol) { this.name = name; this.symbol = symbol; }
    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 20) return;
        double atr = calculateATR(14);

        if (inTrade) {
            barsHeld++;
            if (barsHeld >= 6) { closePosition(bar.close()); return; }

            // Stop-loss and take-profit management
            if (tradeDirection == Order.Side.BUY) {
                // SL: entry - (entry - low) * 1.2  (hammer)
                double sl = entryPrice - (entryPrice - bar.low()) * 1.2;
                double tp = entryPrice + atr * 1.5;
                if (bar.low() <= sl) { closePosition(bar.close()); return; }
                if (bar.high() >= tp) { closePosition(bar.close()); return; }
            } else {
                // SL: entry + (high - entry) * 1.2  (shooting star)
                double sl = entryPrice + (bar.high() - entryPrice) * 1.2;
                double tp = entryPrice - atr * 1.5;
                if (bar.high() >= sl) { closePosition(bar.close()); return; }
                if (bar.low() <= tp) { closePosition(bar.close()); return; }
            }
            return;
        }

        double range = bar.high() - bar.low();
        double body = Math.abs(bar.close() - bar.open());
        if (body <= 0 || range == 0) return;
        double upperWick = bar.high() - Math.max(bar.close(), bar.open());
        double lowerWick = Math.min(bar.close(), bar.open()) - bar.low();

        // Shooting Star: long upper wick > 1.5*body, close in lower 30% of range, body > 0
        if (upperWick > body * 1.5 && bar.close() <= bar.low() + range * 0.3) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); barsHeld = 0;
        }
        // Hammer: long lower wick > 1.5*body, close in upper 30% of range, body > 0
        else if (lowerWick > body * 1.5 && bar.close() >= bar.low() + range * 0.7) {
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
            inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); barsHeld = 0;
        }
    }

    private void closePosition(double price) {
        pending.add(new Order(symbol, tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY, Order.Type.MARKET, positionSize, price));
        inTrade = false;
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

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }
    @Override public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        tradeDirection = Order.Side.BUY;
        entryPrice = 0;
        barsHeld = 0;
    }
}
