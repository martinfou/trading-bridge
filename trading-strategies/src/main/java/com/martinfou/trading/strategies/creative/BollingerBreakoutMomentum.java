package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * BollingerBreakoutMomentum — Volatility breakout via Bollinger Bands (H1)
 *
 * 📊 Concept: Bollinger Bands measure volatility. When price breaks decisively
 *    outside the bands on a trending market, the breakout often carries momentum.
 *    The EMA(20) acts as the primary trend filter — only trading in the direction
 *    of the dominant trend. Manual SL/TP management avoids engine sync issues.
 *
 * 🔧 Mechanism:
 *    - BB(20, 2) identifies volatility extremes on H1
 *    - EMA(20) provides short-term trend direction filter
 *    - Close above upper BB + above EMA(20) → LONG breakout
 *    - Close below lower BB + below EMA(20) → SHORT breakdown
 *    - Manual SL = 1.5× ATR(14), TP = 3.0× ATR(14)
 *    - Price closing back inside the bands → early exit (failed breakout)
 *    - closeOnly() exits for reliable position closure
 *    - Max 1 trade per day
 */
public class BollingerBreakoutMomentum implements Strategy {

    private static final int BB_PERIOD = 20;
    private static final double BB_MULT = 2.0;
    private static final int EMA_PERIOD = 20;
    private static final int ATR_PERIOD = 14;
    private static final double SL_MULT = 1.5;
    private static final double TP_MULT = 3.0;
    private static final double REFERENCE_CAPITAL = 10_000;
    private static final double RISK_PCT = 0.01;
    private static final int MIN_HISTORY = BB_PERIOD + ATR_PERIOD + 10;
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

    public BollingerBreakoutMomentum() {
        this("BollingerBreakoutMomentum", "EUR_USD");
    }

    public BollingerBreakoutMomentum(String name) {
        this(name, "EUR_USD");
    }

    public BollingerBreakoutMomentum(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        // Compute indicators on history WITHOUT current bar (avoid look-ahead)
        int histSize = history.size();
        if (histSize < MIN_HISTORY - 1) { history.add(bar); return; }

        double[] bb = Indicators.bollingerWidth(history, BB_PERIOD, BB_MULT);
        double lower = bb[0];
        double upper = bb[1];
        double ema50 = Indicators.emaLatest(history, EMA_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);

        // Now add current bar to history
        history.add(bar);

        // Daily trade counter (reset on new day)
        int barDay = bar.timestamp().atZone(java.time.ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) {
            tradesToday = 0;
            lastTradeDay = barDay;
        }

        if (inTrade) {
            managePosition(bar);
        } else {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 1) return; // max 1 trade per day
            evaluateEntry(bar, lower, upper, ema50, atr);
        }
    }

    private void evaluateEntry(Bar bar, double lower, double upper, double ema50, double atr) {
        if (Double.isNaN(lower) || Double.isNaN(upper) || Double.isNaN(ema50) || Double.isNaN(atr) || atr <= 0) return;

        if (bar.close() > upper && bar.close() > ema50) {
            // Bullish breakout in uptrend — enter LONG
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * SL_MULT;
            takeProfit = entryPrice + atr * TP_MULT;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET,
                Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol), entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            tradesToday++;
        } else if (bar.close() < lower && bar.close() < ema50) {
            // Bearish breakdown in downtrend — enter SHORT
            entryPrice = bar.close();
            stopLoss = entryPrice + atr * SL_MULT;
            takeProfit = entryPrice - atr * TP_MULT;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET,
                Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol), entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            tradesToday++;
        }
    }

    private void managePosition(Bar bar) {
        // Check stop loss (intra-bar)
        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);

        // Check take profit (intra-bar)
        boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= takeProfit)
            || (tradeDirection == Order.Side.SELL && bar.low() <= takeProfit);

        if (stopHit) {
            exitPosition(stopLoss);
            return;
        }
        if (tpHit) {
            exitPosition(takeProfit);
            return;
        }

        // Check if price closes back inside the bands (failed breakout)
        double[] bb = Indicators.bollingerWidth(history, BB_PERIOD, BB_MULT);
        double lower = bb[0];
        double upper = bb[1];
        if (Double.isNaN(lower) || Double.isNaN(upper)) return;

        boolean insideBands = bar.close() > lower && bar.close() < upper;
        if (insideBands) {
            // Price re-entered the band — breakout failed, exit at market
            exitPosition(bar.close());
        }
    }

    private void exitPosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, 1000, price).asCloseOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
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
        lastTradeDay = -1;
        tradesToday = 0;
        cooldownBars = 0;
    }
}
