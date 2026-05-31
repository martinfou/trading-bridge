package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyOrderQueuesTest {

    @Test
    void drainPending_returnsCopyAndClearsQueue() {
        List<Order> pending = new ArrayList<>();
        pending.add(new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 10_000, 0));

        List<Order> drained = StrategyOrderQueues.drainPending(pending);

        assertEquals(1, drained.size());
        assertTrue(pending.isEmpty());
    }

    @Test
    void drainPending_removesFilledOrdersBeforeCopy() {
        List<Order> pending = new ArrayList<>();
        Order filled = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 10_000, 0);
        filled.fill();
        pending.add(filled);
        pending.add(new Order("EUR_USD", Order.Side.SELL, Order.Type.MARKET, 10_000, 0));

        List<Order> drained = StrategyOrderQueues.drainPending(pending);

        assertEquals(1, drained.size());
        assertEquals(Order.Side.SELL, drained.getFirst().side());
    }
}
