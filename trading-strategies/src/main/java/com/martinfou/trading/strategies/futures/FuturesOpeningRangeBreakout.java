package com.martinfou.trading.strategies.futures;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.indicators.Indicators;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * FuturesOpeningRangeBreakout — Toby Crabel's Opening Range Breakout (ORB) for CME Futures.
 *
 * 📊 Inspiration: Toby Crabel's classic Opening Range Breakout model adapted for
 *    electronic index futures (MES, MNQ, M2K, EMD).
 *
 * 🔧 Mechanism:
 *    - Identifies the US Cash Opening Range (09:00 - 10:00 NY).
 *    - Establishes the Opening Range High and Low.
 *    - Long Trigger: Close > OR High + (stretch × ATR) and EMA(20) > EMA(50).
 *    - Short Trigger: Close < OR Low - (stretch × ATR) and EMA(20) < EMA(200).
 *    - Risk Management:
 *        • Initial Stop Loss at opposite OR boundary + ATR buffer.
 *        • Take Profit set to 2.5:1 Risk/Reward.
 *        • Max hold 6 bars (intraday session exit).
 *        • Max 1 trade per trading day.
 */
public class FuturesOpeningRangeBreakout implements Strategy {

    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
    private static final int DEFAULT_ATR_PERIOD = 14;
    private static final double DEFAULT_STRETCH = 0.20;
    private static final double DEFAULT_RR = 2.5;
    private static final int DEFAULT_FAST_EMA = 20;
    private static final int DEFAULT_SLOW_EMA = 50;
    private static final int DEFAULT_REGIME_EMA = 200;
    private static final int MAX_HOLD_BARS = 6;

    private final String name;
    private final String symbol;
    private final double stretchMult;
    private final double rewardRiskRatio;
    private final int fastEmaPeriod;
    private final int slowEmaPeriod;
    private final int atrPeriod;

    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side activeSide;
    private double activeSl;
    private double activeTp;
    private int barsInTrade;

    // Daily OR tracking
    private int currentDayKey = -1;
    private boolean orEstablished = false;
    private double orHigh = Double.NEGATIVE_INFINITY;
    private double orLow = Double.POSITIVE_INFINITY;
    private boolean tradedToday = false;

    public FuturesOpeningRangeBreakout() {
        this("FuturesOpeningRangeBreakout", "MES");
    }

    public FuturesOpeningRangeBreakout(String name) {
        this(name, "MES");
    }

    public FuturesOpeningRangeBreakout(String name, String symbol) {
        this(name, symbol, DEFAULT_STRETCH, DEFAULT_RR, DEFAULT_FAST_EMA, DEFAULT_SLOW_EMA, DEFAULT_ATR_PERIOD);
    }

    public FuturesOpeningRangeBreakout(String name, String symbol,
                                       double stretchMult, double rewardRiskRatio,
                                       int fastEmaPeriod, int slowEmaPeriod, int atrPeriod) {
        this.name = name;
        this.symbol = symbol;
        this.stretchMult = stretchMult > 0 ? stretchMult : DEFAULT_STRETCH;
        this.rewardRiskRatio = rewardRiskRatio > 0 ? rewardRiskRatio : DEFAULT_RR;
        this.fastEmaPeriod = fastEmaPeriod > 0 ? fastEmaPeriod : DEFAULT_FAST_EMA;
        this.slowEmaPeriod = slowEmaPeriod > 0 ? slowEmaPeriod : DEFAULT_SLOW_EMA;
        this.atrPeriod = atrPeriod > 0 ? atrPeriod : DEFAULT_ATR_PERIOD;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equalsIgnoreCase(symbol) && !bar.symbol().equalsIgnoreCase(symbol.replace("/", "_"))) {
            return;
        }

        ZonedDateTime nyTime = bar.timestamp().atZone(NY_ZONE);
        int dayKey = nyTime.getYear() * 1000 + nyTime.getDayOfYear();

        // New trading day roll
        if (dayKey != currentDayKey) {
            currentDayKey = dayKey;
            orEstablished = false;
            orHigh = Double.NEGATIVE_INFINITY;
            orLow = Double.POSITIVE_INFINITY;
            tradedToday = false;
        }

