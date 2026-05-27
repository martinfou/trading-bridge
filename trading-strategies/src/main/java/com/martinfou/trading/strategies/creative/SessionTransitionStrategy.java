package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class SessionTransitionStrategy implements Strategy {
    private static final ZoneOffset TZ = ZoneOffset.ofHours(2);
    private static final String SYMBOL = "GBP/JPY";

    private final String name;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private final List<Double> atrValues = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private int barsRemaining = 0;
    private double entryPrice = 0;
    private double atr = 0;
    private double positionSize = 10000;

    public SessionTransitionStrategy() {
        this.name = "SessionTransition_GBPJPY";
    }

    public SessionTransitionStrategy(String name) {
        this.name = name;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 15) return;

        atr = calculateATR(14);
        atrValues.add(atr);

        OffsetDateTime odt = OffsetDateTime.ofInstant(bar.timestamp(), TZ);
        int hour = odt.getHour();

        // Manage active trade
        if (inTrade) {
            barsRemaining--;
            if (barsRemaining <= 0) {
                closePosition(bar);
                return;
            }
            // Take profit
            if (bar.close() >= entryPrice + atr * 2.0) {
                closePosition(bar);
                return;
            }
            // Stop loss
            if (bar.close() <= entryPrice - atr * 1.5) {
                closePosition(bar);
                return;
            }
        }

        // Check entry conditions at session starts
        if (!inTrade && (hour == 9 || hour == 16)) {
            evaluateSessionEntry(bar, hour);
        }
    }

    private void evaluateSessionEntry(Bar bar, int hour) {
        // We need at least 1 previous bar
        if (history.size() < 2) return;

        Bar prev = history.get(history.size() - 2);
        double range = bar.high() - bar.low();
        double avgRange = calculateAverageRange(20);
        double body = bar.close() - bar.open();

        // Condition: bar has above-average range AND directionality
        boolean bigRange = range > avgRange * 1.2;
        boolean strongBull = body > 0 && bar.close() > bar.open() + (range * 0.6);
        boolean strongBear = body < 0 && bar.close() < bar.open() - (range * 0.6);

        if (bigRange && (strongBull || strongBear)) {
            Order.Side side = strongBull ? Order.Side.BUY : Order.Side.SELL;
            entryPrice = bar.close();
            double qty = positionSize;

            Order order = new Order(SYMBOL, side, Order.Type.MARKET, qty, bar.close());
            pending.add(order);
            inTrade = true;
            tradeDirection = side;
            barsRemaining = 2; // Hold for 2 more bars
        }
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        Order closeOrder = new Order(SYMBOL, closeSide, Order.Type.MARKET, positionSize, bar.close());
        pending.add(closeOrder);
        inTrade = false;
        barsRemaining = 0;
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
        atrValues.clear();
        inTrade = false;
        tradeDirection = Order.Side.BUY;
        barsRemaining = 0;
        entryPrice = 0;
        atr = 0;
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

    /**
     * Overrides position size (default 10000).
     */
    public void withPositionSize(double size) {
        this.positionSize = size;
    }
}
