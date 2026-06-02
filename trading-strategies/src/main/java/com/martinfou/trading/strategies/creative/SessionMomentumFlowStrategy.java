package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.*;
import java.util.*;

/**
 * Session Momentum Flow Strategy — Seasonality/Calendar
 *
 * 📊 Inspiration: forex-seasonality — Olsen Ltd.'s intraday FX patterns,
 *    DailyFX session analysis. Tracks institutional flow carry-over
 *    across major sessions (Asia → London → NY).
 *
 * 🔧 Mechanism:
 *    - Track open→close return for each of 3 daily sessions
 *    - Compute weighted score from last 3 sessions (0.5/0.3/0.2)
 *    - Entry at session boundary if |score| > threshold
 *    - Exit: trailing stop, max 12 bars, or score flips
 */
public class SessionMomentumFlowStrategy implements Strategy {

    private static final int ASIA_START = 0, ASIA_END = 8;
    private static final int LONDON_START = 8, LONDON_END = 16;
    private static final int NY_START = 13, NY_END = 21;
    private static final int MIN_HISTORY = 100;
    private static final int ATR_PERIOD = 14;
    private static final double ATR_STOP_MULT = 1.5;
    private static final double RR_TARGET = 2.0;
    private static final int MAX_BARS_HOLD = 12;
    private static final double MIN_POSITION = 1000;
    private static final double ENTRY_THRESHOLD = 0.003;
    private static final int SESSION_ASIA = 0, SESSION_LONDON = 1, SESSION_NY = 2;
    private static final int COOLDOWN_BARS = 5;

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private final ZoneOffset tz = ZoneOffset.UTC;

    private boolean inTrade = false;
    private Order.Side tradeDirection;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int barsInTrade;
    private double highestSinceEntry;
    private double lowestSinceEntry;

    private final Deque<SessionRecord> recentSessions = new ArrayDeque<>();
    private int currentSessionId = -1;
    private double sessionOpenPrice = 0;
    private int lastTradeSessionId = -1;
    private double positionSize;
    private int cooldownBars;

    public SessionMomentumFlowStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
        this.positionSize = MIN_POSITION;
    }

    public SessionMomentumFlowStrategy() {
        this("SessionMomentumFlow", "EUR_USD");
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
        int dayOfYear = zdt.getDayOfYear();
        int sessionId = getSessionId(hour, dayOfYear);

        // Detect session transitions
        if (sessionId != currentSessionId) {
            if (currentSessionId >= 0 && sessionOpenPrice > 0 && history.size() > 1) {
                double sessionReturn = (history.get(history.size() - 2).close() - sessionOpenPrice) / sessionOpenPrice;
                recentSessions.addFirst(new SessionRecord(currentSessionId, sessionReturn));
                while (recentSessions.size() > 5) recentSessions.removeLast();
            }

            currentSessionId = sessionId;
            sessionOpenPrice = bar.open();

            if (!inTrade && lastTradeSessionId != sessionId) {
                if (cooldownBars > 0) { cooldownBars = Math.max(0, cooldownBars - 1); }
                else { evaluateSessionEntry(bar, sessionId); }
            }
        }

        managePosition(bar);
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
        recentSessions.clear();
        currentSessionId = -1;
        sessionOpenPrice = 0;
        lastTradeSessionId = -1;
        cooldownBars = 0;
    }

    private void managePosition(Bar bar) {
        if (!inTrade) return;
        barsInTrade++;

        if (tradeDirection == Order.Side.BUY) {
            highestSinceEntry = Math.max(highestSinceEntry, bar.high());
        } else {
            lowestSinceEntry = Math.min(lowestSinceEntry, bar.low());
        }

        // Exit at session boundary
        ZonedDateTime zdt = bar.timestamp().atZone(tz);
        int newSession = getSessionId(zdt.getHour(), zdt.getDayOfYear());
        if (newSession != currentSessionId) {
            closePosition(bar.close());
            return;
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
        if (tradeDirection == Order.Side.BUY) {
            double trail = highestSinceEntry - atr() * ATR_STOP_MULT;
            stopLoss = Math.max(stopLoss, trail);
            if (bar.low() <= stopLoss) { closePosition(bar.close()); return; }
        } else {
            double trail = lowestSinceEntry + atr() * ATR_STOP_MULT;
            stopLoss = Math.min(stopLoss, trail);
            if (bar.high() >= stopLoss) { closePosition(bar.close()); return; }
        }

        // Score flip exit
        double score = computeSessionScore();
        if (!Double.isNaN(score)) {
            if ((tradeDirection == Order.Side.BUY && score < -ENTRY_THRESHOLD)
                || (tradeDirection == Order.Side.SELL && score > ENTRY_THRESHOLD)) {
                closePosition(bar.close());
            }
        }
    }

    private void evaluateSessionEntry(Bar bar, int sessionId) {
        double score = computeSessionScore();
        if (Double.isNaN(score)) return;

        double atr = atr();
        if (Double.isNaN(atr) || atr <= 0) return;

        double effectiveThreshold = sessionId == SESSION_NY ? ENTRY_THRESHOLD * 1.5 : ENTRY_THRESHOLD;

        if (score > effectiveThreshold) {
            entryPrice = bar.open();
            stopLoss = entryPrice - atr * ATR_STOP_MULT;
            takeProfit = entryPrice + atr * ATR_STOP_MULT * RR_TARGET;
            highestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.BUY;
            barsInTrade = 0;
            lastTradeSessionId = sessionId;
            cooldownBars = 0;
        } else if (score < -effectiveThreshold) {
            entryPrice = bar.open();
            stopLoss = entryPrice + atr * ATR_STOP_MULT;
            takeProfit = entryPrice - atr * ATR_STOP_MULT * RR_TARGET;
            lowestSinceEntry = entryPrice;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, entryPrice)
                .withStopLoss(stopLoss).withTakeProfit(takeProfit));
            inTrade = true;
            tradeDirection = Order.Side.SELL;
            barsInTrade = 0;
            lastTradeSessionId = sessionId;
            cooldownBars = 0;
        }
    }

    private void closePosition(double price) {
        Order.Side exitSide = tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, positionSize, price).closeOnly());
        inTrade = false;
        cooldownBars = COOLDOWN_BARS;
    }

    private double computeSessionScore() {
        if (recentSessions.size() < 2) return Double.NaN;

        double score = 0;
        double totalWeight = 0;
        double[] weights = {0.5, 0.3, 0.2};
        int i = 0;
        for (SessionRecord sr : recentSessions) {
            if (i >= weights.length) break;
            score += sr.return_pct * weights[i];
            totalWeight += weights[i];
            i++;
        }

        return totalWeight > 0 ? score / totalWeight : Double.NaN;
    }

    private int getSessionId(int hour, int dayOfYear) {
        if (hour >= NY_START && hour < NY_END) return SESSION_NY;
        if (hour >= LONDON_START && hour < LONDON_END) return SESSION_LONDON;
        if (hour >= ASIA_START && hour < ASIA_END) return SESSION_ASIA;
        return -1;
    }

    private record SessionRecord(int sessionId, double return_pct) {}

    private double atr() { return Indicators.atr(history, ATR_PERIOD); }
}