        history.add(bar);
        syncPositionState(bar);

        if (history.size() < Math.max(DEFAULT_REGIME_EMA, slowEmaPeriod) + 10) {
            return;
        }

        int hour = nyTime.getHour(); // 0-23 in NY time

        // Record Opening Range at 09:00 NY
        if (hour == 9) {
            orHigh = Math.max(orHigh, bar.high());
            orLow = Math.min(orLow, bar.low());
            orEstablished = true;
        }

        if (!inTrade && orEstablished && !tradedToday && hour >= 10 && hour <= 14) {
            evaluateEntry(bar);
        }
    }

    private void evaluateEntry(Bar bar) {
        double atr = Indicators.atr(history, atrPeriod);
        if (Double.isNaN(atr) || atr <= 0) return;

        double fastEma = Indicators.emaLatest(history, fastEmaPeriod);
        double slowEma = Indicators.emaLatest(history, slowEmaPeriod);
        double regimeEma = Indicators.emaLatest(history, DEFAULT_REGIME_EMA);
        if (Double.isNaN(fastEma) || Double.isNaN(slowEma) || Double.isNaN(regimeEma)) return;

        double stretch = stretchMult * atr;
        double buyTrigger = orHigh + stretch;
        double sellTrigger = orLow - stretch;

        // Long Breakout: Close > buyTrigger and fast EMA > slow EMA
        if (bar.close() > buyTrigger && fastEma > slowEma) {
            double entry = bar.close();
            double sl = Math.max(orLow, entry - 1.5 * atr);
            double risk = entry - sl;
            if (risk > 0) {
                double tp = entry + rewardRiskRatio * risk;
                enterTrade(Order.Side.BUY, entry, sl, tp);
            }
        }
        // Short Breakout: Close < sellTrigger and fast EMA < slow EMA and below regime EMA
        else if (bar.close() < sellTrigger && fastEma < slowEma && bar.close() < regimeEma) {
            double entry = bar.close();
            double sl = Math.min(orHigh, entry + 1.5 * atr);
            double risk = sl - entry;
            if (risk > 0) {
                double tp = entry - rewardRiskRatio * risk;
                enterTrade(Order.Side.SELL, entry, sl, tp);
            }
        }
    }

    private void enterTrade(Order.Side side, double entry, double sl, double tp) {
        Order order = new Order(symbol, side, Order.Type.MARKET, 1.0, entry)
            .withStopLoss(sl)
            .withTakeProfit(tp);

        pending.add(order);
        inTrade = true;
        activeSide = side;
        activeSl = sl;
        activeTp = tp;
        barsInTrade = 0;
        tradedToday = true;
    }

    private void syncPositionState(Bar bar) {
        if (!inTrade || activeSide == null) return;
        barsInTrade++;

        boolean hitSl = false;
        boolean hitTp = false;

        if (activeSide == Order.Side.BUY) {
            hitSl = activeSl > 0 && bar.low() <= activeSl;
            hitTp = activeTp > 0 && bar.high() >= activeTp;
        } else {
            hitSl = activeSl > 0 && bar.high() >= activeSl;
            hitTp = activeTp > 0 && bar.low() <= activeTp;
        }

        if (hitSl || hitTp || barsInTrade >= MAX_HOLD_BARS) {
            inTrade = false;
            activeSide = null;
            activeSl = 0;
            activeTp = 0;
            barsInTrade = 0;
        }
    }

    @Override
    public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        activeSide = null;
        activeSl = 0;
        activeTp = 0;
        barsInTrade = 0;
        currentDayKey = -1;
        orEstablished = false;
        orHigh = Double.NEGATIVE_INFINITY;
        orLow = Double.POSITIVE_INFINITY;
        tradedToday = false;
    }

    @Override
    public void onTick(double bid, double ask, long volume) {}

    @Override
    public List<Order> getPendingOrders() {
        var copy = List.copyOf(pending);
        pending.clear();
        return copy;
    }
}
