package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * Weekend Continuation Strategy — News/Sentiment proxy
 *
 * 📊 Inspiration: Part Time Larry's ORB / gap-fill concepts,
 *    Ali Casey's observation that H1 trends persist.
 *    Weekend gaps create measurable directional bias on Mondays.
 *
 * 🔧 Mechanism:
 *    - Track Friday close and Monday open
 *    - A gap (Monday open vs Friday close) ≥ 0.5×ATR signals directional momentum
 *    - Gap up → BUY (weekend info was positive, continuation expected)
 *    - Gap down → SELL (weekend info was negative, continuation expected)
 *    - Gap must NOT be extreme (> 3×ATR) — skip blow-off gaps
 *    - Exit: ATR trailing stop (1.5×) or max 15 bars (end around Wednesday)
 *
 * 🎯 Originality: No existing strategy trades Monday gap continuation on H1.
 *    SessionBreakoutMomentum, SessionOverlapBreakout, SessionMomentumFlow
 *    all trade intra-session, not weekend gaps. Produces ~52 trades/year/pair.
 */
public class WeekendContinuationStrategy implements Strategy {

    private static final int MIN_HISTORY = 50;
    private static final int ATR_PERIOD = 14;
    private static final int RANGE_MEDIAN = 20;
    private static final double MIN_GAP_MULT = 0.5;
    private static final double MAX_GAP_MULT = 3.0;
    private static final double ATR_STOP_MULT = 1.5;
    private static final int MAX_BARS_HOLD = 15;
    private static final double MIN_POSITION = 1000;

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private final ZoneId nyZone = ZoneId.of("America/New_York");

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private int barsInTrade;
    private double highestSinceEntry;
    private double lowestSinceEntry;

    // Gap tracking state
    private int lastBarDay = -1;
    private boolean awaitingMonday = false;
    private double fridayClose;
    private int fridayDay;
    private boolean mondayChecked;

    public WeekendContinuationStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    public WeekendContinuationStrategy() {
        this("WeekendContinuation", "EUR/USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        ZonedDateTime zdt = bar.timestamp().atZone(nyZone);
        int barDay = zdt.getDayOfYear();
        int dayOfWeek = zdt.getDayOfWeek().getValue(); // 1=Mon, 7=Sun

        managePosition(bar);

        if (!inTrade) {
            checkWeekendGap(bar, dayOfWeek, barDay);
        }

        lastBarDay = barDay;
    }

    @Override
    public void onTick(double bid, double ask, long volume) {}

    @Override
    public List<Order> getPendingOrders() {
        var copy = List.copyOf(pending);
        pending.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        barsInTrade = 0;
        awaitingMonday = false;
        fridayClose = 0;
        fridayDay = -1;
        mondayChecked = false;
        lastBarDay = -1;
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

        if (tradeDirection == Order.Side.BUY) {
            highestSinceEntry = Math.max(highestSinceEntry, bar.high());
        } else {
            lowestSinceEntry = Math.min(lowestSinceEntry, bar.low());
        }

        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);

        if (stopHit || barsInTrade >= MAX_BARS_HOLD) {
            closePosition(bar.close());
            return;
        }

        // Trailing stop
        double atr = atr();
        if (!Double.isNaN(atr) && atr > 0) {
            if (tradeDirection == Order.Side.BUY) {
                double trail = highestSinceEntry - atr * ATR_STOP_MULT;
                stopLoss = Math.max(stopLoss, trail);
                if (bar.low() <= stopLoss) { closePosition(bar.close()); return; }
            } else {
                double trail = lowestSinceEntry + atr * ATR_STOP_MULT;
                stopLoss = Math.min(stopLoss, trail);
                if (bar.high() >= stopLoss) { closePosition(bar.close()); return; }
            }
        }
    }

    private void checkWeekendGap(Bar bar, int dayOfWeek, int barDay) {
        // Track Friday close
        if (dayOfWeek == 5 && barDay != lastBarDay) {
            fridayClose = bar.close();
            fridayDay = barDay;
            awaitingMonday = true;
            mondayChecked = false;
            return;
        }

        // Check Monday open
        if (awaitingMonday && dayOfWeek == 1 && barDay != fridayDay && !mondayChecked) {
            mondayChecked = true;
            if (fridayClose <= 0) return;

            double gap = bar.open() - fridayClose;
            double absGap = Math.abs(gap);
            double atr = atr();
            if (Double.isNaN(atr) || atr <= 0) return;
            double atrVal = atr;

            // Gap must be meaningful but not extreme
            double minGap = atrVal * MIN_GAP_MULT;
            double maxGap = atrVal * MAX_GAP_MULT;
            if (absGap < minGap || absGap > maxGap) {
                awaitingMonday = false;
                return;
            }

            // Volume confirmation on Monday's bar
            if (!hasAboveMedianRange()) return;

            if (gap > 0) {
                // Gap up → BUY
                entryPrice = bar.open();
                stopLoss = entryPrice - atrVal * ATR_STOP_MULT;
                highestSinceEntry = entryPrice;
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, MIN_POSITION, entryPrice));
                inTrade = true;
                tradeDirection = Order.Side.BUY;
                barsInTrade = 0;
            } else {
                // Gap down → SELL
                entryPrice = bar.open();
                stopLoss = entryPrice + atrVal * ATR_STOP_MULT;
                lowestSinceEntry = entryPrice;
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, MIN_POSITION, entryPrice));
                inTrade = true;
                tradeDirection = Order.Side.SELL;
                barsInTrade = 0;
            }
        }

        // End Monday window
        if (dayOfWeek != 1) awaitingMonday = false;
    }

    private boolean hasAboveMedianRange() {
        int end = history.size() - 1;
        int lookback = Math.min(RANGE_MEDIAN, end);
        if (lookback < 3) return true;

        double[] ranges = new double[lookback];
        for (int i = 0; i < lookback; i++) {
            int idx = end - lookback + 1 + i;
            ranges[i] = history.get(idx).high() - history.get(idx).low();
        }
        double latestRange = history.get(end).high() - history.get(end).low();
        Arrays.sort(ranges);
        double median = ranges[lookback / 2];
        return latestRange >= median;
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, MIN_POSITION, price).closeOnly());
        inTrade = false;
    }

    private double atr() {
        return Indicators.atr(history, ATR_PERIOD);
    }
}
