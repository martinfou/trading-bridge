package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.util.*;

/** EmaCloudTrendFilter — Trend following via EMA cloud + RSI confirmation + ATR trailing stop. */
public class EmaCloudTrendFilter implements Strategy {
    private static final int EMA_FAST = 20;
    private static final int EMA_SLOW = 50;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final double SL_MULT = 1.5;
    private static final double TP_MULT = 3.0;

    private final String name;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private boolean inTrade = false;
    private Order.Side positionSide = Order.Side.BUY;
    @SuppressWarnings("unused")
    private double entryPrice = 0;

    public EmaCloudTrendFilter() { this.name = "EmaCloudTrendFilter_EURUSD"; }
    public EmaCloudTrendFilter(String name) { this.name = name; }

    @Override public String name() { return name; }

    @Override public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < EMA_SLOW + 5) return;
        if (inTrade) { manageExit(bar); return; }

        double emaFast = Indicators.emaLatest(history, EMA_FAST);
        double emaSlow = Indicators.emaLatest(history, EMA_SLOW);
        double rsi = Indicators.rsi(history, RSI_PERIOD);
        double atr = Indicators.atr(history, ATR_PERIOD);

        if (emaFast > emaSlow && rsi > 50) {
            pending.add(new Order(name, Order.Side.BUY, Order.Type.MARKET, 1000, bar.close())
                .withStopLoss(bar.close() - atr * SL_MULT).withTakeProfit(bar.close() + atr * TP_MULT));
            inTrade = true;
            positionSide = Order.Side.BUY;
            entryPrice = bar.close();
        } else if (emaFast < emaSlow && rsi < 50) {
            pending.add(new Order(name, Order.Side.SELL, Order.Type.MARKET, 1000, bar.close())
                .withStopLoss(bar.close() + atr * SL_MULT).withTakeProfit(bar.close() - atr * TP_MULT));
            inTrade = true;
            positionSide = Order.Side.SELL;
            entryPrice = bar.close();
        }
    }

    private void manageExit(Bar bar) {
        double emaFast = Indicators.emaLatest(history, EMA_FAST);
        double emaSlow = Indicators.emaLatest(history, EMA_SLOW);
        double rsi = Indicators.rsi(history, RSI_PERIOD);

        if (positionSide == Order.Side.BUY && (emaFast < emaSlow || rsi < 40)) {
            pending.add(new Order(name, Order.Side.SELL, Order.Type.MARKET, 1000, bar.close()));
            inTrade = false;
        } else if (positionSide == Order.Side.SELL && (emaFast > emaSlow || rsi > 60)) {
            pending.add(new Order(name, Order.Side.BUY, Order.Type.MARKET, 1000, bar.close()));
            inTrade = false;
        }
    }

    @Override public void onTick(double bid, double ask, long volume) {}
    @Override public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }
    @Override public void reset() { pending.clear(); history.clear(); inTrade = false; }
}
