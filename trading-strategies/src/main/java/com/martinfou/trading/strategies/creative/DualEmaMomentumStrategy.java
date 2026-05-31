package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * Dual-EMA Momentum with Volatility Filter — Structure/Technical
 *
 * 📊 Inspiration: Part Time Larry's multi-framework approach + Ali Casey's
 *    "volume confirms everything" adapted to volatility.
 *    Simple dual-EMA crossover with ATR expansion filter avoids choppy markets.
 *
 * 🔧 Mechanism:
 *    - Fast EMA(12) and Slow EMA(48) on H1 close
 *    - Entry: EMA cross + ATR(14) expansion > 20-period ATR median
 *    - Exit: EMA cross opposite direction, or 2.5× ATR trailing stop
 *    - Volatility filter prevents trend-fading in choppy markets
 *
 * 🎯 Improvements over HMM Regime:
 *    - More trades (EMA crosses frequently)
 *    - Volatility filter ensures entries in trending conditions
 *    - Simpler logic = fewer degrees of freedom for overfitting
 */
public class DualEmaMomentumStrategy implements Strategy {

    private static final int FAST_EMA = 12;
    private static final int SLOW_EMA = 48;
    private static final int ATR_PERIOD = 14;
    private static final int VOLATILITY_LOOKBACK = 20;
    private static final double STOP_ATR_MULT = 2.5;
    private static final int MIN_HISTORY = SLOW_EMA + ATR_PERIOD + 20;
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

    // Previous EMA cross state for detecting crossovers
    private boolean prevFastAboveSlow = false;

    public DualEmaMomentumStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
    }

    public DualEmaMomentumStrategy() {
        this("DualEmaMomentum", "EUR_USD");
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
        prevFastAboveSlow = false;
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

        // Exit on stop loss, take profit, or EMA cross (trend reversal)
        double fastEma = ema(FAST_EMA);
        double slowEma = ema(SLOW_EMA);
        boolean fastAboveSlow = fastEma > slowEma;

        if (tradeDirection == Order.Side.BUY) {
            if (bar.low() <= stopLoss || bar.high() >= takeProfit) {
                inTrade = false;
                return;
            }
            // Exit if EMA death cross (trend reversal signal)
            if (!fastAboveSlow && prevFastAboveSlow) {
                inTrade = false;
                return;
            }
        } else {
            if (bar.high() >= stopLoss || bar.low() <= takeProfit) {
                inTrade = false;
                return;
            }
            // Exit if EMA golden cross (trend reversal signal)
            if (fastAboveSlow && !prevFastAboveSlow) {
                inTrade = false;
                return;
            }
        }

        prevFastAboveSlow = fastAboveSlow;

        // Trailing stop
        double atr = atr(ATR_PERIOD);
        if (tradeDirection == Order.Side.BUY) {
            stopLoss = Math.max(stopLoss, bar.close() - atr * STOP_ATR_MULT);
        } else {
            stopLoss = Math.min(stopLoss, bar.close() + atr * STOP_ATR_MULT);
        }
    }

    private void evaluateEntry(Bar bar) {
        // Check EMA crossover
        double fastEma = ema(FAST_EMA);
        double slowEma = ema(SLOW_EMA);
        if (Double.isNaN(fastEma) || Double.isNaN(slowEma)) return;

        boolean fastAboveSlow = fastEma > slowEma;
        boolean crossoverBuy = fastAboveSlow && !prevFastAboveSlow;    // Golden cross
        boolean crossoverSell = !fastAboveSlow && prevFastAboveSlow;   // Death cross
        prevFastAboveSlow = fastAboveSlow;

        if (!crossoverBuy && !crossoverSell) return;

        // Volatility filter: ATR must be above its 20-period median
        double currentAtr = atr(ATR_PERIOD);
        if (Double.isNaN(currentAtr) || currentAtr <= 0) return;

        double medianAtr = medianAtr(VOLATILITY_LOOKBACK);
        if (Double.isNaN(medianAtr) || currentAtr < medianAtr) return;

        double pip = Indicators.pipSize(symbol);

        if (crossoverBuy) {
            entryPrice = bar.close();
            stopLoss = entryPrice - currentAtr * STOP_ATR_MULT;
            takeProfit = entryPrice + currentAtr * STOP_ATR_MULT * 2.0;

            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
        } else {
            entryPrice = bar.close();
            stopLoss = entryPrice + currentAtr * STOP_ATR_MULT;
            takeProfit = entryPrice - currentAtr * STOP_ATR_MULT * 2.0;

            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            barsInTrade = 0;
        }
    }

    private double ema(int period) {
        return Indicators.emaLatest(history, period);
    }

    private double atr(int period) {
        return Indicators.atr(history, period);
    }

    /** Median ATR over last N periods. */
    private double medianAtr(int lookback) {
        int end = history.size() - 1;
        int start = Math.max(0, end - lookback);
        double[] values = new double[end - start];
        int idx = 0;
        for (int i = start; i < end; i++) {
            values[idx++] = Indicators.atr(history.subList(0, i + 1), ATR_PERIOD);
        }
        Arrays.sort(values);
        return values[values.length / 2];
    }
}
