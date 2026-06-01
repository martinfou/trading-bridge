package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.*;
import java.util.*;

/**
 * Opening Range Breakout Strategy — Breakout + Time-based
 *
 * 📊 Data insight: The first few hours of each trading session establish
 *    a "true range" for the rest of the session. When price breaks out
 *    of this opening range with momentum, it tends to continue in that
 *    direction with an RR of 2:1 achievable before the session close.
 *
 * 🔧 Mechanism: London session opening range breakout.
 *    - Capture first 3 bars (3 hours) of London session (8-10 UTC+2)
 *      as the "opening range" (high = ORH, low = ORL)
 *    - Breakout above ORH with bullish close → BUY
 *    - Breakdown below ORL with bearish close → SELL
 *    - Hold for up to 5 bars or ATR target
 *
 * 🎯 Originality: Session-specific opening range breakout adapted for H1.
 *    Unlike day-level ORB, this uses the H1 session start dynamics
 *    which work consistently across FX pairs due to institutional
 *    flow patterns at session starts.
 */
public class OpeningRangeBreakoutStrategy implements Strategy {
    private static final ZoneOffset TZ = ZoneOffset.ofHours(2);

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 1000;

    // Opening range state
    private boolean collectingRange = false;
    private double orHigh = 0;
    private double orLow = Double.MAX_VALUE;
    private int rangeBarsCollected = 0;
    private int sessionDay = -1;

    public OpeningRangeBreakoutStrategy() {
        this("OpeningRangeBreakout", "GBP/JPY");
    }

    public OpeningRangeBreakoutStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public OpeningRangeBreakoutStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 10) return;

        OffsetDateTime odt = OffsetDateTime.ofInstant(bar.timestamp(), TZ);
        int hour = odt.getHour();
        int day = odt.getDayOfYear();

        // New day: reset range collection
        if (day != sessionDay) {
            collectingRange = false;
            orHigh = 0;
            orLow = Double.MAX_VALUE;
            rangeBarsCollected = 0;
            sessionDay = day;
        }

        double atr = calculateATR(14);

        // Manage active trade
        if (inTrade) {
            barsHeld++;

            if (barsHeld >= 5 || hour < 8 || hour > 20) {
                closePosition(bar); return;
            }

            if (tradeDirection == Order.Side.BUY) {
                if (bar.high() >= entryPrice + atr * 2.0) { closePosition(bar); return; }
                if (bar.low() <= entryPrice - atr * 1.0) { closePosition(bar); return; }
            } else {
                if (bar.low() <= entryPrice - atr * 2.0) { closePosition(bar); return; }
                if (bar.high() >= entryPrice + atr * 1.0) { closePosition(bar); return; }
            }
            return;
        }

        // Collect opening range during London open (hours 8-10 UTC+2)
        if (hour >= 8 && hour <= 10) {
            collectingRange = true;
            if (bar.high() > orHigh) orHigh = bar.high();
            if (bar.low() < orLow) orLow = bar.low();
            rangeBarsCollected++;
        }

        // After opening range is collected (hour 11+), check for breakouts
        if (collectingRange && hour > 10 && rangeBarsCollected >= 2) {
            collectingRange = false; // Only trigger once per day

            // Range must have meaningful width
            double rangeWidth = orHigh - orLow;
            if (rangeWidth < atr * 0.5) return;

            // Check for breakout at the first bar after collecting
            if (bar.high() > orHigh && bar.close() > bar.open()) {
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.BUY; entryPrice = bar.close(); barsHeld = 0;
            } else if (bar.low() < orLow && bar.close() < bar.open()) {
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                inTrade = true; tradeDirection = Order.Side.SELL; entryPrice = bar.close(); barsHeld = 0;
            }
        }
    }

    private void closePosition(Bar bar) {
        Order.Side closeSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, positionSize, bar.close()).closeOnly());
        inTrade = false;
    }

    private double calculateATR(int period) {
        int size = history.size();
        double sum = 0; int count = 0;
        for (int i = Math.max(1, size - period); i < size; i++) {
            Bar prev = history.get(i - 1); Bar curr = history.get(i);
            double tr = Math.max(curr.high() - curr.low(),
                Math.max(Math.abs(curr.high() - prev.close()), Math.abs(curr.low() - prev.close())));
            sum += tr; count++;
        }
        return count > 0 ? sum / count : 1.0;
    }

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending); pending.clear(); return copy;
    }
    @Override public void reset() {
        history.clear(); pending.clear(); inTrade = false;
        tradeDirection = Order.Side.BUY; entryPrice = 0; barsHeld = 0;
        collectingRange = false; orHigh = 0; orLow = Double.MAX_VALUE;
        rangeBarsCollected = 0; sessionDay = -1;
    }
}
