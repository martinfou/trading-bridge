package com.martinfou.trading.strategies.harness;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;

import java.util.ArrayList;
import java.util.List;

/** Base for deterministic harness strategies; usually one pending order per bar, or two for same-bar round-trips. */
public abstract class HarnessScriptedStrategy implements Strategy {

    protected final String symbol;
    protected final List<Order> pending = new ArrayList<>();

    protected HarnessScriptedStrategy(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public void onTick(double bid, double ask, long volume) {}

    @Override
    public List<Order> getPendingOrders() {
        return List.copyOf(pending);
    }

    @Override
    public void reset() {
        pending.clear();
        onReset();
    }

    protected void onReset() {}

    protected boolean symbolMatches(Bar bar) {
        return bar.symbol().equals(symbol);
    }

    protected void clearPending() {
        pending.clear();
    }

    protected void emit(Order order) {
        pending.clear();
        pending.add(order);
    }

    /** Two orders processed in one bar (e.g. MARKET entry then LIMIT exit at {@code close}). */
    protected void emitPair(Order first, Order second) {
        pending.clear();
        pending.add(first);
        pending.add(second);
    }

    protected Order marketBuy(Bar bar) {
        return new Order(symbol, Order.Side.BUY, Order.Type.MARKET, HarnessQuantities.DEFAULT, 0);
    }

    protected Order marketSellClose(Bar bar) {
        return new Order(symbol, Order.Side.SELL, Order.Type.MARKET, HarnessQuantities.DEFAULT, 0).closeOnly();
    }

    /** Close-only exit at this bar's close (LIMIT fills when {@code high >= close}). */
    protected Order limitSellCloseAtClose(Bar bar) {
        return new Order(symbol, Order.Side.SELL, Order.Type.LIMIT, HarnessQuantities.DEFAULT, bar.close())
            .closeOnly();
    }
}
