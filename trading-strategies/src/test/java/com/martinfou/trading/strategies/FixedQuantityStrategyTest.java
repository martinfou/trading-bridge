package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixedQuantityStrategyTest {

    @Test
    void scalesPendingOrderQuantity() {
        Strategy inner = new StubStrategy(500);
        Strategy wrapped = new FixedQuantityStrategy(inner, 5_000);

        wrapped.onBar(sampleBar());
        Order order = wrapped.getPendingOrders().getFirst();

        assertEquals(5_000, order.quantity(), 1e-9);
    }

    @Test
    void fillStatusUpdatesDelegateOrder() {
        StubStrategy inner = new StubStrategy(500);
        Strategy wrapped = new FixedQuantityStrategy(inner, 5_000);

        wrapped.onBar(sampleBar());
        Order order = wrapped.getPendingOrders().getFirst();
        order.fill();

        assertEquals(Order.Status.FILLED, inner.pendingStatus());
    }

    private static Bar sampleBar() {
        Instant t = Instant.parse("2020-01-01T00:00:00Z");
        return new Bar("EUR_USD", t, 1.1, 1.11, 1.09, 1.105, 0);
    }

    private static final class StubStrategy implements Strategy {
        private final double quantity;
        private final List<Order> pending = new ArrayList<>();
        private Order lastSubmitted;

        StubStrategy(double quantity) {
            this.quantity = quantity;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public void onBar(Bar bar) {
            lastSubmitted = new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, quantity, 0);
            pending.add(lastSubmitted);
        }

        @Override
        public void onTick(double bid, double ask, long volume) {}

        @Override
        public List<Order> getPendingOrders() {
            List<Order> copy = List.copyOf(pending);
            pending.clear();
            return copy;
        }

        Order.Status pendingStatus() {
            return lastSubmitted == null ? Order.Status.PENDING : lastSubmitted.status();
        }

        @Override
        public void reset() {
            pending.clear();
            lastSubmitted = null;
        }
    }
}
