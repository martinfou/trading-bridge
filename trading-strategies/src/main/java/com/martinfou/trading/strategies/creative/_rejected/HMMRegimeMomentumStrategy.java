package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * HMM Regime-Aware Momentum Strategy — Structure/Technical
 *
 * 📊 Inspiration: Lewis Jackson's HMM regime detection — simplified 3-state
 *    transition matrix (Bull/Sideways/Bear) from trailing bar returns.
 *    Blends HMM transition probabilities (60%) with trailing momentum (40%).
 *
 * 🔧 Mechanism:
 *    - Classify each bar's return into Bull (>+0.1%), Sideways, Bear (<-0.1%)
 *    - Build 3×3 transition matrix over an 80-bar window (~3.3 days H1)
 *    - Signal = P(Bull | currentState) − P(Bear | currentState)
 *    - Blend 60/40 with trailing 20-bar return (normalized)
 *    - Only enter when |signal| > 0.55 AND bar closes in regime direction
 *    - Exit: ATR trailing stop (1.5×), max 10 bars hold, or regime flip
 *
 * 🎯 Originality: First HMM-based regime filter in the strategy suite.
 *    Unlike DualTimeframeConviction (EMA-based TF filters) or ADXTrendFilter
 *    (ADX trend strength), this uses probabilistic state transitions to
 *    detect market regime with a statistical edge, not arbitrary thresholds.
 *
 * Reference: Lewis Jackson HMM methodology (lewis-jackson-hmm skill),
 *   simplified-hmm-java-implementation.md in skill references.
 */
public class HMMRegimeMomentumStrategy implements Strategy {

    private static final int MIN_HISTORY = 80;
    private static final int TRANSITION_WINDOW = 80;
    private static final int REGIME_LOOKBACK = 20;
    private static final int ATR_PERIOD = 14;
    private static final double SIDEWAYS_THRESHOLD = 0.001; // 0.1%
    private static final double REGIME_PROB_MIN = 0.55;
    private static final double HMM_WEIGHT = 0.6;
    private static final double TRAILING_WEIGHT = 0.4;
    private static final double TRAILING_RET_NORM = 0.05;
    private static final double ATR_STOP_MULT = 1.5;
    private static final double RR_TARGET = 2.0;
    private static final int MAX_BARS_HOLD = 10;
    private static final double MIN_POSITION = 1000;
    private static final int COOLDOWN_BARS = 3;
    private static final int MAX_TRADES_PER_DAY = 2;

    // States: 0=Bull, 1=Sideways, 2=Bear
    private static final int BULL = 0;
    private static final int SIDEWAYS_STATE = 1;
    private static final int BEAR = 2;

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

    public HMMRegimeMomentumStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
    }

    public HMMRegimeMomentumStrategy() {
        this("HMMRegimeMomentum", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        // Daily trade cap
        int barDay = bar.timestamp().atZone(nyZone).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        managePosition(bar);

        if (!inTrade) {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= MAX_TRADES_PER_DAY) return;
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

        // Regime flip exit
        double signal = computeBlendedSignal();
        if (!Double.isNaN(signal)) {
            if ((tradeDirection == Order.Side.BUY && signal < -0.2)
                || (tradeDirection == Order.Side.SELL && signal > 0.2)) {
                closePosition(bar.close());
            }
        }
    }

    private void evaluateEntry(Bar bar) {
        double signal = computeBlendedSignal();
        if (Double.isNaN(signal)) return;

        double atr = atr();
        if (Double.isNaN(atr) || atr <= 0) return;

        // Continuation check: bar closes in direction of regime
        boolean buyContinuation = signal > REGIME_PROB_MIN && bar.close() > bar.open();
        boolean sellContinuation = signal < -REGIME_PROB_MIN && bar.close() < bar.open();

        if (buyContinuation) {
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            takeProfit = entryPrice + atr * ATR_STOP_MULT * RR_TARGET;
            highestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            enterTrade(Order.Side.BUY);
        } else if (sellContinuation) {
            entryPrice = bar.close();
            stopLoss = entryPrice + atr * ATR_STOP_MULT;
            takeProfit = entryPrice - atr * ATR_STOP_MULT * RR_TARGET;
            lowestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            enterTrade(Order.Side.SELL);
        }
    }

    private void enterTrade(Order.Side direction) {
        inTrade = true;
        tradeDirection = direction;
        barsInTrade = 0;
        tradesToday++;
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, positionSize, price).closeOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }

    /**
     * Compute blended HMM + trailing return signal.
     * Returns value in [-1, +1] where +1 = strongly bullish, -1 = strongly bearish.
     */
    private double computeBlendedSignal() {
        int end = history.size() - 1;
        if (end < TRANSITION_WINDOW + 1) return Double.NaN;

        // Step 1: Build 3x3 transition matrix
        int[][] transitions = new int[3][3];
        int[] stateCounts = new int[3];

        int start = end - TRANSITION_WINDOW;
        for (int i = start; i < end; i++) {
            double ret = (history.get(i).close() - history.get(i - 1).close())
                / history.get(i - 1).close();
            int prevState = classifyReturn(ret);

            double nextRet = (history.get(i + 1).close() - history.get(i).close())
                / history.get(i).close();
            int nextState = classifyReturn(nextRet);

            transitions[prevState][nextState]++;
            stateCounts[prevState]++;
        }

        // Step 2: Current state
        double latestReturn = (history.get(end).close() - history.get(end - 1).close())
            / history.get(end - 1).close();
        int currentState = classifyReturn(latestReturn);

        // Step 3: Transition probabilities from current state
        double[] probs = new double[3];
        int totalFromCurrent = stateCounts[currentState];
        if (totalFromCurrent <= 0) return Double.NaN;

        for (int j = 0; j < 3; j++) {
            probs[j] = (double) transitions[currentState][j] / totalFromCurrent;
        }

        // HMM signal: P(Bull) - P(Bear)
        double hmmSignal = probs[BULL] - probs[BEAR];

        // Step 4: Trailing return signal
        double closeNow = history.get(end).close();
        double closePast = history.get(Math.max(0, end - REGIME_LOOKBACK)).close();
        double trailingRet = (closeNow - closePast) / closePast;
        double trailingSignal = trailingRet / TRAILING_RET_NORM;
        trailingSignal = Math.max(-1.0, Math.min(1.0, trailingSignal));

        // Step 5: Blended signal
        return hmmSignal * HMM_WEIGHT + trailingSignal * TRAILING_WEIGHT;
    }

    /** Classify bar return into Bull(0), Sideways(1), Bear(2). */
    private int classifyReturn(double ret) {
        if (ret > SIDEWAYS_THRESHOLD) return BULL;
        if (ret < -SIDEWAYS_THRESHOLD) return BEAR;
        return SIDEWAYS_STATE;
    }

    private double atr() {
        return Indicators.atr(history, ATR_PERIOD);
    }
}
