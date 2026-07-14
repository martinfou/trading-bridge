package com.martinfou.trading.strategies.longterm;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/**
 * LtEfficiencyRatio — Kaufman Efficiency Ratio Trend Strategy
 *
 * Only trade when the market is efficiently trending (Kaufman ER > 0.5).
 * Uses EMA(50) for direction, ATR for trailing SL, ER for exit signal.
 * Position sizing scales with ER strength.
 *
 * Inspiration: Kaufman + Seykota + FTMO risk management.
 */
public class LtEfficiencyRatio implements Strategy {

    private static final int ER_PERIOD = 20;
    private static final double ER_ENTRY = 0.5;
    private static final double ER_EXIT = 0.25;
    private static final double ATR_MULT_SL = 2.0;
    private static final double ATR_MULT_TP = 3.0;
    private static final double REFERENCE_CAPITAL = 10_000;
    private static final double RISK_PCT = 0.01;
    private static final java.time.ZoneId TZ = java.time.ZoneId.of("America/New_York");

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side direction = Order.Side.BUY;
    private double entryPrice = 0;
    private double entrySl = 0;
    private int tradesToday = 0;
    private int lastTradeDay = -1;
    private int consecutiveLosses = 0;

    public LtEfficiencyRatio() { this("LtEfficiencyRatio", "EUR_USD"); }
    public LtEfficiencyRatio(String name) { this(name, "EUR_USD"); }
    public LtEfficiencyRatio(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        int size = history.size();
        if (size < ER_PERIOD + 1) return;

        // Day tracking
        int barDay = bar.timestamp().atZone(TZ).getDayOfYear();
        if (barDay != lastTradeDay) { tradesToday = 0; lastTradeDay = barDay; }

        double er = calcEfficiencyRatio(ER_PERIOD);
        double atr = Indicators.atr(history, 14);
        if (Double.isNaN(er) || Double.isNaN(atr) || atr == 0) return;

        double close = bar.close();

        if (inTrade) {
            if (er < ER_EXIT || consecutiveLosses >= 2) {
                exitTrade(close);
                return;
            }
            if (direction == Order.Side.BUY && close <= entrySl) {
                consecutiveLosses++;
                exitTrade(entrySl);
                return;
            }
            if (direction == Order.Side.SELL && close >= entrySl) {
                consecutiveLosses++;
                exitTrade(entrySl);
                return;
            }
            // Trail stop
            if (direction == Order.Side.BUY && close > entryPrice) {
                double trail = close - atr * ATR_MULT_SL;
                if (trail > entrySl) entrySl = trail;
            }
            if (direction == Order.Side.SELL && close < entryPrice) {
                double trail = close + atr * ATR_MULT_SL;
                if (trail < entrySl) entrySl = trail;
            }
            return;
        }

        if (er < ER_ENTRY || tradesToday >= 1) return;

        double ema50 = Indicators.emaLatest(history, 50);
        if (Double.isNaN(ema50)) return;

        if (close > ema50) {
            double sl = close - atr * ATR_MULT_SL;
            double tp = close + atr * ATR_MULT_TP;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, ATR_MULT_SL, symbol), close)
                .withStopLoss(sl).withTakeProfit(tp));
            entryPrice = close; entrySl = sl; direction = Order.Side.BUY;
            inTrade = true; tradesToday++;
        } else if (close < ema50) {
            double sl = close + atr * ATR_MULT_SL;
            double tp = close - atr * ATR_MULT_TP;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, Indicators.calcRiskPosition(REFERENCE_CAPITAL, RISK_PCT, atr, ATR_MULT_SL, symbol), close)
                .withStopLoss(sl).withTakeProfit(tp));
            entryPrice = close; entrySl = sl; direction = Order.Side.SELL;
            inTrade = true; tradesToday++;
        }
    }

    @Override public void onTick(double bid, double ask, long volume) {}

    private void exitTrade(double price) {
        Order.Side exitSide = direction == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exitSide, Order.Type.MARKET, 1000, price).closeOnly());
        inTrade = false;
    }

    private double calcEfficiencyRatio(int period) {
        int size = history.size();
        if (size < period + 1) return Double.NaN;
        double netChange = Math.abs(history.get(size - 1).close() - history.get(size - 1 - period).close());
        double totalVol = 0;
        for (int i = size - period; i < size; i++) {
            totalVol += Math.abs(history.get(i).close() - history.get(i - 1).close());
        }
        if (totalVol == 0) return 0;
        return netChange / totalVol;
    }

    @Override
    public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear(); pending.clear();
        inTrade = false; tradesToday = 0; lastTradeDay = -1;
        consecutiveLosses = 0; entryPrice = 0; entrySl = 0;
    }
}
