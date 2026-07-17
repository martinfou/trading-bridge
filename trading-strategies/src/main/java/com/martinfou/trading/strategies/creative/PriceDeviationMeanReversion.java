package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * PriceDeviationMeanReversion — SMA %-deviation mean reversion with seasonality (H1)
 *
 * 📊 Concept: When price deviates > 0.15% from SMA(50) on H1, it represents
 *    a short-term overextension that tends to revert. Unlike BB which uses
 *    standard deviation (sensitive to volatility changes), %-distance from
 *    SMA provides a stable, intuitive threshold. Seasonality filter aligns
 *    entries with directional bias (USDCAD Apr=SELL, Oct-Nov=BUY).
 *
 * 🔧 Mechanism:
 *    - SMA(50) midpoint — stable reference
 *    - 0.15% deviation threshold (~20 pips for USDCAD)
 *    - ATR(14): dynamic SL/TP sizing (1.2× / 2.0×)
 *    - Inline seasonality filter
 *    - No trend filter — pure deviation detection + seasonality
 *    - Manual SL/TP via manageExit(), closeOnly() exit
 *    - Max 1 trade/day
 *
 * 🎯 Target: 60-120 trades/year, Sharpe > 0.8, PF > 1.3
 */
public class PriceDeviationMeanReversion implements Strategy {

    // --- Parameters ---
    private static final int SMA_PERIOD = 50;
    private static final int ATR_PERIOD = 14;
    private static final double DEVIATION_PCT = 0.0015;  // 0.15% deviation threshold (~20 pips for USDCAD)
    private static final double SL_MULT = 1.2;
    private static final double TP_MULT = 2.0;
    private static final double REFERENCE_CAPITAL = 10_000;
    private static final double RISK_PCT = 0.01;
    private static final int MIN_HISTORY = Math.max(SMA_PERIOD, ATR_PERIOD) + 5;

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
    public PriceDeviationMeanReversion() {
        this("PriceDeviationMeanReversion", "USDCAD");
    }

    public PriceDeviationMeanReversion(String name) {
        this(name, "USDCAD");
    }

    public PriceDeviationMeanReversion(String name, String symbol) {
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

        double sma50 = Indicators.smaLatest(history, SMA_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);
        history.add(bar);

        if (Double.isNaN(sma50) || Double.isNaN(atr) || atr <= 0 || sma50 <= 0) return;

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
            evaluateEntry(bar, sma50, atr);
        }
    }

    /**
     * Inline seasonality filter for USDCAD based on 21-year research.
     * - Oct 12 → Nov 26: BUY (94% hit rate)
     * - April: SELL (72% hit rate)
     */
    private Order.Side getSeasonalBias(Instant timestamp) {
        ZonedDateTime zdt = timestamp.atZone(ZoneId.of("America/New_York"));
        int month = zdt.getMonthValue();
        int day = zdt.getDayOfMonth();

        if ((month == 10 && day >= 12) || (month == 11 && day <= 26)) return Order.Side.BUY;
        if (month == 4) return Order.Side.SELL;
        return null; // neutral
    }

    private void evaluateEntry(Bar bar, double sma50, double atr) {
        double close = bar.close();
        double deviation = Math.abs(close - sma50) / sma50;

        // Must exceed deviation threshold
        if (deviation < DEVIATION_PCT) return;

        // Seasonality filter
        Order.Side bias = getSeasonalBias(bar.timestamp());

        // --- BUY: price far below SMA (dip) ---
        if (close < sma50) {
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
        // --- SELL: price far above SMA (spike) ---
        else if (close > sma50) {
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
