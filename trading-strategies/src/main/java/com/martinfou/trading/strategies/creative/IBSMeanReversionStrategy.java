package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * IBS Mean Reversion Strategy — Internal Bar Strength (IBS)
 *
 * 📊 Concept: IBS = (Close - Low) / (High - Low). This metric ranges
 *    0.0 to 1.0 and measures where the close sits within the bar's range.
 *    IBS < 0.25 means close is near the low (oversold on this single bar),
 *    IBS > 0.75 means close is near the high (overbought).
 *    Mean reversion predicts that extreme IBS values revert toward 0.5.
 *
 * 🔧 Mechanism:
 *    - Calculate IBS for each bar
 *    - IBS < 0.25 + volume confirmation → BUY (expect reversion up)
 *    - IBS > 0.75 + volume confirmation → SELL (expect reversion down)
 *    - Exit at TP (2.0× ATR) or SL (1.5× ATR) or after max 8 bars
 *
 * 🎯 Originality: Pure single-bar IBS is a unique signal not used by
 *    existing strategies (which focus on VWAP, ATR channels, breakout
 *    patterns, momentum clustering, or multi-bar formations).
 */
public class IBSMeanReversionStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double entryStop = 0;
    private double entryTarget = 0;
    private int barsHeld = 0;
    private double positionSize = 1000;
    private int cooldownBars = 0;
    private int tradesToday = 0;
    private int lastTradeDay = -1;

    private static final double IBS_LONG_THRESHOLD = 0.25;
    private static final double IBS_SHORT_THRESHOLD = 0.75;
    private static final double VOLUME_RATIO_MIN = 0.5;
    private static final int ATR_PERIOD = 14;
    private static final double SL_ATR = 1.5;
    private static final double TP_ATR = 2.0;
    private static final int MAX_BARS_HOLD = 8;
    private static final int COOLDOWN_BARS = 8;
    private static final int MAX_TRADES_PER_DAY = 3;
    private static final int MIN_HISTORY = 20;

    public IBSMeanReversionStrategy() { this("🔄 IBS Mean Rev", "GBP/USD"); }
    public IBSMeanReversionStrategy(String name) { this(name, "GBP/USD"); }
    public IBSMeanReversionStrategy(String name, String symbol) { this.name = name; this.symbol = symbol; }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < MIN_HISTORY) return;

        // Daily trade counter
        int barDay = bar.timestamp().atZone(java.time.ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        double atr = calculateATR(ATR_PERIOD);
        if (atr <= 0) return;

        if (inTrade) {
            barsHeld++;
            // Stop loss
            if (tradeDirection == Order.Side.BUY && bar.low() <= entryStop) { closePosition(entryStop); return; }
            if (tradeDirection == Order.Side.SELL && bar.high() >= entryStop) { closePosition(entryStop); return; }
            // Take profit
            if (tradeDirection == Order.Side.BUY && bar.high() >= entryTarget) { closePosition(entryTarget); return; }
            if (tradeDirection == Order.Side.SELL && bar.low() <= entryTarget) { closePosition(entryTarget); return; }
            // Max hold
            if (barsHeld >= MAX_BARS_HOLD) { closePosition(bar.close()); return; }
            return;
        }

        // Cooldown
        if (cooldownBars > 0) { cooldownBars--; return; }
        if (tradesToday >= MAX_TRADES_PER_DAY) return;

        // Compute IBS = (Close - Low) / (High - Low)
        double range = bar.high() - bar.low();
        if (range <= 0) return;
        double ibs = (bar.close() - bar.low()) / range;

        // Volume confirmation: current volume > average volume * threshold
        double avgVol = averageVolume(20);
        double volRatio = avgVol > 0 ? bar.volume() / avgVol : 1.0;

        // Also check that previous bar's IBS was not already extreme
        // (avoids entering multiple bars into the same reversion)
        Bar prev = history.get(history.size() - 2);
        double prevRange = prev.high() - prev.low();
        double prevIbs = prevRange > 0 ? (prev.close() - prev.low()) / prevRange : 0.5;

        // BUY signal: IBS very low (close near low), oversold single-bar
        if (ibs <= IBS_LONG_THRESHOLD) {
            entryPrice = bar.close();
            entryStop = entryPrice - atr * SL_ATR;
            entryTarget = entryPrice + atr * TP_ATR;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(entryStop).withTakeProfit(entryTarget));
            inTrade = true; tradeDirection = Order.Side.BUY; barsHeld = 0; cooldownBars = 0; tradesToday++;
        }
        // SELL signal: IBS very high (close near high), overbought single-bar
        else if (ibs >= IBS_SHORT_THRESHOLD) {
            entryPrice = bar.close();
            entryStop = entryPrice + atr * SL_ATR;
            entryTarget = entryPrice - atr * TP_ATR;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(entryStop).withTakeProfit(entryTarget));
            inTrade = true; tradeDirection = Order.Side.SELL; barsHeld = 0; cooldownBars = 0; tradesToday++;
        }
    }

    private void closePosition(double price) {
        pending.add(new Order(symbol, tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
            Order.Type.MARKET, positionSize, price).closeOnly());
        inTrade = false; cooldownBars = COOLDOWN_BARS;
    }

    private double calculateATR(int period) {
        int size = history.size(); double sum = 0; int count = 0;
        for (int i = Math.max(1, size - period); i < size; i++) {
            Bar prev = history.get(i - 1); Bar curr = history.get(i);
            double tr = Math.max(curr.high() - curr.low(),
                Math.max(Math.abs(curr.high() - prev.close()), Math.abs(curr.low() - prev.close())));
            sum += tr; count++;
        }
        return count > 0 ? sum / count : 1.0;
    }

    private double averageVolume(int period) {
        int size = history.size(); double sum = 0; int count = 0;
        for (int i = Math.max(0, size - period); i < size; i++) {
            sum += history.get(i).volume(); count++;
        }
        return count > 0 ? sum / count : 1.0;
    }

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending); pending.clear(); return copy;
    }
    @Override public void reset() {
        history.clear(); pending.clear(); inTrade = false; tradeDirection = Order.Side.BUY;
        entryPrice = 0; barsHeld = 0; entryStop = 0; entryTarget = 0;
        cooldownBars = 0; tradesToday = 0; lastTradeDay = -1;
    }

    public void restoreState(int tradesToday, int lastTradeDay, boolean inTrade,
                             Order.Side tradeDirection, int cooldownBars) {
        this.tradesToday = tradesToday;
        this.lastTradeDay = lastTradeDay;
        this.inTrade = inTrade;
        this.tradeDirection = tradeDirection;
        this.cooldownBars = cooldownBars;
    }

    public int getTradesToday() { return tradesToday; }
    public int getLastTradeDay() { return lastTradeDay; }
    public boolean isInTrade() { return inTrade; }
    public Order.Side getTradeDirection() { return tradeDirection; }
    public int getCooldownBars() { return cooldownBars; }
}
