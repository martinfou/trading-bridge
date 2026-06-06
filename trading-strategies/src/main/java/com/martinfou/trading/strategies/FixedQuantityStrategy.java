package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;

import java.util.ArrayList;
import java.util.List;

/** Applies a run-configured position size to every order from a delegate strategy. */
public final class FixedQuantityStrategy implements Strategy {

    private final Strategy delegate;
    private final double quantity;

    public FixedQuantityStrategy(Strategy delegate, double quantity) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.delegate = delegate;
        this.quantity = quantity;
    }

    public double quantity() {
        return quantity;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public void onBar(Bar bar) {
        delegate.onBar(bar);
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
        delegate.onTick(bid, ask, volume);
    }

    @Override
    public List<Order> getPendingOrders() {
        List<Order> scaled = new ArrayList<>();
        for (Order order : delegate.getPendingOrders()) {
            order.rescaleQuantity(quantity);
            scaled.add(order);
        }
        return scaled;
    }

    @Override
    public void reset() {
        delegate.reset();
    }

}
