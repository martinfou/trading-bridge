package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.time.ZoneId;
import java.util.*;

/**
 * MayExhaustionFade — Fades the "Sell in May" seasonal bearish overextension.
 *
 * Rationale: "Sell in May and Go Away" is active. After 3+ consecutive
 * bearish bars with exhaustion wicks (lower wick > body * 1.5), the market is
 * oversold and likely to snap back. COT shows EUR/USD and GBP/USD already
 * heavily short (0.82:1 spec ratio) — the sell-off is priced in.
 *
 * Best on: EUR/USD, GBP/USD (COT bearish consensus already priced in → fade)
 * Entry: 3+ consecutive bearish bars + lower wick exhaustion + RSI < 30
 * Exit: TP at 1.5xATR, SL at 1.0xATR, max hold 6 bars
 */
public class MayExhaustionFadeStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double entryStop = 0;
    private double entryTarget = 0;
    private double positionSize = 1000;
    private int cooldownBars = 0;
    private int tradesToday = 0;
    private int lastTradeDay = -1;
    private static final int COOLDOWN_BARS = 8;
    private static final int MAX_TRADES_PER_DAY = 3;
    private static final int MIN_CONSECUTIVE_BEAR = 3;
    // Session filter: London (03:00-11:00 UTC) and NY (13:00-20:00 UTC) only
    private static final int SESSION_START = 3;
    private static final int SESSION_END = 20;

    public MayExhaustionFadeStrategy() { this("MayExhaustionFade", "EUR/USD"); }
    public MayExhaustionFadeStrategy(String name) { this(name, "EUR/USD"); }
    public MayExhaustionFadeStrategy(String name, String symbol) { this.name = name; this.symbol = symbol; }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 22) return;

        // Daily trade counter
        int barDay = bar.timestamp().atZone(ZoneId.of("America/New_York")).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        double atr = calculateATR(14);
        if (atr <= 0) return;

        if (inTrade) {
            barsHeld++;
            if (tradeDirection == Order.Side.BUY && bar.low() <= entryStop) { closePosition(entryStop); return; }
            if (tradeDirection == Order.Side.SELL && bar.high() >= entryStop) { closePosition(entryStop); return; }
            if (tradeDirection == Order.Side.BUY && bar.high() >= entryTarget) { closePosition(entryTarget); return; }
            if (tradeDirection == Order.Side.SELL && bar.low() <= entryTarget) { closePosition(entryTarget); return; }
            if (barsHeld >= 6) { closePosition(bar.close()); return; }
            return;
        }

        if (cooldownBars > 0) { cooldownBars--; return; }
        if (tradesToday >= MAX_TRADES_PER_DAY) return;

        // Session filter — only trade during liquid hours
        int hour = bar.timestamp().atZone(ZoneId.of("UTC")).getHour();
        if (hour < SESSION_START || hour > SESSION_END) return;

        // Count consecutive bearish bars
        int consBear = 0;
        for (int i = history.size() - 2; i >= Math.max(0, history.size() - 8); i--) {
            Bar b = history.get(i);
            if (b.close() < b.open()) consBear++;
            else break;
        }

        if (consBear < MIN_CONSECUTIVE_BEAR) return;

        // Check for exhaustion after sell-off
        double body = Math.abs(bar.close() - bar.open());
        double upperWick = bar.high() - Math.max(bar.close(), bar.open());
        double lowerWick = Math.min(bar.close(), bar.open()) - bar.low();

        // RSI(14) calculation
        double rsi = calcRSI(14);

        // Long entry: bear exhaustion — long lower wick, RSI oversold
        // After 3+ bear bars, a bar with long lower wick signals reversal
        if (lowerWick > body * 1.5 && lowerWick > upperWick * 1.3 && rsi < 35) {
            entryPrice = bar.close();
            entryStop = entryPrice - atr * 0.8;       // SL 0.8×ATR below (tighter for fade)
            entryTarget = entryPrice + atr * 1.5;     // TP 1.5×ATR above
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(entryStop).withTakeProfit(entryTarget));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsHeld = 0;
            cooldownBars = 0;
            tradesToday++;
        }
        // Short entry: bull exhaustion after a rare up-move in bearish context
        // (only triggered if the trend suddenly reverses to up)
        else if (upperWick > body * 1.5 && upperWick > lowerWick * 1.3 && rsi > 65) {
            // But we check last 8 bars — if majority were bearish, this is a fade
            int bearCount = 0;
            for (int i = history.size() - 8; i < history.size() - 1; i++) {
                if (history.get(i).close() < history.get(i).open()) bearCount++;
            }
            if (bearCount >= 5) {
                entryPrice = bar.close();
                entryStop = entryPrice + atr * 0.8;
                entryTarget = entryPrice - atr * 1.5;
                pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                    .withStopLoss(entryStop).withTakeProfit(entryTarget));
                inTrade = true;
                tradeDirection = Order.Side.SELL;
                barsHeld = 0;
                cooldownBars = 0;
                tradesToday++;
            }
        }
    }

    private void closePosition(double price) {
        pending.add(new Order(symbol, tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
            Order.Type.MARKET, positionSize, price));
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }

    private double calculateATR(int period) {
        int size = history.size();
        double sum = 0;
        int count = 0;
        for (int i = Math.max(1, size - period); i < size; i++) {
            Bar prev = history.get(i - 1);
            Bar curr = history.get(i);
            double tr = Math.max(curr.high() - curr.low(),
                Math.max(Math.abs(curr.high() - prev.close()), Math.abs(curr.low() - prev.close())));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 1.0;
    }

    private double calcRSI(int period) {
        if (history.size() < period + 1) return 50;
        double gainSum = 0, lossSum = 0;
        int start = history.size() - period;
        for (int i = start; i < history.size(); i++) {
            double change = history.get(i).close() - history.get(i - 1).close();
            if (change > 0) gainSum += change;
            else lossSum -= change;
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
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
