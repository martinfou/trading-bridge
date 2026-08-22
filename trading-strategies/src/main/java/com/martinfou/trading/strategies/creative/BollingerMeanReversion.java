package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * BollingerMeanReversion — BB(20,2) extreme mean reversion with seasonality (H1)
 *
 * 📊 Concept: When USDCAD closes outside the 2σ Bollinger Band on H1, the market
 *    is statistically overextended. These extremes revert toward the middle band
 *    ~60% of the time within 2-6 bars. Pure BB touch → mean reversion, no
 *    additional trend filter. Seasonality alignment (USDCAD Apr=SELL 72%,
 *    Oct-Nov=BUY 94%) adds directional conviction.
 *
 * 🔧 Mechanism:
 *    - BB(20, 2.0): price outside 2σ bands = extreme oversold/overbought
 *    - ATR(14): dynamic SL/TP sizing (1.2× / 2.0×)
 *    - Inline seasonality filter (USDCAD patterns from 21-year research)
 *    - Manual SL/TP via manageExit(), closeOnly() exit
 *    - Max 1 trade/day
 *
 * 🔬 Backtest target: USDCAD H1, 2024, Sharpe > 0.8, PF > 1.3, > 30 trades
 */
public class BollingerMeanReversion implements Strategy {

    // --- WFO-optimizable parameters ---
    private static final int BB_PERIOD = 20;
    private static final double BB_MULT = 2.0;
    private static final int ATR_PERIOD = 14;
    private static final double SL_ATR_MULT = 1.2;
    private static final double TP_ATR_MULT = 2.0;
    private static final double REFERENCE_CAPITAL = 10_000;
    private static final double RISK_PCT = 0.01;
    private static final int MIN_HISTORY = Math.max(BB_PERIOD, ATR_PERIOD) + 5;

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
    public BollingerMeanReversion() {
        this("BollingerMeanReversion", "USDCAD");
    }

    public BollingerMeanReversion(String name) {
        this(name, "USDCAD");
    }

    public BollingerMeanReversion(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        // Symbol filter — ignore bars for other pairs
        if (!bar.symbol().equals(symbol)) return;

        // ⚠️ LOOK-AHEAD SAFE: compute indicators on CLOSED history first
        if (history.size() < MIN_HISTORY - 1) {
            history.add(bar);
            return;
        }

        // Compute indicators on closed bars only (no look-ahead)
        double[] bb = Indicators.bollingerWidth(history, BB_PERIOD, BB_MULT);
        double bbLower = bb[0];
        double bbUpper = bb[1];
        double atr = Indicators.atr(history, ATR_PERIOD);

        // Now add current bar for next iteration
        history.add(bar);

        // Validate indicators
        if (Double.isNaN(bbLower) || Double.isNaN(bbUpper) || Double.isNaN(atr) || atr <= 0) {
            return;
        }

        // Daily trade limit (NY timezone)
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
            evaluateEntry(bar, bbLower, bbUpper, atr);
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

        // USDCAD: Oct 12 → Nov 26 = BUY (94% hit rate!)
        if ((month == 10 && day >= 12) || (month == 11 && day <= 26)) {
            return Order.Side.BUY;
        }
        // USDCAD: April = SELL (72% hit rate)
        if (month == 4) {
            return Order.Side.SELL;
        }
        return null; // neutral
    }

    private void evaluateEntry(Bar bar, double bbLower, double bbUpper, double atr) {
        double close = bar.close();

        // Inline seasonality filter
        Order.Side seasonalBias = getSeasonalBias(bar.timestamp());

        // --- BUY signal: price below lower BB (oversold extreme) ---
        if (close < bbLower) {
            // Seasonality check
            if (seasonalBias == Order.Side.SELL) return; // seasonal veto

            entryPrice = close;
            stopLoss = entryPrice - atr * SL_ATR_MULT;
            takeProfit = entryPrice + atr * TP_ATR_MULT;
            double qty = Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_ATR_MULT, symbol);

            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, qty, entryPrice));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            tradesToday++;
        }
        // --- SELL signal: price above upper BB (overbought extreme) ---
        else if (close > bbUpper) {
            // Seasonality check
            if (seasonalBias == Order.Side.BUY) return; // seasonal veto

            entryPrice = close;
            stopLoss = entryPrice + atr * SL_ATR_MULT;
            takeProfit = entryPrice - atr * TP_ATR_MULT;
            double qty = Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, SL_ATR_MULT, symbol);

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
