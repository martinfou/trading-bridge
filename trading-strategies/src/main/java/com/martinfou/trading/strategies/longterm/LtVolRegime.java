package com.martinfou.trading.strategies.longterm;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;

import java.util.*;

/**
 * LtVolRegime — Volatility Regime Trading (Strategy #7)
 *
 * 📊 Inspiration: Volatility regime switching (e.g. ATR ratio).
 *    Uses the ratio of ATR(14) to ATR(100) to identify high/low volatility regimes.
 *
 * 🔧 Mechanism:
 *    - ATR ratio (14/100) > 1.5 → high vol regime → trend follow:
 *        price > EMA(50) → BUY, price < EMA(50) → SELL
 *    - ATR ratio < 0.5 → low vol regime → mean revert:
 *        RSI(14) < 30 → BUY, RSI(14) > 70 → SELL
 *    - Normal regime (0.5 ≤ ratio ≤ 1.5) → no trades
 *    - SL = 2x ATR(14), TP = 4x ATR(14)
 *    - Max 1 trade per day
 *    - closeOnly() on exits
 */
public class LtVolRegime implements Strategy {

    private static final int ATR_SHORT = 14;
    private static final int ATR_LONG = 100;
    private static final int EMA_PERIOD = 50;
    private static final int RSI_PERIOD = 14;
    private static final double HIGH_VOL_THRESHOLD = 1.5;
    private static final double LOW_VOL_THRESHOLD = 0.5;
    private static final double SL_MULT = 2.0;
    private static final double TP_MULT = 4.0;
    private static final double REFERENCE_CAPITAL = 10_000;
    private static final double RISK_PCT = 0.01;
    private static final int MIN_HISTORY = ATR_LONG + 5;

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int lastTradeDayOfYear = -1;

    public LtVolRegime(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    public LtVolRegime() {
        this("LtVolRegime", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        // Manage existing position
        if (inTrade) {
            managePosition(bar);
            return;
        }

        // Check max 1 trade per day
        int dayOfYear = bar.timestamp().atZone(java.time.ZoneOffset.UTC).getDayOfYear();
        if (dayOfYear == lastTradeDayOfYear) return;

        // Calculate indicators
        double atrShort = Indicators.atr(history, ATR_SHORT);
        double atrLong = Indicators.atr(history, ATR_LONG);
        double ema50 = Indicators.emaLatest(history, EMA_PERIOD);
        double rsi = Indicators.rsi(history, RSI_PERIOD);

        if (Double.isNaN(atrShort) || Double.isNaN(atrLong)
            || Double.isNaN(ema50) || Double.isNaN(rsi)
            || atrLong <= 0) {
            return;
        }

        double atrRatio = atrShort / atrLong;

        if (atrRatio > HIGH_VOL_THRESHOLD) {
            // High volatility regime → trend follow
            double price = bar.open();
            if (price > ema50) {
                // Uptrend → BUY
                entryPrice = price;
                stopLoss = price - atrShort * SL_MULT;
                takeProfit = price + atrShort * TP_MULT;
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atrShort, SL_MULT, symbol), price));
                inTrade = true;
                tradeDirection = Order.Side.BUY;
                lastTradeDayOfYear = dayOfYear;
            } else if (price < ema50) {
                // Downtrend → SELL
                entryPrice = price;
                stopLoss = price + atrShort * SL_MULT;
                takeProfit = price - atrShort * TP_MULT;
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atrShort, SL_MULT, symbol), price));
                inTrade = true;
                tradeDirection = Order.Side.SELL;
                lastTradeDayOfYear = dayOfYear;
            }
        } else if (atrRatio < LOW_VOL_THRESHOLD) {
            // Low volatility regime → mean reversion
            double price = bar.open();
            if (rsi < 30) {
                // Oversold → BUY
                entryPrice = price;
                stopLoss = price - atrShort * SL_MULT;
                takeProfit = price + atrShort * TP_MULT;
                pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atrShort, SL_MULT, symbol), price));
                inTrade = true;
                tradeDirection = Order.Side.BUY;
                lastTradeDayOfYear = dayOfYear;
            } else if (rsi > 70) {
                // Overbought → SELL
                entryPrice = price;
                stopLoss = price + atrShort * SL_MULT;
                takeProfit = price - atrShort * TP_MULT;
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atrShort, SL_MULT, symbol), price));
                inTrade = true;
                tradeDirection = Order.Side.SELL;
                lastTradeDayOfYear = dayOfYear;
            }
        }
        // Normal regime (0.5 ≤ ratio ≤ 1.5) → no trades
    }

    private void managePosition(Bar bar) {
        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);
        boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= takeProfit)
            || (tradeDirection == Order.Side.SELL && bar.low() <= takeProfit);

        if (stopHit || tpHit) {
            closePosition(bar.close());
        }
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, 1000, price).closeOnly());
        inTrade = false;
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
        lastTradeDayOfYear = -1;
    }
}
