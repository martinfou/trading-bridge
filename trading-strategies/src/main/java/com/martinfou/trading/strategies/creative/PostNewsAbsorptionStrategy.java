package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * Post-News Absorption Volatility Strategy — News/Sentiment
 *
 * 📊 Inspiration: TTrades NFP Protocol (wait for 3-min impulse completion,
 *    entries on initial impulse exhaustion), Steven Goldstein's straddle
 *    system (pre-event position, exit via order flow reversal), and
 *    forex-soft-signals absorption/exhaustion patterns.
 *
 * 🔧 Mechanism:
 *    - Detect "volatility expansion" bars: range > 1.8× median range of last 20 bars
 *      (proxy for news/event bars since we don't have an economic calendar feed)
 *    - After expansion, wait for "absorption" bar: range < median range with
 *      body in the direction of the absorption (absorption closes in the top
 *      half of its range for bullish absorption, bottom half for bearish)
 *    - Enter on absorption bar close in direction of absorption
 *    - Only valid if absorption occurs within 3 bars of expansion
 *    - Exit: ATR trailing stop (1.5×), max 5 bars hold (news fade is short-lived)
 *
 * 🎯 Originality: No existing strategy in the creative suite uses this
 *    volatility-expansion-then-absorption pattern. VolatilitySpikeFade fades
 *    the spike immediately; this one waits for absorption confirmation.
 *    The 2-stage detection (expansion → absorption) is unique.
 */
public class PostNewsAbsorptionStrategy implements Strategy {

    private static final int MIN_HISTORY = 50;
    private static final int ATR_PERIOD = 14;
    private static final int RANGE_MEDIAN_BARS = 20;
    private static final double EXPANSION_MULT = 1.8;
    private static final int MAX_ABSORPTION_DELAY = 3;
    private static final double ATR_STOP_MULT = 1.5;
    private static final double RR_TARGET = 1.5; // shorter because news fades reverse faster
    private static final int MAX_BARS_HOLD = 5;
    private static final double MIN_POSITION = 1000;
    private static final int COOLDOWN_BARS = 5;

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private final ZoneId nyZone = ZoneId.of("America/New_York");

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int barsInTrade;
    private double highestSinceEntry;
    private double lowestSinceEntry;
    private double positionSize;
    private int cooldownBars;
    private int tradesToday;
    private int lastTradeDay;

    // Expansion-absorption state machine
    private enum Phase { IDLE, EXPANSION_DETECTED, AWAITING_ABSORPTION }
    private Phase phase = Phase.IDLE;
    private int expansionBarIndex = -1;
    private Order.Side expansionDirection; // direction of the expansion bar
    private int barsSinceExpansion;

    public PostNewsAbsorptionStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
    }

    public PostNewsAbsorptionStrategy() {
        this("PostNewsAbsorption", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        int barDay = bar.timestamp().atZone(nyZone).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        managePosition(bar);

        if (!inTrade) {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 2) return;
            evaluateEntry(bar);
        }
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
        cooldownBars = 0;
        tradesToday = 0;
        lastTradeDay = -1;
        phase = Phase.IDLE;
        expansionBarIndex = -1;
        barsSinceExpansion = 0;
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
        boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= takeProfit)
            || (tradeDirection == Order.Side.SELL && bar.low() <= takeProfit);

        if (stopHit || tpHit || barsInTrade >= MAX_BARS_HOLD) {
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

    private void evaluateEntry(Bar bar) {
        int end = history.size() - 1;
        double atr = atr();
        if (Double.isNaN(atr) || atr <= 0) return;

        double medianRange = computeMedianRange();

        // Phase transition logic
        switch (phase) {
            case IDLE -> {
                // Look for volatility expansion bar
                double range = bar.high() - bar.low();
                if (range > medianRange * EXPANSION_MULT) {
                    phase = Phase.EXPANSION_DETECTED;
                    expansionBarIndex = end;
                    expansionDirection = bar.close() > bar.open() ? Order.Side.BUY : Order.Side.SELL;
                    barsSinceExpansion = 0;
                }
            }
            case EXPANSION_DETECTED -> {
                // Confirm absorption pattern on the same bar
                checkAndEnter(bar, end, atr, medianRange);
            }
            case AWAITING_ABSORPTION -> {
                // Already have expansion — look for absorption
                checkAndEnter(bar, end, atr, medianRange);
            }
        }
    }

    /**
     * Check if current bar is an absorption bar and enter if so.
     * An absorption bar has narrow range (below median) and closes
     * in the direction that confirms reversal from the expansion.
     */
    private void checkAndEnter(Bar bar, int end, double atr, double medianRange) {
        double range = bar.high() - bar.low();

        // If another expansion fires, reset to this new expansion
        if (range > medianRange * EXPANSION_MULT) {
            expansionBarIndex = end;
            expansionDirection = bar.close() > bar.open() ? Order.Side.BUY : Order.Side.SELL;
            barsSinceExpansion = 0;
            phase = Phase.EXPANSION_DETECTED;
            return;
        }

        // Check for absorption bar (narrow range)
        if (range <= medianRange) {
            boolean isBullishAbsorption = bar.close() > bar.open()
                && bar.close() >= bar.low() + range * 0.6; // closes in upper 60%
            boolean isBearishAbsorption = bar.close() < bar.open()
                && bar.close() <= bar.low() + range * 0.4; // closes in lower 40%

            // Enter in the OPPOSITE direction of expansion (fading the news spike)
            if (isBullishAbsorption && expansionDirection == Order.Side.SELL) {
                // Bullish absorption after bearish expansion → reversal up
                doEntry(bar, Order.Side.BUY, atr);
                resetPhase();
            } else if (isBearishAbsorption && expansionDirection == Order.Side.BUY) {
                // Bearish absorption after bullish expansion → reversal down
                doEntry(bar, Order.Side.SELL, atr);
                resetPhase();
            } else {
                // Absorption without reversal pattern — wait more but with decay
                barsSinceExpansion++;
                if (barsSinceExpansion >= MAX_ABSORPTION_DELAY) {
                    resetPhase();
                }
            }
        } else {
            // Wide range but not expansion — still waiting
            barsSinceExpansion++;
            if (barsSinceExpansion >= MAX_ABSORPTION_DELAY) {
                resetPhase();
            }
        }
    }

    private void doEntry(Bar bar, Order.Side direction, double atr) {
        entryPrice = bar.close();
        if (direction == Order.Side.BUY) {
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            takeProfit = entryPrice + atr * ATR_STOP_MULT * RR_TARGET;
            highestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
        } else {
            stopLoss = entryPrice + atr * ATR_STOP_MULT;
            takeProfit = entryPrice - atr * ATR_STOP_MULT * RR_TARGET;
            lowestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
        }
        inTrade = true;
        tradeDirection = direction;
        barsInTrade = 0;
        tradesToday++;
    }

    private void resetPhase() {
        phase = Phase.IDLE;
        expansionBarIndex = -1;
        barsSinceExpansion = 0;
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, positionSize, price).closeOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }

    private double computeMedianRange() {
        int end = history.size() - 1;
        int lookback = Math.min(RANGE_MEDIAN_BARS, end);
        if (lookback < 3) return Double.MAX_VALUE;

        double[] ranges = new double[lookback];
        for (int i = 0; i < lookback; i++) {
            int idx = end - lookback + 1 + i;
            ranges[i] = history.get(idx).high() - history.get(idx).low();
        }
        Arrays.sort(ranges);
        return ranges[lookback / 2];
    }

    private double atr() {
        return Indicators.atr(history, ATR_PERIOD);
    }
}
