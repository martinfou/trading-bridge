package com.martinfou.trading.strategies.longterm;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * LtBollingerSqueeze — Bollinger Bandwidth Squeeze Breakout.
 *
 * Identifies periods of low volatility (bandwidth squeeze) and enters in the
 * direction of the breakout when price moves beyond the middle band.
 *
 * Entry:
 *   - Wait for Bollinger bandwidth (20,2) to compress below 0.05 (squeeze)
 *   - On the squeeze bar, direction = price > SMA(20) ? BUY : SELL
 *   - Enter at market on the next bar
 *   - SL = 2×ATR(14), TP = 4×ATR(14)
 *
 * Exit:
 *   - Stop loss or take profit hit
 *   - Bandwidth expands significantly (> 0.10), signalling the breakout is complete
 *   - Max 1 trade per calendar day
 *
 * Risk management:
 *   - ATR-based stops adapted to each asset's volatility
 *   - Fixed fractional position sizing based on ATR risk (calcRiskPosition)
 *   - closeOnly() on all exits
 *
 * Inspiration:
 *   - John Bollinger (Bollinger Bands, squeeze concept)
 *   - Alan Farley (short-term squeeze breakout setups)
 *   - Linda Raschke (volatility breakout patterns)
 *   - FTMO (strict risk management, max drawdown discipline)
 */
public class LtBollingerSqueeze implements Strategy {

    private static final int BB_PERIOD = 20;
    private static final double BB_MULT = 2.0;
    private static final double SQUEEZE_THRESHOLD = 0.05;
    private static final double EXPAND_THRESHOLD = 0.10;
    private static final int ATR_PERIOD = 14;
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
    private double entrySl = 0;
    private double entryTp = 0;
    private LocalDate lastTradeDate = null;

    public LtBollingerSqueeze() { this("LtBollingerSqueeze", "EUR_USD"); }
    public LtBollingerSqueeze(String name) { this(name, "EUR_USD"); }
    public LtBollingerSqueeze(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        int size = history.size();
        if (size < BB_PERIOD + ATR_PERIOD + 1) return;

        double[] bb = Indicators.bollingerWidth(history, BB_PERIOD, BB_MULT);
        double bandWidth = bb[2];
        double atr = Indicators.atr(history, ATR_PERIOD);
        double close = bar.close();
        LocalDate barDate = bar.timestamp().atZone(ZoneId.of("UTC")).toLocalDate();

        if (Double.isNaN(bandWidth) || Double.isNaN(atr) || atr == 0) return;

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
            // Exit when bandwidth expands significantly (squeeze breakout complete)
            if (bandWidth > EXPAND_THRESHOLD) {
                exitTrade(close);
                return;
            }
            return;
        }

        // Max 1 trade per day
        if (lastTradeDate != null && barDate.equals(lastTradeDate)) return;

        // Detect squeeze and breakout direction
        boolean isSqueeze = bandWidth < SQUEEZE_THRESHOLD;
        if (!isSqueeze) return;

        double sma = Indicators.smaLatest(history, BB_PERIOD);
        if (Double.isNaN(sma)) return;

        if (atr == 0) return;

        double entrySl = close - atr * ATR_SL_MULT;
        double entryTp = close + atr * ATR_TP_MULT;

        // Squeeze detected → enter on breakout direction
        if (close > sma) {
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, ATR_SL_MULT, symbol), close)
                .withStopLoss(entrySl).withTakeProfit(entryTp));
            direction = Order.Side.BUY; inTrade = true; lastTradeDate = barDate;
        }
        else if (close < sma) {
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, ATR_SL_MULT, symbol), close)
                .withStopLoss(entrySl).withTakeProfit(entryTp));
            direction = Order.Side.SELL; inTrade = true; lastTradeDate = barDate;
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
        entrySl = 0;
        entryTp = 0;
        lastTradeDate = null;
    }
}
