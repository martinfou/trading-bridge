package com.martinfou.trading.examples;

import com.martinfou.trading.core.*;

import java.time.*;
import java.util.*;

/**
 * Parameterized HMM Regime Momentum variant for deep-dive sweeps (NOT in catalog).
 *
 * Same Lewis Jackson simplified 3-state transition matrix as
 * HMMRegimeMomentumStrategy, but with configurable REGIME_PROB_MIN so we can
 * test whether the near-zero trade count (11 trades / 20 years) is a threshold
 * artifact or a genuinely dead signal.
 *
 * Sweep dimensions (concept-level, not curve-fitting — we test robustness of
 * the trade-generating capacity):
 *   - regimeProbMin: 0.55 (baseline) / 0.30 / 0.15
 *   - sidewaysThreshold: 0.001 (0.1%) / 0.0005 / 0.002
 */
public class HmmRegimeSweepStrategy implements Strategy {

    private final String name;
    private final String symbol;
    private final double regimeProbMin;
    private final double sidewaysThreshold;
    private final int transitionWindow;
    private final int regimeLookback;

    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int barsInTrade;
    private double highestSinceEntry;
    private double lowestSinceEntry;
    private int cooldownBars;
    private int tradesToday;
    private int lastTradeDay;

    private static final int MIN_HISTORY = 80;
    private static final int ATR_PERIOD = 14;
    private static final double ATR_STOP_MULT = 1.5;
    private static final double RR_TARGET = 2.0;
    private static final int MAX_BARS_HOLD = 10;
    private static final double MIN_POSITION = 1000;
    private static final int COOLDOWN_BARS = 3;
    private static final int MAX_TRADES_PER_DAY = 2;
    private static final double HMM_WEIGHT = 0.6;
    private static final double TRAILING_WEIGHT = 0.4;
    private static final double TRAILING_RET_NORM = 0.05;

    private static final int BULL = 0;
    private static final int SIDEWAYS_STATE = 1;
    private static final int BEAR = 2;

    private final ZoneId nyZone = ZoneId.of("America/New_York");

    public HmmRegimeSweepStrategy(String name, String symbol,
                                  double regimeProbMin, double sidewaysThreshold,
                                  int transitionWindow, int regimeLookback) {
        this.name = name;
        this.symbol = symbol;
        this.regimeProbMin = regimeProbMin;
        this.sidewaysThreshold = sidewaysThreshold;
        this.transitionWindow = transitionWindow;
        this.regimeLookback = regimeLookback;
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

        boolean buyContinuation = signal > regimeProbMin && bar.close() > bar.open();
        boolean sellContinuation = signal < -regimeProbMin && bar.close() < bar.open();

        if (buyContinuation) {
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            takeProfit = entryPrice + atr * ATR_STOP_MULT * RR_TARGET;
            highestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, MIN_POSITION, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            enterTrade(Order.Side.BUY);
        } else if (sellContinuation) {
            entryPrice = bar.close();
            stopLoss = entryPrice + atr * ATR_STOP_MULT;
            takeProfit = entryPrice - atr * ATR_STOP_MULT * RR_TARGET;
            lowestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, MIN_POSITION, entryPrice)
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
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, MIN_POSITION, price).closeOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }

    private double computeBlendedSignal() {
        int end = history.size() - 1;
        if (end < transitionWindow + 1) return Double.NaN;

        int[][] transitions = new int[3][3];
        int[] stateCounts = new int[3];

        int start = end - transitionWindow;
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

        double latestReturn = (history.get(end).close() - history.get(end - 1).close())
            / history.get(end - 1).close();
        int currentState = classifyReturn(latestReturn);

        double[] probs = new double[3];
        int totalFromCurrent = stateCounts[currentState];
        if (totalFromCurrent <= 0) return Double.NaN;

        for (int j = 0; j < 3; j++) {
            probs[j] = (double) transitions[currentState][j] / totalFromCurrent;
        }

        double hmmSignal = probs[BULL] - probs[BEAR];

        double closeNow = history.get(end).close();
        double closePast = history.get(Math.max(0, end - regimeLookback)).close();
        double trailingRet = (closeNow - closePast) / closePast;
        double trailingSignal = trailingRet / TRAILING_RET_NORM;
        trailingSignal = Math.max(-1.0, Math.min(1.0, trailingSignal));

        return hmmSignal * HMM_WEIGHT + trailingSignal * TRAILING_WEIGHT;
    }

    private int classifyReturn(double ret) {
        if (ret > sidewaysThreshold) return BULL;
        if (ret < -sidewaysThreshold) return BEAR;
        return SIDEWAYS_STATE;
    }

    private double atr() {
        return com.martinfou.trading.core.indicators.Indicators.atr(history, ATR_PERIOD);
    }
}
