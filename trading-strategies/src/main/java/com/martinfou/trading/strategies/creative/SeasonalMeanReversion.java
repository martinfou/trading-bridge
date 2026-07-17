package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * SeasonalMeanReversion — Seasonal window mean reversion on USDCAD (H1)
 *
 * 📊 Concept: Trade only during USDCAD's strongest seasonal windows, fading
 *    intraday extremes against the seasonal bias. In April (72% SELL bias),
 *    sell rallies above EMA(100) + 1× ATR. In Oct-Nov (94% BUY bias), buy
 *    dips below EMA(100) - 1× ATR. Outside these windows, no trades.
 *
 *    Key insight: The seasonal patterns represent genuine institutional flow
 *    (repatriation, reserve rebalancing). Intraday fades within these windows
 *    capture the snap-back with a strong directional tailwind.
 *
 * 🔧 Mechanism:
 *    - Seasonal windows: Apr 1-30 (SELL), Oct 12-Nov 26 (BUY)
 *    - Entry: price deviates from EMA(100) by > 1.5× ATR → fade
 *    - SL = 1.2× ATR, TP = 2.0× ATR (tight, capturing the snap-back)
 *    - Manual SL/TP via manageExit(), closeOnly() exit
 *    - Max 1 trade/day, only within seasonal windows
 *
 * 🎯 Target: 20-40 trades/year, Sharpe > 1.0, PF > 1.5, costs negligible
 *    (few trades means costs don't eat returns)
 */
public class SeasonalMeanReversion implements Strategy {

    // --- Parameters ---
    private static final int EMA_PERIOD = 100;
    private static final int ATR_PERIOD = 14;
    private static final double DEVIATION_MULT = 1.5;   // ATR multiplier for deviation threshold
    private static final double SL_MULT = 1.2;
    private static final double TP_MULT = 2.0;
    private static final double REFERENCE_CAPITAL = 10_000;
    private static final double RISK_PCT = 0.01;
    private static final int MIN_HISTORY = Math.max(EMA_PERIOD, ATR_PERIOD) + 5;

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
    private static final int COOLDOWN_BARS = 2;

    // --- Constructors ---
    public SeasonalMeanReversion() {
        this("SeasonalMeanReversion", "USDCAD");
    }

    public SeasonalMeanReversion(String name) {
        this(name, "USDCAD");
    }

    public SeasonalMeanReversion(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;

        // Look-ahead safe: compute on closed history, then add current bar
        if (history.size() < MIN_HISTORY - 1) {
            history.add(bar);
            return;
        }

        double ema100 = Indicators.emaLatest(history, EMA_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);
        history.add(bar);

        if (Double.isNaN(ema100) || Double.isNaN(atr) || atr <= 0) return;

        // Daily trade limit
        int barDay = bar.timestamp().atZone(ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) {
            tradesToday = 0;
            lastTradeDay = barDay;
        }

        if (inTrade) {
            managePosition(bar);
        } else {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 1) return;
            evaluateEntry(bar, ema100, atr);
        }
    }

    /**
     * Determine seasonal bias for USDCAD based on 21-year research.
     * - Oct 12 → Nov 26: BUY (94% hit rate!)
     * - April: SELL (72% hit rate)
     */
    private Order.Side getSeasonalBias(Instant timestamp) {
        ZonedDateTime zdt = timestamp.atZone(ZoneId.of("America/New_York"));
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();

        if ((month == 10 && day >= 12) || (month == 11 && day <= 26)) return Order.Side.BUY;
        if (month == 4) return Order.Side.SELL;
        return null; // neutral → no trade outside seasonal windows
    }

    private void evaluateEntry(Bar bar, double ema100, double atr) {
        double close = bar.close();

        // Seasonality bias — determines direction AND whether we trade at all
        Order.Side bias = getSeasonalBias(bar.timestamp());
        if (bias == null) return; // Outside seasonal window — no trade

        // BUY window (Oct-Nov): fade dips below EMA(100) - ATR
        if (bias == Order.Side.BUY && close < ema100 - atr * DEVIATION_MULT) {
            entryPrice = close;
            stopLoss = entryPrice - atr * SL_MULT;
            takeProfit = entryPrice + atr * TP_MULT;
            double qty = Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_MULT, symbol);
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, qty, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            tradesToday++;
        }
        // SELL window (April): fade rallies above EMA(100) + ATR
        else if (bias == Order.Side.SELL && close > ema100 + atr * DEVIATION_MULT) {
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
