package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * COT Sentiment Momentum Strategy v3 — Sentiment/COT
 *
 * 📊 Inspiration: Steven Goldstein's COT + retail overlay (trade WITH commercials,
 *    not against them), Williams COT Index momentum interpretation (high index
 *    confirms bullish tendency, not reversal trigger).
 *
 * 🔧 Mechanism:
 *    - Computes a composite "Sentiment Score" (0-100) every bar from:
 *      1. PRICE_POSITION (40%) — close in 50-bar H/L range
 *      2. RSI_MOMENTUM (35%) — RSI(14) mapped to 0-100
 *      3. VOLATILITY_CLIMAX (25%) — range expansion
 *    - Score > 55 → BULLISH regime (trade long)
 *    - Score < 45 → BEARISH regime (trade short)
 *    - Score 45-55 → NEUTRAL (no trades)
 *    - Entry: price must break OUTSIDE a 20-bar Donchian channel
 *      in the direction of sentiment. This filters noise:
 *      - Score bullish AND price > 20-bar high → BUY
 *      - Score bearish AND price < 20-bar low → SELL
 *    - Risk-based sizing: 2% of capital per trade, scaled by ATR
 *    - Exit: trailing ATR stop (2.0×) or max 20 bars
 *
 * 🎯 This aligns with how real COT traders operate: they don't fade positioning
 *    extremes; they enter when commercial positioning aligns with breakout.
 *    Targets ~50-150 trades/year per pair on H1.
 */
public class COTSentimentContrarianStrategy implements Strategy {

    private static final int MIN_HISTORY = 300;
    private static final int ATR_PERIOD = 14;
    private static final int RANGE_MEDIAN_BARS = 20;
    private static final double VOL_CLIMAX_MULT = 1.5;
    private static final int SENTIMENT_LOOKBACK = 50;
    private static final int DONCHIAN_PERIOD = 20;
    private static final double BULLISH_THRESHOLD = 55.0;
    private static final double BEARISH_THRESHOLD = 45.0;
    private static final double CAPITAL = 50_000.0;
    private static final double RISK_PER_TRADE = 0.02;
    private static final double ATR_STOP_MULT = 2.0;
    private static final double RR_TARGET = 1.5;
    private static final int MAX_BARS_HOLD = 20;
    private static final int COOLDOWN_BARS = 3;

    private static final double W_PRICE = 0.40;
    private static final double W_RSI = 0.35;
    private static final double W_VOL = 0.25;

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
    private double highestSinceEntry;
    private double lowestSinceEntry;
    private double positionSize;
    private int cooldownBars;
    private int tradesToday;
    private int lastTradeDay;

