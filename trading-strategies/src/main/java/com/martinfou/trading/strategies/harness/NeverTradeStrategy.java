package com.martinfou.trading.strategies.harness;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;

import java.util.List;

/** Emits no orders — expects zero closed trades. */
public final class NeverTradeStrategy implements Strategy {

    private final String symbol;

    public NeverTradeStrategy(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public String name() {
        return "Harness_NeverTrade";
    }

    @Override
    public void onBar(Bar bar) {}

    @Override
    public void onTick(double bid, double ask, long volume) {}

    @Override
    public List<Order> getPendingOrders() {
        return List.of();
    }

    @Override
    public void reset() {}
}
