package com.martinfou.trading.strategies.longterm;

import com.martinfou.trading.core.*;
import com.martinfou.trading.core.indicators.Indicators;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * LtCrossMomentum — SMA(20)/SMA(100) Crossover Trend Following.
 *
 * A classic trend-following strategy using the golden cross / death cross
 * of a fast SMA (20) and a slow SMA (100). Enters long on golden cross
 * (SMA20 &gt; SMA100), short on death cross (SMA20 &lt; SMA100). Exits on the
 * reverse cross or when SL/TP is hit. ATR-based risk management ensures
 * adaptive stops across different market regimes.
 *
 * Entry:
 *   LONG: SMA(20) crosses ABOVE SMA(100) (golden cross)
 *   SHORT: SMA(20) crosses BELOW SMA(100) (death cross)
 *
 * Exit:
 *   Reverse crossover (SMA20 crosses back the other way)
 *   Stop loss: 2x ATR(14)
 *   Take profit: 4x ATR(14)
 *
 * Risk management:
 *   - Max 1 trade per day
 *   - closeOnly() on all exits
 *   - Fixed base units (2000)
 *
 * Inspiration:
 *   - Classic moving average crossover systems
 *   - Richard Donchian (pioneer of trend following systems)
 *   - Ed Seykota (trend following with risk management)
 *   - FTMO (max 10% drawdown discipline)
 */
public class LtCrossMomentum implements Strategy {

    private int fastPeriod = 20;
    private int slowPeriod = 100;
    private int atrPeriod = 14;
    private double atrSlMult = 2.0;
    private double atrTpMult = 4.0;
    private double referenceCapital = 10_000;
    private double riskPct = 0.01;
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean inTrade = false;
    private Order.Side direction = Order.Side.BUY;
    private double entryPrice = 0;
    private double entrySl = 0;
    private double entryTp = 0;
    private int lastTradeDay = -1;  // epoch-day to enforce max 1 trade/day
    private double positionUnits = 0;

    public LtCrossMomentum() { this("LtCrossMomentum", "EUR_USD"); }
    public LtCrossMomentum(String name) { this(name, "EUR_USD"); }
    public LtCrossMomentum(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        history.add(bar);
        int size = history.size();
        // Need slowPeriod bars for SMA, plus some buffer
        if (size < slowPeriod + 5) return;

        double smaFast = Indicators.sma(history, fastPeriod, size - 1);
        double smaSlow = Indicators.sma(history, slowPeriod, size - 1);
        double smaFastPrev = Indicators.sma(history, fastPeriod, size - 2);
        double smaSlowPrev = Indicators.sma(history, slowPeriod, size - 2);

        double atr = Indicators.atr(history, atrPeriod);
        double close = bar.close();

        if (Double.isNaN(smaFast) || Double.isNaN(smaSlow) ||
            Double.isNaN(atr) || atr == 0) return;

        int currentDay = ZonedDateTime.ofInstant(bar.timestamp(), UTC).getDayOfYear();

        if (inTrade) {
            // Check stop loss
            if ((direction == Order.Side.BUY && close <= entrySl) ||
                (direction == Order.Side.SELL && close >= entrySl)) {
                exitTrade(close);
                lastTradeDay = currentDay;
                return;
            }
            // Check take profit
            if ((direction == Order.Side.BUY && close >= entryTp) ||
                (direction == Order.Side.SELL && close <= entryTp)) {
                exitTrade(close);
                lastTradeDay = currentDay;
                return;
            }
            // Exit on reverse SMA crossover
            if (direction == Order.Side.BUY && smaFast < smaSlow) {
                // Golden cross reversed: SMA20 crossed back below SMA100
                exitTrade(close);
                lastTradeDay = currentDay;
                return;
            }
            if (direction == Order.Side.SELL && smaFast > smaSlow) {
                // Death cross reversed: SMA20 crossed back above SMA100
                exitTrade(close);
                lastTradeDay = currentDay;
                return;
            }
            return;
        }

        // Not in trade — check entry conditions (max 1 trade per day)
        if (currentDay == lastTradeDay) return;

        long units = Indicators.calcRiskPosition(referenceCapital, riskPct, atr, atrSlMult, symbol);

        // LONG entry: golden cross (SMA20 crosses above SMA100)
        if (smaFastPrev <= smaSlowPrev && smaFast > smaSlow) {
            direction = Order.Side.BUY;
            entryPrice = close;
            entrySl = close - atr * atrSlMult;
            entryTp = close + atr * atrTpMult;
            pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, units, close)
                .withStopLoss(entrySl).withTakeProfit(entryTp));
            inTrade = true;
            lastTradeDay = currentDay;
            positionUnits = units;
        }
        // SHORT entry: death cross (SMA20 crosses below SMA100)
        else if (smaFastPrev >= smaSlowPrev && smaFast < smaSlow) {
            direction = Order.Side.SELL;
            entryPrice = close;
            entrySl = close + atr * atrSlMult;
            entryTp = close - atr * atrTpMult;
            pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, units, close)
                .withStopLoss(entrySl).withTakeProfit(entryTp));
            inTrade = true;
            lastTradeDay = currentDay;
            positionUnits = units;
        }
    }

    @Override public void onTick(double bid, double ask, long volume) {}

    private void exitTrade(double price) {
        Order.Side exit = direction == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        pending.add(new Order(symbol, exit, Order.Type.MARKET, positionUnits, price).asCloseOnly());
        inTrade = false;
        positionUnits = 0;
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
        entryPrice = 0;
        entrySl = 0;
        entryTp = 0;
        lastTradeDay = -1;
        positionUnits = 0;
    }
}
