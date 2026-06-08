package com.martinfou.trading.strategies.longterm;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * LtRSIMeanRev — RSI Extreme Mean Reversion with EMA(200) Trend Filter.
 *
 * Enters counter-trend when RSI(14) reaches extreme levels AND price is aligned
 * with the EMA(200) long-term trend:
 *   - RSI(14) < 25 (oversold) → BUY if price is ABOVE EMA(200) (bullish trend)
 *   - RSI(14) > 75 (overbought) → SELL if price is BELOW EMA(200) (bearish trend)
 *
 * Risk management:
 *   - SL = 2x ATR(14), TP = 4x ATR(14)
 *   - Max 1 trade per day (calendar day)
 *   - Exit when RSI returns to the 40-60 neutral zone
 *   - Close-only exits to avoid hedging on OANDA
 *
 * Inspiration:
 *   - Quantitative mean reversion / RSI-2 by Larry Connors
 *   - Trend filter via EMA(200) (long-term bias)
 *   - FTMO risk discipline (max 1 trade/day, ATR-based stops)
 */
public class LtRSIMeanRev implements Strategy {

    private static final int RSI_PERIOD = 14;
    private static final int EMA_PERIOD = 200;
    private static final int ATR_PERIOD = 14;
    private static final double RSI_OVERSOLD = 25.0;
    private static final double RSI_OVERBOUGHT = 75.0;
    private static final double RSI_EXIT_LOWER = 40.0;
    private static final double RSI_EXIT_UPPER = 60.0;
    private static final double ATR_SL_MULT = 2.0;
    private static final double ATR_TP_MULT = 4.0;
    private static final double REFERENCE_CAPITAL = 10_000;
    private static final double RISK_PCT = 0.01;

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side direction = Order.Side.BUY;
    private double entryPrice = 0;
    private double entrySl = 0;
    private double entryTp = 0;
    private String lastTradeDay = ""; // YYYY-MM-DD to enforce 1 trade/day

    public LtRSIMeanRev() { this("LtRSIMeanRev", "EUR_USD"); }
    public LtRSIMeanRev(String name) { this(name, "EUR_USD"); }
    public LtRSIMeanRev(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        int size = history.size();
        if (size < EMA_PERIOD + RSI_PERIOD + 1) return;

        double rsi = Indicators.rsi(history, RSI_PERIOD);
        double ema200 = Indicators.emaLatest(history, EMA_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);
        double close = bar.close();

        if (Double.isNaN(rsi) || Double.isNaN(ema200) || Double.isNaN(atr) || atr == 0) return;

        // Determine current trading day
        LocalDate barDate = bar.timestamp().atZone(ZoneId.of("UTC")).toLocalDate();
        String todayKey = barDate.toString();

        if (inTrade) {
            // Check stop loss
            if ((direction == Order.Side.BUY && close <= entrySl) ||
                (direction == Order.Side.SELL && close >= entrySl)) {
                exitTrade(close);
                return;
            }
            // Check take profit
            if ((direction == Order.Side.BUY && close >= entryTp) ||
                (direction == Order.Side.SELL && close <= entryTp)) {
                exitTrade(close);
                return;
            }
            // Exit when RSI returns to neutral zone (40-60)
            if (rsi >= RSI_EXIT_LOWER && rsi <= RSI_EXIT_UPPER) {
                exitTrade(close);
                return;
            }
            return;
        }

        // Max 1 trade per calendar day
        if (todayKey.equals(lastTradeDay)) return;

        // Entry conditions:
        // Oversold (RSI < 25) + price above EMA(200) = BUY (bullish trend, mean reversion up)
        if (rsi < RSI_OVERSOLD && close > ema200) {
            direction = Order.Side.BUY;
            entryPrice = close;
            entrySl = close - atr * ATR_SL_MULT;
            entryTp = close + atr * ATR_TP_MULT;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, ATR_SL_MULT, symbol), close)
                .withStopLoss(entrySl).withTakeProfit(entryTp));
            inTrade = true;
            lastTradeDay = todayKey;
        }
        // Overbought (RSI > 75) + price below EMA(200) = SELL (bearish trend, mean reversion down)
        else if (rsi > RSI_OVERBOUGHT && close < ema200) {
            direction = Order.Side.SELL;
            entryPrice = close;
            entrySl = close + atr * ATR_SL_MULT;
            entryTp = close - atr * ATR_TP_MULT;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, ATR_SL_MULT, symbol), close)
                .withStopLoss(entrySl).withTakeProfit(entryTp));
            inTrade = true;
            lastTradeDay = todayKey;
        }
    }

    @Override public void onTick(double bid, double ask, long volume) {}

    private void exitTrade(double price) {
        Order.Side exit = direction == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exit, Order.Type.MARKET, 1000, price).closeOnly());
        inTrade = false;
    }

    @Override
    public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        entryPrice = 0;
        entrySl = 0;
        entryTp = 0;
        lastTradeDay = "";
    }
}
