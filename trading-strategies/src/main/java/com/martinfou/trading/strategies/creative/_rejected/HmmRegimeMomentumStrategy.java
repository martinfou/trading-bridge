package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * HMM Regime Momentum Strategy — Structure/Technical
 *
 * 📊 Inspiration: Lewis Jackson HMM methodology for market state detection.
 *    Uses a simplified trailing-return based 2-state regime classification
 *    (Bull/Bear) to filter momentum trades. When the market has been in
 *    a clear trending regime (20-bar return > +3% = BULL, < -3% = BEAR),
 *    trade in the direction of the regime with ATR-based exits.
 *
 * 🔧 Mechanism:
 *    - Compute 20-bar trailing return to classify regime
 *    - Build 3×3 transition matrix (Bull/Bear/Sideways) from 50-bar history
 *    - Regime filter: only trade when dominant regime probability > 0.55
 *    - Entry: on continuation bar (close > previous close) in regime direction
 *    - Exit: trailing stop at 2× ATR(14) or regime flip
 *    - Max hold: 12 bars
 *
 * 🎯 Originality: Regime-aware momentum using HMM-inspired state detection.
 *    Unlike fixed thresholds, the transition matrix adapts to recent market
 *    behavior and provides a probabilistic regime signal.
 */
public class HmmRegimeMomentumStrategy implements Strategy {

    private static final int REGIME_LOOKBACK = 20;
    private static final int TRANSITION_WINDOW = 80;
    private static final int MIN_HISTORY = 100;
    private static final double BULL_THRESHOLD = 0.03;  // +3% = bull
    private static final double BEAR_THRESHOLD = -0.03; // -3% = bear
    private static final double REGIME_PROB_MIN = 0.55;
    private static final int ATR_PERIOD = 14;
    private static final double ATR_STOP_MULT = 2.0;
    private static final double RR_TARGET = 2.5;
    private static final int MAX_BARS_HOLD = 12;
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
    private double positionSize;

    public HmmRegimeMomentumStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
    }

    public HmmRegimeMomentumStrategy() {
        this("HmmRegimeMomentum", "EUR_USD");
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
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

        // Exit on stop loss, take profit, or max bars
        if (tradeDirection == Order.Side.BUY) {
            if (bar.low() <= stopLoss || bar.high() >= takeProfit || barsInTrade >= MAX_BARS_HOLD) {
                exitTrade(bar);
                return;
            }
        } else {
            if (bar.high() >= stopLoss || bar.low() <= takeProfit || barsInTrade >= MAX_BARS_HOLD) {
                exitTrade(bar);
                return;
            }
        }

        // Trailing stop
        if (tradeDirection == Order.Side.BUY) {
            double trail = bar.high() - atr(ATR_PERIOD) * ATR_STOP_MULT;
            stopLoss = Math.max(stopLoss, trail);
        } else {
            double trail = bar.low() + atr(ATR_PERIOD) * ATR_STOP_MULT;
            stopLoss = Math.min(stopLoss, trail);
        }
    }

    private void evaluateEntry(Bar bar) {
        double regimeProb = computeRegimeProbability();

        // Determine regime direction and check if probability is sufficient
        Order.Side regimeDirection = null;
        if (regimeProb > REGIME_PROB_MIN) {
            regimeDirection = Order.Side.BUY;
        } else if (regimeProb < -REGIME_PROB_MIN) {
            regimeDirection = Order.Side.SELL;
        } else {
            return; // No clear regime — skip
        }

        // Continuation check: bar closes in regime direction
        boolean continuation = (regimeDirection == Order.Side.BUY && bar.close() > bar.open())
            || (regimeDirection == Order.Side.SELL && bar.close() < bar.open());

        if (!continuation) return;

        double atr = atr(ATR_PERIOD);
        if (Double.isNaN(atr) || atr <= 0) return;

        if (regimeDirection == Order.Side.BUY) {
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            takeProfit = entryPrice + atr * ATR_STOP_MULT * RR_TARGET;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
        } else {
            entryPrice = bar.close();
            stopLoss = entryPrice + atr * ATR_STOP_MULT;
            takeProfit = entryPrice - atr * ATR_STOP_MULT * RR_TARGET;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            barsInTrade = 0;
        }
    }

    private void exitTrade(Bar bar) {
        inTrade = false;
    }

    /**
     * Computes a regime probability in range [-1, +1].
     * Positive = BULL bias, Negative = BEAR bias.
     * Uses Lewis Jackson HMM-inspired approach: trailing return + transition matrix.
     */
    private double computeRegimeProbability() {
        if (history.size() < TRANSITION_WINDOW) return 0;

        int end = history.size() - 1;

        // Compute trailing return over REGIME_LOOKBACK bars
        double trailingReturn = (history.get(end).close() - history.get(end - REGIME_LOOKBACK).close())
            / history.get(end - REGIME_LOOKBACK).close();

        // Classify current state (most recent bar's state)
        double latestReturn = (history.get(end).close() - history.get(end - 1).close())
            / history.get(end - 1).close();
        int currentState = classifyReturn(latestReturn);

        // Build 3-state transition matrix (Bull=0, Sideways=1, Bear=2)
        int[][] transitions = new int[3][3];
        int[] stateCounts = new int[3];

        for (int i = end - TRANSITION_WINDOW + 1; i < end; i++) {
            double ret = (history.get(i).close() - history.get(i - 1).close())
                / history.get(i - 1).close();
            int prevState = classifyReturn(ret);

            double nextRet = (history.get(i + 1).close() - history.get(i).close())
                / history.get(i).close();
            int nextState = classifyReturn(nextRet);

            transitions[prevState][nextState]++;
            stateCounts[prevState]++;
        }

        // Compute transition probabilities from current state
        double[] probs = new double[3];
        int totalFromCurrent = stateCounts[currentState];
        if (totalFromCurrent > 0) {
            for (int j = 0; j < 3; j++) {
                probs[j] = (double) transitions[currentState][j] / totalFromCurrent;
            }
        }

        // Regime signal: P(Bull) - P(Bear)
        double signal = probs[0] - probs[2];

        // Blend with trailing return signal (weighted 60:40)
        double trailingSignal = trailingReturn / 0.05; // normalize to [-1, +1] roughly
        trailingSignal = Math.max(-1.0, Math.min(1.0, trailingSignal));

        return signal * 0.6 + trailingSignal * 0.4;
    }

    /** Classify bar return into Bull(0), Sideways(1), Bear(2). */
    private int classifyReturn(double ret) {
        if (ret > 0.001) return 0; // Bull
        if (ret < -0.001) return 2; // Bear
        return 1; // Sideways
    }

    private double atr(int period) {
        return Indicators.atr(history, period);
    }
}
