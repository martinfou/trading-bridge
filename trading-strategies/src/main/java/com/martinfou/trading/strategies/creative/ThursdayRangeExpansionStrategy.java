package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.*;
import java.util.*;

/**
 * Thursday Range Expansion Strategy — Custom Indicator + Pattern Recognition
 *
 * 📊 Data insight: Thursday has the highest average range of any weekday
 *    (0.2136%), 1.44x the overall average (0.1481%). The range std is
 *    also highest (0.1681). This makes Thursday the most volatile day
 *    for GBP/JPY, driven by a combination of UK data releases, US
 *    jobless claims, and end-of-week positioning.
 *
 * 🔧 Mechanism: Adaptive breakout with Thursday-specific parameters.
 *    - On Thursday, widen ATR threshold by 1.4x (to match the higher
 *      typical range)
 *    - Enter on breakout of the Wednesday high/low range
 *    - Wider stops (2x ATR) and wider targets (3x ATR) to accommodate
 *      the elevated volatility
 *    - On other days, no trades
 *
 * 🎯 Originality: Day-of-week volatility regime adaptation. Most
 *    strategies use the same parameters every day; this adjusts
 *    thresholds based on the statistical volatility profile of
 *    the specific day for GBP/JPY.
 */
public class ThursdayRangeExpansionStrategy implements Strategy {
    private static final ZoneOffset TZ_OFFSET = ZoneOffset.ofHours(2);
    private static final String SYMBOL = "GBP/JPY";

    private final String name;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int tradeBarsHeld = 0;
    private int maxTradeBars = 5;
    private double positionSize = 1000;

    public ThursdayRangeExpansionStrategy() {
        this.name = "ThuRangeExpansion_GBPJPY";
    }

    public ThursdayRangeExpansionStrategy(String name) {
        this.name = name;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 50) return; // Need enough history for Wednesday range

        OffsetDateTime odt = OffsetDateTime.ofInstant(bar.timestamp(), TZ_OFFSET);
        DayOfWeek dow = odt.getDayOfWeek();

        // Only trade on Thursday
        if (dow != DayOfWeek.THURSDAY) {
            if (inTrade) {
                closePosition(bar);
            }
            return;
        }

        double atr = calculateATR(14);
        double atrScaled = atr * 1.4; // Thursday factor: 1.44x avg range
        int hour = odt.getHour();

        // Manage active trade
        if (inTrade) {
            tradeBarsHeld++;

            if (tradeBarsHeld >= maxTradeBars) {
                closePosition(bar);
                return;
            }

            // Wider targets for Thursday
            if (tradeDirection == Order.Side.BUY &&
                bar.high() >= entryPrice + atrScaled * 3.0) {
                closePosition(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL &&
                bar.low() <= entryPrice - atrScaled * 3.0) {
                closePosition(bar);
                return;
            }

            // Wider stops
            if (tradeDirection == Order.Side.BUY &&
                bar.low() <= entryPrice - atrScaled * 2.0) {
                closePosition(bar);
                return;
            }
            if (tradeDirection == Order.Side.SELL &&
                bar.high() >= entryPrice + atrScaled * 2.0) {
                closePosition(bar);
                return;
            }

            return;
        }

        // Entry: during London/NY session hours (8-20 UTC+2)
        if (hour >= 8 && hour <= 16) {
            // Find Wednesday's high and low
            double wedHigh = findDayHighLow(DayOfWeek.WEDNESDAY, true);
            double wedLow = findDayHighLow(DayOfWeek.WEDNESDAY, false);

            if (wedHigh == 0 || wedLow == 0) return;

            double range = wedHigh - wedLow;

            // Only trade if Wednesday had meaningful range
            if (range < 0.5) return;

            // Breakout above Wednesday high
            if (bar.high() > wedHigh && bar.close() > bar.open() && !inTrade) {
                pending.add(new Order(SYMBOL, Order.Side.BUY, Order.Type.MARKET,
                    positionSize, bar.close()));
                inTrade = true;
                tradeDirection = Order.Side.BUY;
                entryPrice = bar.close();
                tradeBarsHeld = 0;
            }
            // Breakdown below Wednesday low
            else if (bar.low() < wedLow && bar.close() < bar.open() && !inTrade) {
                pending.add(new Order(SYMBOL, Order.Side.SELL, Order.Type.MARKET,
                    positionSize, bar.close()));
                inTrade = true;
                tradeDirection = Order.Side.SELL;
                entryPrice = bar.close();
                tradeBarsHeld = 0;
            }
        }
    }

    private double findDayHighLow(DayOfWeek targetDay, boolean wantHigh) {
        double best = wantHigh ? Double.MIN_VALUE : Double.MAX_VALUE;
        boolean found = false;

        // Search backwards through history for the last occurrence of targetDay
        for (int i = history.size() - 1; i >= 0; i--) {
            Bar b = history.get(i);
            OffsetDateTime bt = OffsetDateTime.ofInstant(b.timestamp(), TZ_OFFSET);

            // Stop if we go back more than 3 days
            if (bt.getDayOfWeek() == targetDay) {
                if (wantHigh) {
                    if (b.high() > best) best = b.high();
                } else {
                    if (b.low() < best) best = b.low();
                }
                found = true;
            } else if (found && bt.getDayOfWeek() != targetDay) {
                // We've passed through the target day
                break;
            }
        }

        return found ? best : (wantHigh ? Double.MAX_VALUE : 0);
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
}
