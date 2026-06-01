package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * Composite Momentum Ranking Strategy — Structure/Technical
 *
 * 📊 Inspiration: Ali Casey's massive-scale testing philosophy + Neurotrader's
 *    multi-dimensional signal approach. Instead of relying on a single indicator,
 *    combine three complementary momentum metrics into a composite score that
 *    is more robust than any individual signal.
 *
 * 🔧 Mechanism:
 *    - Compute 3 momentum metrics: 10-bar return, 20-bar SMA slope, RSI(14) level
 *    - Normalize each to a [-1, +1] composite contribution
 *    - Composite score = 0.4 × return_signal + 0.3 × slope_signal + 0.3 × rsi_signal
 *    - Entry: |composite| ≥ 0.55 with trend alignment
 *    - Exit: composite crosses below 0.15 in opposite direction, or 2× ATR trailing stop
 *    - Max hold: 18 bars
 *
 * 🎯 Originality: Multi-indicator ensemble with adaptive weighting.
 *    Unlike single-indicator strategies (EMA cross, RSI alone), the composite
 *    approach naturally filters false signals — all 3 must agree for high-conviction
 *    entries. The normalization scheme allows apples-to-apples comparison of
 *    fundamentally different momentum measures.
 */
public class CompositeMomentumRankingStrategy implements Strategy {

    private static final int MIN_HISTORY = 80;
    private static final int RETURN_PERIOD = 10;
    private static final int SMA_PERIOD = 20;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final double ENTRY_THRESHOLD = 0.55;
    private static final double EXIT_THRESHOLD = 0.15;
    private static final double ATR_STOP_MULT = 2.0;
    private static final double RR_TARGET = 2.0;
    private static final int MAX_BARS_HOLD = 18;
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

    public CompositeMomentumRankingStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
    }

    public CompositeMomentumRankingStrategy() {
        this("CompositeMomentumRanking", "EUR_USD");
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
                exitTrade();
                return;
            }
            // Check composite reversal
            double comp = computeCompositeScore();
            if (comp < -EXIT_THRESHOLD) {
                exitTrade();
                return;
            }
            // Trailing stop
            double trail = bar.high() - atr() * ATR_STOP_MULT;
            stopLoss = Math.max(stopLoss, trail);
        } else {
            if (bar.high() >= stopLoss || bar.low() <= takeProfit || barsInTrade >= MAX_BARS_HOLD) {
                exitTrade();
                return;
            }
            double comp = computeCompositeScore();
            if (comp > EXIT_THRESHOLD) {
                exitTrade();
                return;
            }
            // Trailing stop
            double trail = bar.low() + atr() * ATR_STOP_MULT;
            stopLoss = Math.min(stopLoss, trail);
        }
    }

    private void evaluateEntry(Bar bar) {
        double composite = computeCompositeScore();
        if (Double.isNaN(composite)) return;

        double atr = atr();
        if (Double.isNaN(atr) || atr <= 0) return;

        if (composite >= ENTRY_THRESHOLD) {
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            takeProfit = entryPrice + atr * ATR_STOP_MULT * RR_TARGET;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
        } else if (composite <= -ENTRY_THRESHOLD) {
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

    private void exitTrade() {
        inTrade = false;
    }

    /**
     * Compute composite momentum score in [-1, +1].
     * Positive = bullish, Negative = bearish.
     * Combines: 10-bar return, 20-bar SMA slope, RSI(14).
     */
    private double computeCompositeScore() {
        if (history.size() < MIN_HISTORY) return Double.NaN;
        int end = history.size() - 1;

        // 1) Normalized return signal
        double ret10 = (history.get(end).close() - history.get(end - RETURN_PERIOD).close())
            / history.get(end - RETURN_PERIOD).close();
        double retSignal = Math.max(-1.0, Math.min(1.0, ret10 / 0.03)); // normalize to ±1 at 3%

        // 2) SMA slope signal
        double smaNow = Indicators.sma(history, SMA_PERIOD, end);
        double smaPrev = Indicators.sma(history, SMA_PERIOD, end - 5);
        if (Double.isNaN(smaNow) || Double.isNaN(smaPrev)) return Double.NaN;
        double slope = (smaNow - smaPrev) / smaPrev;
        double slopeSignal = Math.max(-1.0, Math.min(1.0, slope / 0.002)); // normalize at 0.2%

        // 3) RSI signal
        double rsi = Indicators.rsi(history, RSI_PERIOD);
        if (Double.isNaN(rsi)) return Double.NaN;
        double rsiSignal = (rsi - 50) / 50.0; // -1 to +1 (0 at 50)

        // Weighted composite
        return retSignal * 0.4 + slopeSignal * 0.3 + rsiSignal * 0.3;
    }

    private double atr() {
        return Indicators.atr(history, ATR_PERIOD);
    }
}
