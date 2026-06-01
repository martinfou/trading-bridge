package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

public class VWPReversionStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private static final int VWAP_PERIOD = 20;
    private static final double ENTRY_DEVIATION_ATR = 1.5;
    private static final double EXIT_DEVIATION_ATR = 0.3;
    private static final double VOLUME_SPIKE_THRESHOLD = 1.3;
    private static final int MAX_BARS_HOLD = 8;
    private static final int ATR_PERIOD = 14;
    private static final int COOLDOWN_BARS = 10;
    private static final int MAX_TRADES_PER_DAY = 3;

    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private double entryStop = 0;
    private double entryTarget = 0;
    private int barsHeld = 0;
    private double entryVwap = 0;
    private double positionSize = 1000;
    private int cooldownBars = 0;
    private int tradesToday = 0;
    private int lastTradeDay = -1;

    public VWPReversionStrategy() { this("🔁 VWAP Reversion", "USD/CHF"); }
    public VWPReversionStrategy(String name) { this(name, "USD/CHF"); }
    public VWPReversionStrategy(String name, String symbol) { this.name = name; this.symbol = symbol; }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < Math.max(VWAP_PERIOD, ATR_PERIOD) + 5) return;

        int barDay = bar.timestamp().atZone(java.time.ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        double atr = calculateATR(ATR_PERIOD);
        if (atr <= 0) return;

        double vwap = calculateVWAP(VWAP_PERIOD);
        double close = bar.close();
        double deviation = (close - vwap) / atr;
        double avgVolume = averageVolume(VWAP_PERIOD);
        double volumeRatio = avgVolume > 0 ? bar.volume() / avgVolume : 1.0;

        if (inTrade) {
            barsHeld++;
            boolean stopHit = (tradeDirection == Order.Side.BUY && bar.low() <= entryStop)
                || (tradeDirection == Order.Side.SELL && bar.high() >= entryStop);
            boolean tpHit = (tradeDirection == Order.Side.BUY && bar.high() >= entryTarget)
                || (tradeDirection == Order.Side.SELL && bar.low() <= entryTarget);
            boolean reverted = Math.abs(deviation) < EXIT_DEVIATION_ATR;

            if (stopHit || tpHit || reverted || barsHeld >= MAX_BARS_HOLD) {
                closePosition(close);
            }
            return;
        }

        if (cooldownBars > 0) { cooldownBars--; return; }
        if (tradesToday >= MAX_TRADES_PER_DAY) return;

        if (deviation > ENTRY_DEVIATION_ATR && volumeRatio > VOLUME_SPIKE_THRESHOLD) {
            entryPrice = close; entryVwap = vwap;
            entryStop = close + atr * 1.5; entryTarget = close - atr * 2.0;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, close)
                .withStopLoss(entryStop).withTakeProfit(entryTarget));
            inTrade = true; tradeDirection = Order.Side.SELL;
            barsHeld = 0; cooldownBars = 0; tradesToday++;
        } else if (deviation < -ENTRY_DEVIATION_ATR && volumeRatio > VOLUME_SPIKE_THRESHOLD) {
            entryPrice = close; entryVwap = vwap;
            entryStop = close - atr * 1.5; entryTarget = close + atr * 2.0;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, close)
                .withStopLoss(entryStop).withTakeProfit(entryTarget));
            inTrade = true; tradeDirection = Order.Side.BUY;
            barsHeld = 0; cooldownBars = 0; tradesToday++;
        }
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, positionSize, price).closeOnly());
        inTrade = false; cooldownBars = COOLDOWN_BARS;
    }

    private double calculateVWAP(int period) {
        int size = history.size(); double sumTPV = 0; double sumVol = 0;
        for (int i = Math.max(0, size - period); i < size; i++) {
            Bar b = history.get(i);
            double tp = (b.high() + b.low() + b.close()) / 3.0;
            sumTPV += tp * b.volume(); sumVol += b.volume();
        }
        return sumVol > 0 ? sumTPV / sumVol : history.get(size - 1).close();
    }

    private double calculateATR(int period) {
        int size = history.size(); double sum = 0; int count = 0;
        for (int i = Math.max(1, size - period); i < size; i++) {
            Bar prev = history.get(i - 1); Bar curr = history.get(i);
            double tr = Math.max(curr.high() - curr.low(), Math.max(Math.abs(curr.high() - prev.close()), Math.abs(curr.low() - prev.close())));
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
        entryPrice = 0; entryStop = 0; entryTarget = 0; entryVwap = 0; barsHeld = 0;
        cooldownBars = 0; tradesToday = 0; lastTradeDay = -1;
    }

    /** Restore runtime state after crash recovery — keeps history/pending intact. */
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
