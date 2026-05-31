package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Order;

import java.util.ArrayList;
import java.util.List;

/** Helpers for the Strategy {@code getPendingOrders()} copy-and-clear contract. */
public final class StrategyOrderQueues {

    private StrategyOrderQueues() {}

    /**
     * Drops non-{@link Order.Status#PENDING} orders, returns a copy, and clears the queue.
     * Engines call {@code getPendingOrders()} once per bar; the queue must not retain drained orders.
     */
    public static List<Order> drainPending(List<Order> pending) {
        pending.removeIf(o -> o.status() != Order.Status.PENDING);
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }
}
