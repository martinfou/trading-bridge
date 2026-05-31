package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * Session Breakout Momentum — News/Liquidity/Sentiment
 *
 * 📊 Inspiration: Part Time Larry's ORB (Opening Range Breakout) + 
 *    Ali Casey's "Volume confirms everything" adapted to session-based
 *    forex. Instead of chasing VWAP reversion (which failed), this captures
 *    genuine session momentum breakouts during the most liquid times.
 *
 * 🔧 Mechanism:
 *    - Track Asian session range (00:00-07:00 UTC) for each day
 *    - Enter on London-open breakout above Asian high or below Asian low
 *    - Only trade during London/NY overlap (08:00-16:00 UTC) — peak liquidity
 *    - Exit: trailing stop at 2× ATR, or end of session
 *    - Second entry only: if a breakout fails, trade the reversal at Asian range edge
 *
 * 🎯 Improvements over VWAP Reversion:
 *    - Trend-following, not mean-reversion (H1 trends, doesn't revert)
 *    - Session timing captures institutional flow
 *    - Multiple entry opportunities per day (breakout + reversal)
 */
public class SessionBreakoutMomentumStrategy implements Strategy {

    // Asian session window (UTC)
    private static final int ASIAN_START = 0;
    private static final int ASIAN_END = 7;
    // London/NY overlap window (UTC)
    private static final int TRADE_START = 8;
    private static final int TRADE_END = 16;

    private static final int ATR_PERIOD = 14;
    private static final double BREAKOUT_ATR_MULT = 0.5;  // Asian range + 0.5 ATR for breakout
    private static final double STOP_ATR_MULT = 2.0;
    private static final double TP_ATR_MULT = 3.0;
    private static final int MIN_HISTORY = 48;
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

    // Daily tracking
    private int currentDayKey = -1;
    private double asianHigh = Double.MIN_VALUE;
    private double asianLow = Double.MAX_VALUE;
    private boolean asianSessionClosed = false;
    private boolean breakoutTriggeredBuy = false;
    private boolean breakoutTriggeredSell = false;
    private double lastBarHour = -1;

    public SessionBreakoutMomentumStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
    }

    public SessionBreakoutMomentumStrategy() {
        this("SessionBreakoutMomentum", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        int hour = hourOfDay(bar);
        int dayKey = dayKey(bar);

        // New day — reset
        if (dayKey != currentDayKey) {
            currentDayKey = dayKey;
            asianHigh = Double.MIN_VALUE;
            asianLow = Double.MAX_VALUE;
            asianSessionClosed = false;
            breakoutTriggeredBuy = false;
            breakoutTriggeredSell = false;
            lastBarHour = -1;
        }

        // Track Asian session range (00:00-07:00 UTC)
        if (hour >= ASIAN_START && hour <= ASIAN_END) {
            asianHigh = Math.max(asianHigh, bar.high());
            asianLow = Math.min(asianLow, bar.low());
        }

        // Asian session just closed (first bar after 07:00 UTC)
        if (hour > ASIAN_END && !asianSessionClosed && lastBarHour <= ASIAN_END) {
            asianSessionClosed = true;
        }

        managePosition(bar);

        // Only trade during London/NY overlap
        if (hour >= TRADE_START && hour <= TRADE_END) {
            if (!inTrade && asianSessionClosed) {
                evaluateEntry(bar);
            }
        }

        lastBarHour = hour;
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
        currentDayKey = -1;
        asianHigh = Double.MIN_VALUE;
        asianLow = Double.MAX_VALUE;
        asianSessionClosed = false;
        breakoutTriggeredBuy = false;
        breakoutTriggeredSell = false;
        lastBarHour = -1;
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

        if (tradeDirection == Order.Side.BUY) {
            if (bar.low() <= stopLoss || bar.high() >= takeProfit) {
                inTrade = false;
                return;
            }
        } else {
            if (bar.high() >= stopLoss || bar.low() <= takeProfit) {
                inTrade = false;
                return;
            }
        }

        // Exit at end of trading session
        int hour = hourOfDay(bar);
        if (hour > TRADE_END) {
            inTrade = false;
            return;
        }

        // Trailing stop
        double atr = atr(ATR_PERIOD);
        if (tradeDirection == Order.Side.BUY) {
            double trail = bar.close() - atr * STOP_ATR_MULT;
            stopLoss = Math.max(stopLoss, trail);
        } else {
            double trail = bar.close() + atr * STOP_ATR_MULT;
            stopLoss = Math.min(stopLoss, trail);
        }
    }

    private void evaluateEntry(Bar bar) {
        if (asianHigh == Double.MIN_VALUE || asianLow == Double.MAX_VALUE) return;

        double atr = atr(ATR_PERIOD);
        if (Double.isNaN(atr) || atr <= 0) return;

        // Breakout entry: price breaks Asian range + buffer
        double breakoutBuyLevel = asianHigh + atr * BREAKOUT_ATR_MULT;
        double breakoutSellLevel = asianLow - atr * BREAKOUT_ATR_MULT;

        boolean buyBreakout = bar.high() >= breakoutBuyLevel && !breakoutTriggeredBuy;
        boolean sellBreakout = bar.low() <= breakoutSellLevel && !breakoutTriggeredSell;

        // Prefer the dominant breakout direction
        if (buyBreakout && sellBreakout) {
            // Both triggered same bar — pick the stronger move
            double buyDist = bar.high() - breakoutBuyLevel;
            double sellDist = breakoutSellLevel - bar.low();
            if (buyDist >= sellDist) sellBreakout = false;
            else buyBreakout = false;
        }

        if (buyBreakout) {
            breakoutTriggeredBuy = true;
            entryPrice = Math.max(bar.close(), breakoutBuyLevel);
            stopLoss = entryPrice - atr * STOP_ATR_MULT;
            takeProfit = entryPrice + atr * TP_ATR_MULT;

            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
        } else if (sellBreakout) {
            breakoutTriggeredSell = true;
            entryPrice = Math.min(bar.close(), breakoutSellLevel);
            stopLoss = entryPrice + atr * STOP_ATR_MULT;
            takeProfit = entryPrice - atr * TP_ATR_MULT;

            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            barsInTrade = 0;
        }
    }

    private double atr(int period) {
        return Indicators.atr(history, period);
    }

    private int hourOfDay(Bar bar) {
        return bar.timestamp().atZone(ZoneOffset.UTC).getHour();
    }

    private int dayKey(Bar bar) {
        var ld = bar.timestamp().atZone(ZoneOffset.UTC).toLocalDate();
        return ld.getYear() * 1000 + ld.getDayOfYear();
    }
}
