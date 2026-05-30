package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

/**
 * Momentum Divergence Strategy — Multi-timeframe Momentum Comparison
 *
 * 📊 Concept: Compares short-term momentum (5-bar rate of change) against
 *    medium-term momentum (21-bar rate of change). When short-term momentum
 *    diverges significantly FROM the medium-term direction, it signals
 *    exhaustion of the current move and a potential reversal.
 *
 *    Key insight: If price is rising but 5-bar ROC is DECLINING while
 *    21-bar ROC is still RISING, that's bearish divergence — momentum
 *    is rolling over before price does.
 *
 * 🔧 Mechanism:
 *    - Compute ROC(5) = % change over last 5 bars
 *    - Compute ROC(21) = % change over last 21 bars
 *    - Bullish setup: Price declining (ROC(21) < -threshold) but ROC(5)
 *      is rising (turning up before ROC(21)) → early re-entry signal
 *    - Bearish setup: Price rising (ROC(21) > threshold) but ROC(5)
 *      is declining (rolling over before ROC(21)) → early exit/fade signal
 *    - Trade IN THE DIRECTION of ROC(5) divergence from ROC(21)
 *
 * 🎯 Originality: This multi-timeframe momentum divergence approach is
 *    fundamentally different from existing strategies. No existing strategy
 *    uses comparative ROC divergence between two timeframes. RSI Divergence
 *    is price-vs-indicator, not momentum-vs-momentum.
 */
public class MomentumDivergenceStrategy implements Strategy {
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

    private static final int ROC_FAST = 5;
    private static final int ROC_SLOW = 21;
    private static final double DIVERGENCE_THRESHOLD = 0.003; // 0.3% minimum divergence
    private static final int ATR_PERIOD = 14;
    private static final double SL_ATR = 1.5;
    private static final double TP_ATR = 2.0;
    private static final int MAX_BARS_HOLD = 8;
    private static final int COOLDOWN_BARS = 8;
    private static final int MAX_TRADES_PER_DAY = 3;
    private static final int MIN_HISTORY = 30;

    public MomentumDivergenceStrategy() { this("⚡ Momentum Diverge", "EUR/USD"); }
    public MomentumDivergenceStrategy(String name) { this(name, "EUR/USD"); }
    public MomentumDivergenceStrategy(String name, String symbol) { this.name = name; this.symbol = symbol; }

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

        // Compute rate of change for fast and slow periods
        int size = history.size();
        double rocFast = calculateROC(ROC_FAST);
        double rocSlow = calculateROC(ROC_SLOW);

        // Previous bar's ROC values to detect divergence direction change
        double prevRocFast = calculateROCPrev(ROC_FAST);
        double prevRocSlow = calculateROCPrev(ROC_SLOW);

        // Divergence: short-term momentum moving opposite to the overall trend
        // Fast ROC going up while Slow ROC going down, or vice versa
        boolean fastTurningUp = prevRocFast < rocFast && rocFast > 0;
        boolean fastTurningDown = prevRocFast > rocFast && rocFast < 0;

        // Bullish divergence: price downtrend (slow ROC negative) but fast ROC is turning up
        // This means the short-term is improving while the medium-term is still down
        if (rocSlow < -DIVERGENCE_THRESHOLD && fastTurningUp && Math.abs(rocFast - rocSlow) > DIVERGENCE_THRESHOLD) {
            entryPrice = bar.close();
            entryStop = entryPrice - atr * SL_ATR;
            entryTarget = entryPrice + atr * TP_ATR;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(entryStop).withTakeProfit(entryTarget));
            inTrade = true; tradeDirection = Order.Side.BUY; barsHeld = 0; cooldownBars = 0; tradesToday++;
        }
        // Bearish divergence: price uptrend (slow ROC positive) but fast ROC is turning down
        else if (rocSlow > DIVERGENCE_THRESHOLD && fastTurningDown && Math.abs(rocFast - rocSlow) > DIVERGENCE_THRESHOLD) {
            entryPrice = bar.close();
            entryStop = entryPrice + atr * SL_ATR;
            entryTarget = entryPrice - atr * TP_ATR;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(entryStop).withTakeProfit(entryTarget));
            inTrade = true; tradeDirection = Order.Side.SELL; barsHeld = 0; cooldownBars = 0; tradesToday++;
        }
    }

    private double calculateROC(int period) {
        int size = history.size();
        if (size < period + 1) return 0;
        double currentClose = history.get(size - 1).close();
        double pastClose = history.get(size - 1 - period).close();
        return pastClose > 0 ? (currentClose - pastClose) / pastClose : 0;
    }

    private double calculateROCPrev(int period) {
        int size = history.size();
        if (size < period + 2) return 0;
        double currentClose = history.get(size - 2).close();
        double pastClose = history.get(size - 2 - period).close();
        return pastClose > 0 ? (currentClose - pastClose) / pastClose : 0;
    }

    private void closePosition(double price) {
        pending.add(new Order(symbol, tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
            Order.Type.MARKET, positionSize, price));
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
