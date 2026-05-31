package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeBrokerTest {

    @Test
    void submitOrder_marketFill_emitsSubmittedAndFill() {
        var broker = new FakeBroker(100_000.0);
        List<BrokerEvent> events = new ArrayList<>();
        broker.addEventListener(events::add);

        Order order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 10_000, 1.1000);
        OrderSubmitResult result = broker.submitOrder(order);

        assertTrue(result.accepted());
        assertEquals(order.id(), result.brokerOrderId());
        assertEquals(2, events.size());
        assertEquals(BrokerEventType.ORDER_SUBMITTED, events.get(0).type());
        assertEquals(BrokerEventType.FILL, events.get(1).type());
        assertEquals(1, broker.getPositions().size());
        assertEquals(10_000, broker.getPositions().getFirst().quantity(), 0.001);
    }

    @Test
    void submitOrder_whenDisconnected_rejectsWithEvent() {
        var broker = new FakeBroker(100_000.0);
        List<BrokerEvent> events = new ArrayList<>();
        broker.addEventListener(events::add);
        broker.disconnect();

        Order order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1_000, 1.1000);
        OrderSubmitResult result = broker.submitOrder(order);

        assertFalse(result.accepted());
        assertTrue(events.stream().anyMatch(e -> e.type() == BrokerEventType.REJECT));
        assertTrue(broker.getPositions().isEmpty());
    }

    @Test
    void reconnect_restoresConnectivity() {
        var broker = new FakeBroker(100_000.0);
        broker.disconnect();
        assertFalse(broker.isConnected());

        broker.reconnect();
        assertTrue(broker.isConnected());

        Order order = new Order("EUR_USD", Order.Side.SELL, Order.Type.MARKET, 500, 1.2000);
        assertTrue(broker.submitOrder(order).accepted());
    }

    @Test
    void getAccountState_returnsInitialBalance() {
        var broker = new FakeBroker(50_000.0, "USD", order -> false);
        AccountState state = broker.getAccountState();
        assertEquals(50_000.0, state.balance());
        assertEquals(50_000.0, state.equity());
        assertEquals("USD", state.currency());
    }

    @Test
    void submitOrder_limitOrder_rejected() {
        var broker = new FakeBroker(100_000.0);
        List<BrokerEvent> events = new ArrayList<>();
        broker.addEventListener(events::add);

        Order order = new Order("EUR_USD", Order.Side.BUY, Order.Type.LIMIT, 1_000, 1.0900);
        OrderSubmitResult result = broker.submitOrder(order);

        assertFalse(result.accepted());
        assertTrue(events.stream().anyMatch(e -> e.type() == BrokerEventType.REJECT));
    }
}
