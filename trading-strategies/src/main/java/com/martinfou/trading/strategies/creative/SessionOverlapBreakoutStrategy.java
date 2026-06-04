package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * SessionOverlapBreakout — News/Sentiment inspired
 *
 * 📊 Inspiration: forex-soft-signals (Axia Futures, VWAP Daddy), forex-news-trading
 *    (London-NY overlap is highest institutional activity period).
 *    The London-NY overlap (13:00-16:00 UTC) handles the bulk of daily volume.
 *    Breakouts during this window with volume confirmation are more reliable.
 *
 * 🔧 Mechanism:
 *    - Track Asian session range (00:00-08:00 UTC)
 *    - Track Asian session volume average
 *    - During London-NY overlap (13:00-16:00 UTC), enter when price breaks
 *      the Asian range high/low with 1.5× avg volume confirmation
 *    - Exit: ATR trailing stop at 1.5×, max 6 bars hold
 *
 * 🎯 Novelty: Combines session range breakout + volume confirmation during
 *    highest-liquidity overlap. None of the existing creative strategies use
 *    this exact session-overlap + volume filter combination.
 */
public class SessionOverlapBreakoutStrategy implements Strategy {

    private static final int ATR_PERIOD = 14;
    private static final double ATR_SL_MULT = 1.5;
    private static final double RR_TARGET = 2.0;
    private static final int MAX_BARS_HOLD = 6;
    private static final int MIN_HISTORY = 20;
    private static final int COOLDOWN_BARS = 8;
    private static final double VOLUME_THRESHOLD = 1.5;
    private static final double MIN_POSITION = 1000;

    // Session boundaries (UTC)
    private static final int ASIA_START = 0;
    private static final int ASIA_END = 8;
    private static final int OVERLAP_START = 13;
    private static final int OVERLAP_END = 16;

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private final ZoneOffset tz = ZoneOffset.UTC;

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int barsInTrade;
    private double highestSinceEntry;
    private double lowestSinceEntry;
    private int cooldownBars;
    private double positionSize = MIN_POSITION;

    // Asian session state tracking
    private double asianHigh = Double.NaN;
    private double asianLow = Double.NaN;
    private double asianVolumeSum = 0;
    private int asianBarCount = 0;
    private boolean collectingAsian = true;
    private int lastAsianDay = -1;

    public SessionOverlapBreakoutStrategy() {
        this("SessionOverlapBreakout", "EUR/USD");
    }

    public SessionOverlapBreakoutStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        ZonedDateTime zdt = bar.timestamp().atZone(tz);
        int hour = zdt.getHour();
        int day = zdt.getDayOfYear();

        // Reset Asian session tracking at new day
        if (day != lastAsianDay) {
            resetAsianSession();
            lastAsianDay = day;
        }

        // Collect Asian session data (00:00-08:00 UTC)
        if (hour >= ASIA_START && hour < ASIA_END) {
            if (Double.isNaN(asianHigh) || bar.high() > asianHigh) asianHigh = bar.high();
            if (Double.isNaN(asianLow) || bar.low() < asianLow) asianLow = bar.low();
            asianVolumeSum += bar.volume();
            asianBarCount++;
            collectingAsian = true;
            return;
        }

        // If we were collecting Asian and now it's past, finalize
        if (collectingAsian && hour >= ASIA_END) {
            collectingAsian = false;
        }

        if (inTrade) {
            managePosition(bar);
            return;
        }

        if (cooldownBars > 0) {
            cooldownBars--;
            return;
        }

        // Only trade during London-NY overlap
        if (hour < OVERLAP_START || hour >= OVERLAP_END) return;

        // Need Asian session data
        if (Double.isNaN(asianHigh) || Double.isNaN(asianLow) || asianBarCount < 4) return;

        double avgAsianVolume = asianVolumeSum / Math.max(1, asianBarCount);
        double atr = Indicators.atr(history, ATR_PERIOD);
        if (Double.isNaN(atr) || atr <= 0) return;

        double volumeRatio = avgAsianVolume > 0 ? bar.volume() / avgAsianVolume : 1.0;
        double close = bar.close();

        // Breakout above Asian high with volume confirmation
        if (close > asianHigh && volumeRatio >= VOLUME_THRESHOLD) {
            entryPrice = close;
            stopLoss = close - atr * ATR_SL_MULT;
            takeProfit = close + atr * ATR_SL_MULT * RR_TARGET;
            highestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice));
            enterTrade(Order.Side.BUY);
        }
        // Breakout below Asian low with volume confirmation
        else if (close < asianLow && volumeRatio >= VOLUME_THRESHOLD) {
            entryPrice = close;
            stopLoss = close + atr * ATR_SL_MULT;
            takeProfit = close - atr * ATR_SL_MULT * RR_TARGET;
            lowestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice));
            enterTrade(Order.Side.SELL);
        }
    }

    private void enterTrade(Order.Side side) {
        inTrade = true;
        tradeDirection = side;
        barsInTrade = 0;
        cooldownBars = 0;
    }

    private void managePosition(Bar bar) {
        barsInTrade++;

        if (tradeDirection == Order.Side.BUY) {
            highestSinceEntry = Math.max(highestSinceEntry, bar.high());
        } else {
            lowestSinceEntry = Math.min(lowestSinceEntry, bar.low());
        }

        boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= stopLoss)
            || (tradeDirection == Order.Side.SELL && bar.high() >= stopLoss);
        boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= takeProfit)
            || (tradeDirection == Order.Side.SELL && bar.low() <= takeProfit);

        if (stopHit || tpHit || barsInTrade >= MAX_BARS_HOLD) {
            closePosition(bar.close());
            return;
        }

        // Trailing stop
        double atr = Indicators.atr(history, ATR_PERIOD);
        if (!Double.isNaN(atr)) {
            if (tradeDirection == Order.Side.BUY) {
                double trail = highestSinceEntry - atr * ATR_SL_MULT;
                stopLoss = Math.max(stopLoss, trail);
                if (bar.low() <= stopLoss) { closePosition(bar.close()); return; }
            } else {
                double trail = lowestSinceEntry + atr * ATR_SL_MULT;
                stopLoss = Math.min(stopLoss, trail);
                if (bar.high() >= stopLoss) { closePosition(bar.close()); return; }
            }
        }
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, positionSize, price).closeOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }

    private void resetAsianSession() {
        asianHigh = Double.NaN;
        asianLow = Double.NaN;
        asianVolumeSum = 0;
        asianBarCount = 0;
        collectingAsian = true;
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
        cooldownBars = 0;
        resetAsianSession();
        lastAsianDay = -1;
    }
}
