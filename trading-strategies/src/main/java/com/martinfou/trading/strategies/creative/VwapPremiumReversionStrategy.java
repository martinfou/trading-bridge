package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * VWAP Premium Reversion Strategy — News/Sentiment
 *
 * 📊 Inspiration: forex-soft-signals VWAP premium/discount methodology
 *    + forex-sentiment COT contrarian thinking. Trades when price
 *    deviates excessively from a rolling VWAP (Volume-Weighted Average
 *    Price), fading the extreme move. The idea: when price strays too
 *    far from its "fair value" (VWAP), it tends to revert.
 *
 * 🔧 Mechanism:
 *    - Compute rolling VWAP over 24 periods (1 trading day on H1)
 *    - Entry: price > VWAP + 1.5× ATR = overbought → SELL
 *            price < VWAP - 1.5× ATR = oversold → BUY
 *    - Exit: touch VWAP, or ATR stop, or max 8 bars
 *    - Filter: only trade during London/NY overlap (8-16 UTC, high liquidity)
 *    - Session strength filter: avoid fading strong trend days
 *
 * 🎯 Originality: Combines VWAP premium/discount mean reversion with
 *    session-based volume filter. Unlike static Bollinger Band strategies,
 *    VWAP adapts to intraday volume and is widely used by institutional
 *    traders as a fair value reference. The 1.5 ATR deviation threshold
 *    catches statistically significant extremes.
 */
public class VwapPremiumReversionStrategy implements Strategy {

    private static final int VWAP_PERIOD = 24;
    private static final double DEVIATION_ATR_MULT = 1.5;
    private static final int ATR_PERIOD = 14;
    private static final double STOP_ATR_MULT = 1.8;
    private static final int MAX_BARS_HOLD = 8;
    private static final int MIN_HISTORY = 48;
    private static final double MIN_POSITION = 1000;

    // London session hours in UTC (8:00 is session start)
    private static final int LONDON_START = 7;  // 07 UTC = London open
    private static final int LONDON_END = 16;   // 16 UTC = NY close

    // Trend filter: don't fade if ADR > 2% (too strong a trend day)
    private static final double MAX_DAILY_RANGE_PCT = 0.015;

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

    // Daily high/low tracking
    private int currentDay = -1;
    private double dayLow = Double.MAX_VALUE;
    private double dayHigh = 0;

    public VwapPremiumReversionStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
    }

    public VwapPremiumReversionStrategy() {
        this("VwapPremiumReversion", "EUR_USD");
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        updateDailyRange(bar);
        managePosition(bar);

        if (!inTrade) {
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
        barsInTrade = 0;
        currentDay = -1;
        dayLow = Double.MAX_VALUE;
        dayHigh = 0;
    }

    private void updateDailyRange(Bar bar) {
        // Track daily high/low using day key
        int dk = dayKey(bar);
        if (dk != currentDay) {
            currentDay = dk;
            dayHigh = bar.high();
            dayLow = bar.low();
        } else {
            dayHigh = Math.max(dayHigh, bar.high());
            dayLow = Math.min(dayLow, bar.low());
        }
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

        if (tradeDirection == Order.Side.BUY) {
            if (bar.low() <= stopLoss || bar.high() >= takeProfit || barsInTrade >= MAX_BARS_HOLD) {
                inTrade = false;
                return;
            }
        } else {
            if (bar.high() >= stopLoss || bar.low() <= takeProfit || barsInTrade >= MAX_BARS_HOLD) {
                inTrade = false;
                return;
            }
        }
    }

    private void evaluateEntry(Bar bar) {
        // Session filter: only trade during London/NY overlap
        int hour = hourOfDay(bar);
        if (hour < LONDON_START || hour > LONDON_END) return;

        // Trend filter: skip if daily range is too large (strong trend day)
        double dayRangePct = (dayHigh - dayLow) / dayLow;
        if (dayRangePct > MAX_DAILY_RANGE_PCT) return;

        double vwap = computeVWAP();
        if (Double.isNaN(vwap)) return;

        double atr = Indicators.atr(history, ATR_PERIOD);
        if (Double.isNaN(atr) || atr <= 0) return;

        double deviation = (bar.close() - vwap) / atr;
        double pip = Indicators.pipSize(symbol);
        double buffer = Math.max(pip, atr * 0.3);

        // Oversold: price significantly below VWAP → BUY
        if (deviation < -DEVIATION_ATR_MULT) {
            entryPrice = bar.close();
            stopLoss = entryPrice - atr * STOP_ATR_MULT;
            takeProfit = vwap; // Reversion to VWAP
            // Ensure TP > entry + buffer
            if (takeProfit <= entryPrice + buffer) takeProfit = entryPrice + atr;

            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
        }
        // Overbought: price significantly above VWAP → SELL
        else if (deviation > DEVIATION_ATR_MULT) {
            entryPrice = bar.close();
            stopLoss = entryPrice + atr * STOP_ATR_MULT;
            takeProfit = vwap; // Reversion to VWAP
            // Ensure TP < entry - buffer
            if (takeProfit >= entryPrice - buffer) takeProfit = entryPrice - atr;

            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            barsInTrade = 0;
        }
    }

    /** Rolling VWAP over VWAP_PERIOD bars. */
    private double computeVWAP() {
        if (history.size() < VWAP_PERIOD) return Double.NaN;
        int end = history.size() - 1;

        double sumPv = 0;
        long sumVol = 0;

        for (int i = end - VWAP_PERIOD + 1; i <= end; i++) {
            Bar b = history.get(i);
            double typPrice = (b.high() + b.low() + b.close()) / 3.0;
            long vol = Math.max(b.volume(), 1);
            sumPv += typPrice * vol;
            sumVol += vol;
        }

        return sumVol > 0 ? sumPv / sumVol : Double.NaN;
    }

    private int hourOfDay(Bar bar) {
        return bar.timestamp().atZone(java.time.ZoneOffset.UTC).getHour();
    }

    private int dayKey(Bar bar) {
        var ld = bar.timestamp().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return ld.getYear() * 1000 + ld.getDayOfYear();
    }
}
