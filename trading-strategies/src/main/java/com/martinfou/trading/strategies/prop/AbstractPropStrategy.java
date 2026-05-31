package com.martinfou.trading.strategies.prop;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.indicators.Indicators;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for prop-firm strategies: 0.5% risk envelope, max 3 trades/day,
 * halt after 2 consecutive losses, minimum 1:2.5 RR, no averaging down.
 */
public abstract class AbstractPropStrategy implements Strategy {

    protected static final double QUANTITY = 1000;
    protected static final double RR = 2.5;
    protected static final int MAX_TRADES_PER_DAY = 3;
    protected static final int MAX_CONSECUTIVE_LOSSES = 2;

    protected final String strategyName;
    protected final String symbol;

    protected final List<Bar> history = new ArrayList<>();
    private final List<Order> pending = new ArrayList<>();

    private int dayKey = -1;
    protected int tradesToday;
    protected int consecutiveLosses;

    private Indicators.TradeSide activeSide;
    private double activeSl;
    private double activeTp;
    private int barsInTrade;

    protected AbstractPropStrategy(String strategyName, String symbol) {
        this.strategyName = strategyName;
        this.symbol = symbol;
    }

    @Override
    public String name() {
        return strategyName;
    }

    @Override
    public void onBar(Bar bar) {
        if (!bar.symbol().equals(symbol)) return;
        rollDay(bar);
        history.add(bar);
        syncPositionState(bar);
        if (canEnter()) {
            evaluate(bar);
        }
    }

    protected abstract void evaluate(Bar bar);

    protected boolean canEnter() {
        return activeSide == null
            && tradesToday < MAX_TRADES_PER_DAY
            && consecutiveLosses < MAX_CONSECUTIVE_LOSSES;
    }

    protected void rollDay(Bar bar) {
        int dk = PropSessions.dayKey(bar);
        if (dk != dayKey) {
            dayKey = dk;
            tradesToday = 0;
            consecutiveLosses = 0;
        }
    }

    /** Mirror engine SL/TP resolution so strategies can take new trades. */
    private void syncPositionState(Bar bar) {
        if (activeSide == null) return;
        barsInTrade++;
        boolean hitSl = false;
        boolean hitTp = false;
        if (activeSide == Indicators.TradeSide.LONG) {
            hitSl = activeSl > 0 && bar.low() <= activeSl;
            hitTp = activeTp > 0 && bar.high() >= activeTp;
        } else {
            hitSl = activeSl > 0 && bar.high() >= activeSl;
            hitTp = activeTp > 0 && bar.low() <= activeTp;
        }
        int maxHold = maxHoldBars();
        if (hitSl || hitTp || barsInTrade >= maxHold) {
            if (hitSl) consecutiveLosses++;
            else if (hitTp) consecutiveLosses = 0;
            clearActive();
        }
    }

    protected int maxHoldBars() {
        return 48;
    }

    protected void enterLong(Bar bar, double sl, double tp) {
        pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, QUANTITY, bar.close())
            .withStopLoss(sl).withTakeProfit(tp));
        activeSide = Indicators.TradeSide.LONG;
        activeSl = sl;
        activeTp = tp;
        barsInTrade = 0;
        tradesToday++;
    }

    protected void enterShort(Bar bar, double sl, double tp) {
        pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, QUANTITY, bar.close())
            .withStopLoss(sl).withTakeProfit(tp));
        activeSide = Indicators.TradeSide.SHORT;
        activeSl = sl;
        activeTp = tp;
        barsInTrade = 0;
        tradesToday++;
    }

    protected double rrTp(double entry, double sl, Indicators.TradeSide side) {
        return Indicators.riskRewardTp(entry, sl, side, RR);
    }

    protected double atr(int period) {
        return Indicators.atr(history, period);
    }

    private void clearActive() {
        activeSide = null;
        activeSl = 0;
        activeTp = 0;
        barsInTrade = 0;
    }

    @Override
    public void onTick(double bid, double ask, long volume) {}

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
        clearActive();
        dayKey = -1;
        tradesToday = 0;
        consecutiveLosses = 0;
    }
}
