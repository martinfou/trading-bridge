package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class LondonOpenVolStrategy implements Strategy {
    private static final ZoneOffset TZ = ZoneOffset.ofHours(2);
    private static final String SYMBOL = "GBP/JPY";

    private final String name;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int tradeBarsHeld = 0;
    private int maxTradeBars = 3;
    private double positionSize = 1000;

    public LondonOpenVolStrategy() {
        this.name = "LondonOpenVol_GBPJPY";
    }

    public LondonOpenVolStrategy(String name) {
        this.name = name;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 20) return;

        OffsetDateTime odt = OffsetDateTime.ofInstant(bar.timestamp(), TZ);
        int hour = odt.getHour();

        // Manage active trade
        if (inTrade) {
            tradeBarsHeld++;
            double atr = calculateATR(14);

            if (tradeBarsHeld >= maxTradeBars) {
                closePosition(bar);
                return;
            }

            // Wider take profit for London high-vol: 2.5x ATR
            if (tradeDirection == Order.Side.BUY &&
                bar.high() >= entryPrice + atr * 2.5) {
                closePosition(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL &&
                bar.low() <= entryPrice - atr * 2.5) {
                closePosition(bar);
                return;
            }

            // Stop loss: 1.2x ATR (wider due to higher vol)
            if (tradeDirection == Order.Side.BUY &&
                bar.low() <= entryPrice - atr * 1.2) {
                closePosition(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL &&
                bar.high() >= entryPrice + atr * 1.2) {
                closePosition(bar);
                return;
            }

            return;
        }

        // Only check entry during London open (hour 9 UTC+2)
        if (hour != 9) return;

        if (history.size() < 2) return;
        Bar prev = history.get(history.size() - 2);

        // Breakout: bar breaks above prev bar's high → long
        if (bar.high() > prev.high()) {
            double avgRange = calculateAverageRange(20);
            double range = bar.high() - bar.low();

            // Strong breakout: range is above average
            if (range > avgRange * 1.1 && bar.close() > bar.open()) {
                pending.add(new Order(SYMBOL, Order.Side.BUY, Order.Type.MARKET,
                    positionSize, bar.close()));
                inTrade = true;
                tradeDirection = Order.Side.BUY;
                entryPrice = bar.close();
                tradeBarsHeld = 0;
            }
        }
        // Breakout: bar breaks below prev bar's low → short
        else if (bar.low() < prev.low()) {
            double avgRange = calculateAverageRange(20);
            double range = bar.high() - bar.low();

            if (range > avgRange * 1.1 && bar.close() < bar.open()) {
                pending.add(new Order(SYMBOL, Order.Side.SELL, Order.Type.MARKET,
                    positionSize, bar.close()));
                inTrade = true;
                tradeDirection = Order.Side.SELL;
                entryPrice = bar.close();
                tradeBarsHeld = 0;
            }
        }
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(SYMBOL, closeSide, Order.Type.MARKET, positionSize, bar.close()).closeOnly());
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
        tradeBarsHeld = 0;
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
