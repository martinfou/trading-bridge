package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class NYMidSessionMomentumStrategy implements Strategy {
    private static final ZoneOffset TZ = ZoneOffset.ofHours(2);
    private static final String SYMBOL = "GBP/JPY";

    private final String name;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private int maxBars = 2;
    private double positionSize = 1000;

    public NYMidSessionMomentumStrategy() {
        this.name = "NYMidSessionMomentum_GBPJPY";
    }

    public NYMidSessionMomentumStrategy(String name) {
        this.name = name;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 15) return;

        OffsetDateTime odt = OffsetDateTime.ofInstant(bar.timestamp(), TZ);
        int hour = odt.getHour();

        // Manage active trade
        if (inTrade) {
            barsHeld++;

            // Exit after max bars or if hour is past 20
            if (barsHeld >= maxBars || hour > 20) {
                closePosition(bar);
                return;
            }

            double atr = calculateATR(14);

            // Take profit at 1.0x ATR
            if (tradeDirection == Order.Side.BUY &&
                bar.high() >= entryPrice + atr * 1.0) {
                closePosition(bar);
                return;
            }

            // Stop loss at 0.7x ATR (tight for this strategy)
            if (tradeDirection == Order.Side.BUY &&
                bar.low() <= entryPrice - atr * 0.7) {
                closePosition(bar);
                return;
            }

            return;
        }

        // Entry: at hour 18 (first bar of NY mid-session window)
        // Only look for entry on the first bar of the session (hour 18)
        if (!inTrade && hour == 18) {
            // Momentum entry: if bar is bullish (close > open), go long
            if (bar.close() > bar.open()) {
                // Stronger signal: bar also has above-average body
                double avgRange = calculateAverageRange(20);
                double body = Math.abs(bar.close() - bar.open());

                if (body > avgRange * 0.4) {
                    pending.add(new Order(SYMBOL, Order.Side.BUY, Order.Type.MARKET,
                        positionSize, bar.close()));
                    inTrade = true;
                    tradeDirection = Order.Side.BUY;
                    entryPrice = bar.close();
                    barsHeld = 0;
                }
            }
        }
    }

    private void closePosition(Bar bar) {
        pending.add(new Order(SYMBOL, Order.Side.SELL, Order.Type.MARKET,
            positionSize, bar.close()));
        inTrade = false;
    }

    @Override
    public void onTick(double bid, double ask, long volume) {}

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

    private double calculateAverageRange(int period) {
        int size = history.size();
        double sum = 0;
        int count = 0;
        for (int i = Math.max(0, size - period); i < size; i++) {
            sum += (history.get(i).high() - history.get(i).low());
            count++;
        }
        return count > 0 ? sum / count : 1.0;
    }
}
