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
    private double entryStop = 0;
    private double entryTarget = 0;
    private double positionSize = 1000;
    private int cooldownBars = 0;
    private int tradesToday = 0;
    private int lastTradeDay = -1;
    private static final int COOLDOWN_BARS = 8;
    private static final int MAX_TRADES_PER_DAY = 3;

    public ConsecutiveBarExhaustionStrategy() { this("🔥 Consec Bar Exhaust", "GBP/JPY"); }
    public ConsecutiveBarExhaustionStrategy(String name) { this(name, "GBP/JPY"); }
    public ConsecutiveBarExhaustionStrategy(String name, String symbol) { this.name = name; this.symbol = symbol; }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 20) return;

        // Daily trade counter
        int barDay = bar.timestamp().atZone(java.time.ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        double atr = calculateATR(14);
        if (atr <= 0) return;

        if (inTrade) {
            barsHeld++;
            // Stop loss
            if (tradeDirection == Order.Side.BUY && bar.low() <= entryStop) { closePosition(entryStop); return; }
            if (tradeDirection == Order.Side.SELL && bar.high() >= entryStop) { closePosition(entryStop); return; }
            // Take profit
            if (tradeDirection == Order.Side.BUY && bar.high() >= entryTarget) { closePosition(entryTarget); return; }
            if (tradeDirection == Order.Side.SELL && bar.low() <= entryTarget) { closePosition(entryTarget); return; }
            // Max hold
            if (barsHeld >= 6) { closePosition(bar.close()); return; }
            return;
        }

        // Cooldown
        if (cooldownBars > 0) { cooldownBars--; return; }
        if (tradesToday >= MAX_TRADES_PER_DAY) return;

        int consUp = 0, consDown = 0;
        for (int i = history.size() - 2; i >= Math.max(0, history.size() - 6); i--) {
            Bar b = history.get(i);
            if (b.close() > b.open()) { consUp++; consDown = 0; }
            else if (b.close() < b.open()) { consDown++; consUp = 0; }
            else break;
        }

        double body = Math.abs(bar.close() - bar.open());
        double upperWick = bar.high() - Math.max(bar.close(), bar.open());
        double lowerWick = Math.min(bar.close(), bar.open()) - bar.low();

        if (consUp >= 3 && upperWick > body * 1.2 && bar.close() < bar.high() - body * 0.3) {
            entryPrice = bar.close(); entryStop = entryPrice + atr * 1.0; entryTarget = entryPrice - atr * 1.5;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(entryStop).withTakeProfit(entryTarget));
            inTrade = true; tradeDirection = Order.Side.SELL; barsHeld = 0; cooldownBars = 0; tradesToday++;
        } else if (consDown >= 3 && lowerWick > body * 1.2 && bar.close() > bar.low() + body * 0.3) {
            entryPrice = bar.close(); entryStop = entryPrice - atr * 1.0; entryTarget = entryPrice + atr * 1.5;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(entryStop).withTakeProfit(entryTarget));
            inTrade = true; tradeDirection = Order.Side.BUY; barsHeld = 0; cooldownBars = 0; tradesToday++;
        }
    }

    private void closePosition(double price) {
        pending.add(new Order(symbol, tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY, Order.Type.MARKET, positionSize, price));
        inTrade = false; cooldownBars = COOLDOWN_BARS;
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
        history.clear(); pending.clear(); inTrade = false; tradeDirection = Order.Side.BUY;
        entryPrice = 0; barsHeld = 0; entryStop = 0; entryTarget = 0;
        cooldownBars = 0; tradesToday = 0; lastTradeDay = -1;
    }

    /** Restore runtime state after crash recovery — keeps history/pending intact. */
    public void restoreState(int tradesToday, int lastTradeDay, boolean inTrade,
                             Order.Side tradeDirection, int cooldownBars) {
        this.tradesToday = tradesToday;
        this.lastTradeDay = lastTradeDay;
        this.inTrade = inTrade;
        this.tradeDirection = tradeDirection;
        this.cooldownBars = cooldownBars;
    }

    public int getTradesToday() { return tradesToday; }
    public int getLastTradeDay() { return lastTradeDay; }
    public boolean isInTrade() { return inTrade; }
    public Order.Side getTradeDirection() { return tradeDirection; }
    public int getCooldownBars() { return cooldownBars; }
}
