package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;

import java.util.ArrayList;
import java.util.List;

/**
 * MesTurtleStrategy — Turtle-style Donchian channel breakout for CME Micro Futures (MES, MNQ, M2K).
 *
 * Mechanism (mirrors GoldTurtleTrend, which produced the first positive post-fix edge on XAU):
 *   - Entry: bar close breaks the highest HIGH of the last {@code entryChannel} bars (long)
 *            or the lowest LOW (short).
 *   - Exit:  opposite Donchian channel of {@code exitChannel} bars, via closeOnly.
 *   - Symmetric long/short, integer contracts, all prices tick-quantized via FuturesRegistry.
 *
 * Look-ahead safety: Donchian is computed on the internal history BEFORE the current bar is added,
 * and entries are emitted as MARKET orders filled by the engine at the next bar's open.
 */
public class MesTurtleStrategy implements Strategy {

    private final String name;
    private final String symbol;
    private final int entryChannel;
    private final int exitChannel;
    private final int contracts;
    private final boolean longOnly;
    private final int regimeMa;

    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side positionSide = null;

    public MesTurtleStrategy() {
        this("MesTurtleStrategy", "MES", 55, 20, 1, false, 0);
    }

    public MesTurtleStrategy(String name) {
        this(name, "MES", 55, 20, 1, false, 0);
    }

    public MesTurtleStrategy(String name, String symbol, int entryChannel, int exitChannel, int contracts) {
        this(name, symbol, entryChannel, exitChannel, contracts, false, 0);
    }

    public MesTurtleStrategy(String name, String symbol, int entryChannel, int exitChannel, int contracts, boolean longOnly) {
        this(name, symbol, entryChannel, exitChannel, contracts, longOnly, 0);
    }

    public MesTurtleStrategy(String name, String symbol, int entryChannel, int exitChannel, int contracts, boolean longOnly, int regimeMa) {
        this.name = name;
        this.symbol = symbol;
        this.entryChannel = entryChannel;
        this.exitChannel = exitChannel;
        this.contracts = contracts;
        this.longOnly = longOnly;
        this.regimeMa = regimeMa;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onBar(Bar bar) {
        // Regime filter: only long when above the long MA (bullish regime).
        boolean regimeBull = regimeMa <= 0
            || (history.size() >= regimeMa && bar.close() > sma(history, regimeMa));
        boolean regimeBear = regimeMa > 0
            && history.size() >= regimeMa && bar.close() < sma(history, regimeMa);

        // Manage open position first (exit channel on PAST bars)
        if (inTrade && history.size() >= exitChannel) {
            double exitHigh = donchianHigh(history, exitChannel);
            double exitLow = donchianLow(history, exitChannel);
            if (positionSide == Order.Side.BUY && (bar.close() < exitLow || regimeBear)) {
                closePosition(bar.close());
                history.add(bar);
                return;
            }
            if (positionSide == Order.Side.SELL && bar.close() > exitHigh) {
                closePosition(bar.close());
                history.add(bar);
                return;
            }
        }

        // Entry (entry channel on PAST bars)
        if (!inTrade && history.size() >= entryChannel) {
            double entryHigh = donchianHigh(history, entryChannel);
            double entryLow = donchianLow(history, entryChannel);
            if (bar.close() > entryHigh && regimeBull) {
                enterPosition(Order.Side.BUY, bar.close());
            } else if (!longOnly && bar.close() < entryLow) {
                enterPosition(Order.Side.SELL, bar.close());
            }
        }

        history.add(bar);
    }

    private void enterPosition(Order.Side side, double price) {
        pending.add(new Order(symbol, side, Order.Type.MARKET, contracts, price));
        inTrade = true;
        positionSide = side;
    }

    private void closePosition(double price) {
        Order.Side closeSide = positionSide == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, closeSide, Order.Type.MARKET, contracts, price).asCloseOnly());
        inTrade = false;
        positionSide = null;
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
    }

    @Override
    public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        positionSide = null;
    }

    private static double donchianHigh(List<Bar> bars, int n) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = bars.size() - n; i < bars.size(); i++) {
            max = Math.max(max, bars.get(i).high());
        }
        return max;
    }

    private static double donchianLow(List<Bar> bars, int n) {
        double min = Double.POSITIVE_INFINITY;
        for (int i = bars.size() - n; i < bars.size(); i++) {
            min = Math.min(min, bars.get(i).low());
        }
        return min;
    }

    private static double sma(List<Bar> bars, int n) {
        double sum = 0;
        for (int i = bars.size() - n; i < bars.size(); i++) {
            sum += bars.get(i).close();
        }
        return sum / n;
    }
}
