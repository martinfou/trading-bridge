package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * StochasticMeanReversion — Stoch(14,3) mean reversion with EMA trend (H1)
 *
 * 📊 Concept: When Stoch %K(14) < 20 on H1, price is in the bottom 20% of
 *    the 14-bar range — a genuine oversold extreme. Unlike RSI(3) which
 *    measures velocity, stochastic measures position within the range.
 *    This catches different exhaustion patterns: gap-downs, V-reversals,
 *    and range-bound extremes that RSI misses.
 *
 *    Target: GBPUSD — known for strong mean reversion properties and
 *    April BUY seasonality (89% hit rate).
 *
 * 🔧 Mechanism:\n *    - Stoch %K(5): close position within 5-bar high-low range\n *    - < 15 (extreme oversold) → BUY, > 85 (extreme overbought) → SELL\n *    - EMA(100) trend filter: buy in uptrend, sell in downtrend
 *    - Inline GBPUSD seasonality: Mar 11-Apr 25 BUY (83%)
 *    - ATR(14): SL = 1.2× ATR, TP = 2.0× ATR
 *    - Manual SL/TP via manageExit(), closeOnly() exit
 *    - Max 1 trade/day
 *
 * 🎯 Target: 40-70 trades/year, Sharpe > 0.8, PF > 1.3, DD < 8%
 */
public class StochasticMeanReversion implements Strategy {

    // --- Parameters ---
    private static final int STOCH_PERIOD = 3;
    private static final int EMA_PERIOD = 100;
    private static final int ATR_PERIOD = 14;
    private static final double OVERSOLD = 10.0;
    private static final double OVERBOUGHT = 90.0;
    private static final double SL_MULT = 1.2;
    private static final double TP_MULT = 2.0;
    private static final double REFERENCE_CAPITAL = 10_000;
    private static final double RISK_PCT = 0.008;
    private static final int MIN_HISTORY = Math.max(STOCH_PERIOD, Math.max(EMA_PERIOD, ATR_PERIOD)) + 5;
    private static final int COOLDOWN_BARS = 2;

    private final String name;
    private final String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int lastTradeDay = -1;
    private int tradesToday = 0;
    private int cooldownBars = 0;

    // --- Constructors ---
    public StochasticMeanReversion() { this("StochasticMeanReversion", "GBP_USD"); }
    public StochasticMeanReversion(String name) { this(name, "GBP_USD"); }
    public StochasticMeanReversion(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        // ⚠️ LOOK-AHEAD SAFE: compute on CLOSED history first
        if (history.size() < MIN_HISTORY - 1) { history.add(bar); return; }

        // Indicators on closed bars only
        double stochK = computeStochasticK(history, STOCH_PERIOD);
        double ema100 = Indicators.emaLatest(history, EMA_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);
        history.add(bar);

        if (Double.isNaN(stochK) || Double.isNaN(ema100) || Double.isNaN(atr) || atr <= 0) return;

        // Daily trade limit (NY timezone)
        int barDay = bar.timestamp().atZone(ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        if (inTrade) {
            managePosition(bar);
        } else {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 1) return;
            evaluateEntry(bar, stochK, ema100, atr);
        }
    }

    /**
     * Compute Stochastic %K = (close - lowestLow) / (highestHigh - lowestLow) × 100
     * Uses the last `period` bars from `bars` list.
     */
    private double computeStochasticK(List<Bar> bars, int period) {
        int n = bars.size();
        if (n < period) return Double.NaN;
        double close = bars.get(n - 1).close();
        double lowestLow = bars.get(n - 1).low();
        double highestHigh = bars.get(n - 1).high();
        for (int i = n - period; i < n; i++) {
            lowestLow = Math.min(lowestLow, bars.get(i).low());
            highestHigh = Math.max(highestHigh, bars.get(i).high());
        }
        double range = highestHigh - lowestLow;
        if (range <= 0) return 50.0; // flat market → neutral
        return (close - lowestLow) / range * 100.0;
    }

    /**
     * Inline seasonality filter for GBPUSD.
     * - Mar 11 → Apr 25: BUY (83% hit rate)
     * - April: BUY (89% hit rate)
     */
    private Order.Side getSeasonalBias(Instant timestamp) {
        ZonedDateTime zdt = timestamp.atZone(ZoneId.of("America/New_York"));
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();
        // Mar 11 → Apr 25: BUY (83%)
        if ((month == 3 && day >= 11) || (month == 4 && day <= 25)) return Order.Side.BUY;
        return null;
    }

    private void evaluateEntry(Bar bar, double stochK, double ema100, double atr) {
        double close = bar.close();

        // Seasonality filter
        Order.Side bias = getSeasonalBias(bar.timestamp());

        // --- BUY: Stoch < 20 (oversold) in uptrend ---
        if (stochK < OVERSOLD && close > ema100) {
            if (bias == Order.Side.SELL) return;

            entryPrice = close;
            stopLoss = entryPrice - atr * SL_MULT;
            takeProfit = entryPrice + atr * TP_MULT;
            double qty = Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol);
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, qty, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            tradesToday++;
        }
        // --- SELL: Stoch > 80 (overbought) in downtrend ---
        else if (stochK > OVERBOUGHT && close < ema100) {
            if (bias == Order.Side.BUY) return;

            entryPrice = close;
            stopLoss = entryPrice + atr * SL_MULT;
            takeProfit = entryPrice - atr * TP_MULT;
            double qty = Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol);
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, qty, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            tradesToday++;
        }
    }

    private void managePosition(Bar bar) {
        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);
        boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= takeProfit)
            || (tradeDirection == Order.Side.SELL && bar.low() <= takeProfit);
        if (stopHit) { exitPosition(stopLoss); return; }
        if (tpHit) { exitPosition(takeProfit); return; }
    }

    private void exitPosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, 1000, price).closeOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }

    @Override public void onTick(double bid, double ask, long volume) {}

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
        lastTradeDay = -1;
        tradesToday = 0;
        cooldownBars = 0;
    }
}
