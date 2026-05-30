package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * False Breakout Reversal Strategy — Fakeout Detection & Counter-Trend
 *
 * 📊 Concept: False breakouts (fakeouts) occur when price briefly moves
 *    beyond a recent high/low but fails to sustain the move and reverses
 *    back inside the range. This strategy detects these fakeouts by
 *    looking for a bar that exceeds the previous N-bar range, but the
 *    FOLLOWING bar reverses back inside — the classic "spring" or
 *    "upthrust" pattern from Wyckoff theory.
 *
 * 🔧 Mechanism:
 *    - Track the highest high and lowest low over the last 8 bars (range)
 *    - Detect a "failed breakout": a bar that breaks the range but closes
 *      back inside it (long upper wick above range, body inside)
 *    - On the NEXT bar, enter in the REVERSAL direction
 *    - SL = beyond the false breakout extreme, TP = 2.0× ATR
 *
 * 🎯 Originality: This is a pure false-breakout / Wyckoff spring pattern
 *    strategy. No existing strategy detects failed breakouts with a
 *    confirmation bar. InsideBarBreakout enters on breakouts (not fakeouts),
 *    OpeningRangeBreakout enters on session breakouts, DonchianChannelBreakout
 *    is a trend-following breakout. This is counter-trend breakout failure.
 */
public class FalseBreakoutReversalStrategy implements Strategy {
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

    // State machine for fakeout detection
    private enum FakeoutState { NONE, BREACH_DETECTED, CONFIRMED }
    private FakeoutState state = FakeoutState.NONE;
    private double breachHigh = 0;
    private double breachLow = 0;
    private Order.Side breachSide = Order.Side.BUY;

    private static final int RANGE_LOOKBACK = 8;
    private static final int ATR_PERIOD = 14;
    private static final double SL_ATR = 1.5;
    private static final double TP_ATR = 2.0;
    private static final int MAX_BARS_HOLD = 8;
    private static final int COOLDOWN_BARS = 8;
    private static final int MAX_TRADES_PER_DAY = 3;
    private static final int MIN_HISTORY = 15;

    public FalseBreakoutReversalStrategy() { this("🎯 False Break Rev", "XAU/USD"); }
    public FalseBreakoutReversalStrategy(String name) { this(name, "XAU/USD"); }
    public FalseBreakoutReversalStrategy(String name, String symbol) { this.name = name; this.symbol = symbol; }

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

        int size = history.size();
        Bar prev = history.get(size - 2);

        // Compute range over last N bars (excluding the latest two which might be the fakeout)
        double rangeHigh = Double.MIN_VALUE;
        double rangeLow = Double.MAX_VALUE;
        for (int i = Math.max(0, size - RANGE_LOOKBACK - 2); i < size - 2; i++) {
            if (history.get(i).high() > rangeHigh) rangeHigh = history.get(i).high();
            if (history.get(i).low() < rangeLow) rangeLow = history.get(i).low();
        }

        if (rangeHigh == Double.MIN_VALUE || rangeLow == Double.MAX_VALUE) return;

        // State machine
        switch (state) {
            case NONE -> {
                // Check if the PREVIOUS bar (just completed) was a potential false breakout
                // It must breach the range but close back inside
                boolean breachedUp = prev.high() > rangeHigh;
                boolean breachedDown = prev.low() < rangeLow;
                boolean closedInside = prev.close() < rangeHigh && prev.close() > rangeLow;

                if (breachedUp && closedInside && !breachedDown) {
                    // Upward false breakout: price spiked above range, closed inside → bearish
                    state = FakeoutState.BREACH_DETECTED;
                    breachHigh = prev.high();
                    breachLow = prev.low();
                    breachSide = Order.Side.SELL;
                } else if (breachedDown && closedInside && !breachedUp) {
                    // Downward false breakout: price spiked below range, closed inside → bullish
                    state = FakeoutState.BREACH_DETECTED;
                    breachHigh = prev.high();
                    breachLow = prev.low();
                    breachSide = Order.Side.BUY;
                }
            }
            case BREACH_DETECTED -> {
                // Current bar is the confirmation bar
                // For fakeout UP (breachSide = SELL): confirm that bar continues lower
                if (breachSide == Order.Side.SELL && bar.close() < breachLow && bar.low() < breachLow) {
                    // Confirmed: enter SELL
                    entryPrice = bar.close();
                    entryStop = entryPrice + atr * SL_ATR;
                    entryTarget = entryPrice - atr * TP_ATR;
                    pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                        .withStopLoss(entryStop).withTakeProfit(entryTarget));
                    inTrade = true; tradeDirection = Order.Side.SELL; barsHeld = 0;
                    cooldownBars = 0; tradesToday++; state = FakeoutState.NONE;
                }
                // For fakeout DOWN (breachSide = BUY): confirm that bar continues higher
                else if (breachSide == Order.Side.BUY && bar.close() > breachHigh && bar.high() > breachHigh) {
                    // Confirmed: enter BUY
                    entryPrice = bar.close();
                    entryStop = entryPrice - atr * SL_ATR;
                    entryTarget = entryPrice + atr * TP_ATR;
                    pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                        .withStopLoss(entryStop).withTakeProfit(entryTarget));
                    inTrade = true; tradeDirection = Order.Side.BUY; barsHeld = 0;
                    cooldownBars = 0; tradesToday++; state = FakeoutState.NONE;
                } else {
                    // Not confirmed — reset
                    state = FakeoutState.NONE;
                }
            }
            case CONFIRMED -> {
                // Shouldn't get here as we reset on entry
                state = FakeoutState.NONE;
            }
        }
    }

    private void closePosition(double price) {
        pending.add(new Order(symbol, tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
            Order.Type.MARKET, positionSize, price));
        inTrade = false; cooldownBars = COOLDOWN_BARS; state = FakeoutState.NONE;
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

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending); pending.clear(); return copy;
    }
    @Override public void reset() {
        history.clear(); pending.clear(); inTrade = false; tradeDirection = Order.Side.BUY;
        entryPrice = 0; barsHeld = 0; entryStop = 0; entryTarget = 0;
        cooldownBars = 0; tradesToday = 0; lastTradeDay = -1;
        state = FakeoutState.NONE; breachHigh = 0; breachLow = 0;
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