    public COTSentimentContrarianStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = 1000;
    }

    public COTSentimentContrarianStrategy() {
        this("COTSentimentContrarian", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        int barDay = bar.timestamp().atZone(java.time.ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        managePosition(bar);

        if (!inTrade) {
            if (cooldownBars > 0) { cooldownBars--; return; }
            if (tradesToday >= 2) return;
            evaluateEntry(bar);
        }
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
        inTrade = false;
        barsInTrade = 0;
        cooldownBars = 0;
        tradesToday = 0;
        lastTradeDay = -1;
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
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

        double atr = atr();
        if (!Double.isNaN(atr) && atr > 0) {
            if (tradeDirection == Order.Side.BUY) {
                double trail = highestSinceEntry - atr * ATR_STOP_MULT;
                stopLoss = Math.max(stopLoss, trail);
                if (bar.low() <= stopLoss) { closePosition(bar.close()); return; }
            } else {
                double trail = lowestSinceEntry + atr * ATR_STOP_MULT;
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

    private void evaluateEntry(Bar bar) {
        double sentimentScore = computeSentimentScore(bar);
        double atr = atr();
        if (Double.isNaN(atr) || atr <= 0) return;

        int end = history.size() - 1;

        // Donchian channel (20-bar high/low)
        double donchianHigh = Double.NEGATIVE_INFINITY;
        double donchianLow = Double.POSITIVE_INFINITY;
        int start = Math.max(0, end - DONCHIAN_PERIOD + 1);
        for (int i = start; i <= end; i++) {
            donchianHigh = Math.max(donchianHigh, history.get(i).high());
            donchianLow = Math.min(donchianLow, history.get(i).low());
        }

        double close = bar.close();

        if (sentimentScore > BULLISH_THRESHOLD && close >= donchianHigh) {
            // Bullish sentiment + breakout above Donchian high → BUY
            riskSizePosition(atr);
            doEntry(bar, Order.Side.BUY, atr);
        } else if (sentimentScore < BEARISH_THRESHOLD && close <= donchianLow) {
            // Bearish sentiment + breakout below Donchian low → SELL
            riskSizePosition(atr);
            doEntry(bar, Order.Side.SELL, atr);
        }
    }

    private void riskSizePosition(double atr) {
        double riskAmount = CAPITAL * RISK_PER_TRADE;
        double stopDistance = atr * ATR_STOP_MULT;
        double sizedPos = riskAmount / stopDistance;
        positionSize = Math.max(100, Math.min(5000, sizedPos));
    }

    private void doEntry(Bar bar, Order.Side direction, double atr) {
        entryPrice = bar.close();
        double stopDistance = atr * ATR_STOP_MULT;
        if (direction == Order.Side.BUY) {
            stopLoss = entryPrice - stopDistance;
            takeProfit = entryPrice + stopDistance * RR_TARGET;
            highestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
        } else {
            stopLoss = entryPrice + stopDistance;
            takeProfit = entryPrice - stopDistance * RR_TARGET;
            lowestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
        }
        inTrade = true;
        tradeDirection = direction;
        barsInTrade = 0;
        tradesToday++;
    }

    private double computeSentimentScore(Bar bar) {
        int end = history.size() - 1;
        double priceScore = computePricePositionScore(end);
        double rsiScore = computeRSIScore(end);
        double volScore = computeVolatilityScore(bar, end);
        double score = priceScore * W_PRICE + rsiScore * W_RSI + volScore * W_VOL;
        return Math.max(0, Math.min(100, score));
    }

    private double computePricePositionScore(int end) {
        int lookback = Math.min(SENTIMENT_LOOKBACK, end);
        if (lookback < 10) return 50.0;
        double highMax = Double.NEGATIVE_INFINITY;
        double lowMin = Double.POSITIVE_INFINITY;
        for (int i = end - lookback; i <= end; i++) {
            highMax = Math.max(highMax, history.get(i).high());
            lowMin = Math.min(lowMin, history.get(i).low());
        }
        double range = highMax - lowMin;
        if (range <= 0) return 50.0;
        return ((history.get(end).close() - lowMin) / range) * 100.0;
    }

    private double computeRSIScore(int end) {
        double rsi = Indicators.rsi(history, 14);
        if (Double.isNaN(rsi)) return 50.0;
        double mapped = (rsi - 50) * 1.5 + 50;
        return Math.max(0, Math.min(100, mapped));
    }

    private double computeVolatilityScore(Bar bar, int end) {
        double currentRange = bar.high() - bar.low();
        double medianRange = computeMedianRange();
        if (medianRange <= 0) return 50.0;
        double ratio = currentRange / medianRange;
        if (ratio < VOL_CLIMAX_MULT) return 50.0;
        double climaxIntensity = Math.min(ratio / 3.0, 1.0);
        return bar.close() > bar.open()
            ? 50.0 + climaxIntensity * 50.0
            : 50.0 - climaxIntensity * 50.0;
    }

    private double computeMedianRange() {
        int end = history.size() - 1;
        int lookback = Math.min(RANGE_MEDIAN_BARS, end);
        if (lookback < 3) return Double.MAX_VALUE;
        double[] ranges = new double[lookback];
        for (int i = 0; i < lookback; i++) {
            int idx = end - lookback + 1 + i;
            ranges[i] = history.get(idx).high() - history.get(idx).low();
        }
        Arrays.sort(ranges);
        return ranges[lookback / 2];
    }

    private double atr() { return Indicators.atr(history, ATR_PERIOD); }
}
