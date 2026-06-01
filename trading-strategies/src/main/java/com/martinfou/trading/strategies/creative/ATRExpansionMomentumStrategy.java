package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * ATR Expansion Momentum Strategy — News/Sentiment
 *
 * 📊 Inspiration: forex-news-trading — Steven Goldstein's straddle expansion,
 *    TTrades' NFP Protocol (wait for spike, then trade direction), and
 *    Nick Bala's volatility compression filter. Instead of trading the
 *    initial news spike (unreliable due to slippage on real events), this
 *    strategy detects volatility expansion bars (proxy for news events)
 *    and trades the FOLLOW-THROUGH momentum in the expansion direction.
 *
 * 🔧 Mechanism:
 *    - Track 10-bar median ATR as "normal" volatility baseline
 *    - Detect expansion bar: current ATR(14) > 1.3 × median ATR(10)
 *    - On expansion bar, determine direction (bullish or bearish close)
 *    - Enter on NEXT bar in the expansion direction (wait for follow-through)
 *    - Exit: trailing stop at 1.5× ATR of the expansion bar, max 8 bars hold
 *    - No re-entry until new expansion detected
 *
 * 🎯 Originality: Pure volatility-momentum approach. Unlike VolatilitySpikeFadeStrategy
 *    (which fades the spike), this strategy trades IN THE DIRECTION of the expansion
 *    after waiting for the initial impulse to resolve. This simulates the
 *    "react don't anticipate" approach from The Trading Channel and Rayner Teo's
 *    post-news trend following methodology. Works best on H1 where news-driven
 *    momentum tends to persist for several bars after initial reaction.
 *
 * Key difference from VolContractionBreakoutStrategy: that strategy enters
 * ON the breakout from a contraction. This strategy waits 1 bar AFTER an
 * expansion to confirm direction, reducing false breakouts.
 */
public class ATRExpansionMomentumStrategy implements Strategy {

    private static final int MIN_HISTORY = 30;
    private static final int ATR_PERIOD = 14;
    private static final int MEDIAN_LOOKBACK = 10;
    private static final double EXPANSION_MULT = 1.3;
    private static final double ATR_STOP_MULT = 1.5;
    private static final double RR_TARGET = 2.0;
    private static final int MAX_BARS_HOLD = 8;
    private static final int COOLDOWN_BARS = 5;
    private static final double MIN_POSITION = 1000;

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int barsInTrade;
    private double expansionAtr;
    private int barsSinceExpansion;
    private boolean awaitingEntry = false;
    private Order.Side expansionDirection;
    private int cooldownCounter;
    private double positionSize;

    public ATRExpansionMomentumStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
    }

    public ATRExpansionMomentumStrategy() {
        this("ATRExpansionMomentum", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        managePosition(bar);

        if (!inTrade) {
            if (cooldownCounter > 0) {
                cooldownCounter--;
            }
            if (awaitingEntry) {
                tryEntry(bar);
            } else {
                detectExpansion(bar);
            }
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
        awaitingEntry = false;
        barsInTrade = 0;
        barsSinceExpansion = 0;
        cooldownCounter = 0;
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

        if (tradeDirection == Order.Side.BUY) {
            if (bar.low() <= stopLoss || bar.high() >= takeProfit || barsInTrade >= MAX_BARS_HOLD) {
                exitTrade();
                return;
            }
            // Trailing stop based on expansion ATR
            double trail = bar.high() - expansionAtr * ATR_STOP_MULT;
            stopLoss = Math.max(stopLoss, trail);
        } else {
            if (bar.high() >= stopLoss || bar.low() <= takeProfit || barsInTrade >= MAX_BARS_HOLD) {
                exitTrade();
                return;
            }
            double trail = bar.low() + expansionAtr * ATR_STOP_MULT;
            stopLoss = Math.min(stopLoss, trail);
        }
    }

    private void detectExpansion(Bar bar) {
        if (cooldownCounter > 0) return;

        double currentAtr = Indicators.atr(history, ATR_PERIOD);
        if (Double.isNaN(currentAtr)) return;

        // Compute median ATR over lookback
        double[] atrValues = new double[MEDIAN_LOOKBACK];
        for (int i = 0; i < MEDIAN_LOOKBACK; i++) {
            if (history.size() - 1 - i < ATR_PERIOD + 1) return;
            atrValues[i] = Indicators.atr(history.subList(0, history.size() - i), ATR_PERIOD);
        }
        Arrays.sort(atrValues);
        double medianAtr = atrValues[MEDIAN_LOOKBACK / 2];

        if (medianAtr <= 0) return;

        double ratio = currentAtr / medianAtr;
        if (ratio >= EXPANSION_MULT) {
            // Volatility expansion detected — determine direction
            expansionDirection = bar.close() > bar.open() ? Order.Side.BUY : Order.Side.SELL;
            expansionAtr = currentAtr;
            awaitingEntry = true;
            barsSinceExpansion = 0;
        }
    }

    private void tryEntry(Bar bar) {
        double atr = expansionAtr;
        if (Double.isNaN(atr) || atr <= 0) {
            awaitingEntry = false;
            return;
        }

        // Enter in direction of expansion with follow-through confirmation
        boolean followThrough = (expansionDirection == Order.Side.BUY && bar.close() > bar.open())
            || (expansionDirection == Order.Side.SELL && bar.close() < bar.open());

        // Enter on the next bar even without follow-through (weak signal)
        // but require follow-through for strongest entries
        if (barsSinceExpansion >= 1 || followThrough) {
            entryPrice = bar.close();
            if (expansionDirection == Order.Side.BUY) {
                stopLoss = entryPrice - atr * ATR_STOP_MULT;
                takeProfit = entryPrice + atr * ATR_STOP_MULT * RR_TARGET;
            } else {
                stopLoss = entryPrice + atr * ATR_STOP_MULT;
                takeProfit = entryPrice - atr * ATR_STOP_MULT * RR_TARGET;
            }
            pending.add(new Order(symbol, expansionDirection, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = expansionDirection;
            barsInTrade = 0;
            awaitingEntry = false;
        }
        barsSinceExpansion++;
    }

    private void exitTrade() {
        inTrade = false;
        awaitingEntry = false;
        cooldownCounter = COOLDOWN_BARS;
    }
}
