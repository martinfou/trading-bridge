package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class FridayBearStrategy implements Strategy {
    private static final ZoneOffset TZ = ZoneOffset.ofHours(2); // UTC+2
    private static final String SYMBOL = "GBP/JPY";

    private final String name;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private boolean hasPosition = false;
    private double entryPrice = 0;
    private final double positionSize = 10000; // units of base currency

    public FridayBearStrategy() {
        this.name = "FridayBear_GBPJPY";
    }

    public FridayBearStrategy(String name) {
        this.name = name;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        OffsetDateTime odt = OffsetDateTime.ofInstant(bar.timestamp(), TZ);
        DayOfWeek dow = odt.getDayOfWeek();
        int hour = odt.getHour();

        // Only trade Fridays
        if (dow != DayOfWeek.FRIDAY) {
            // Close any remaining position at end of Thursday (or Monday open)
            if (hasPosition) {
                pending.add(new Order(SYMBOL, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                hasPosition = false;
            }
            return;
        }

        // Enter short on Friday during London/NY session (8-20 UTC+2)
        if (!hasPosition && hour >= 8 && hour <= 19 && hour % 4 == 0) {
            Order sell = new Order(SYMBOL, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close());
            // Set stop loss above recent high
            double recentHigh = getHighestHigh(12); // 12-bar lookback
            if (recentHigh > bar.close()) {
                sell.withStopLoss(recentHigh + 0.5); // 50 pips buffer above high
            }
            pending.add(sell);
            hasPosition = true;
            entryPrice = bar.close();
        }

        // Exit all shorts before Friday 21:00 UTC+2
        if (hasPosition && hour >= 20) {
            pending.add(new Order(SYMBOL, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
            hasPosition = false;
        }

        // Take profit: exit at +1.5x ATR gain
        if (hasPosition && history.size() >= 20) {
            double atr = calculateATR(14);
            double target = entryPrice - atr * 1.5;
            if (bar.close() <= target) {
                pending.add(new Order(SYMBOL, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                hasPosition = false;
            }
        }
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
        hasPosition = false;
        entryPrice = 0;
    }

    private double getHighestHigh(int periods) {
        int size = history.size();
        double max = 0;
        for (int i = Math.max(0, size - periods); i < size; i++) {
            if (history.get(i).high() > max) max = history.get(i).high();
        }
        return max;
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
}
