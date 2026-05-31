package com.martinfou.trading.runtime;

import com.martinfou.trading.broker.BrokerEventType;
import com.martinfou.trading.broker.OrderSubmitResult;
import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerProviderTest {

    @Test
    void fakeBroker_satisfiesRuntimeContract() {
        var broker = BrokerProvider.fakeBroker(100_000.0);
        List<com.martinfou.trading.broker.BrokerEvent> events = new ArrayList<>();
        broker.addEventListener(events::add);

        Order order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 5_000, 1.1050);
        OrderSubmitResult result = broker.submitOrder(order);

        assertTrue(result.accepted());
        assertEquals(2, events.size());
        assertEquals(BrokerEventType.ORDER_SUBMITTED, events.getFirst().type());
        assertEquals(BrokerEventType.FILL, events.getLast().type());
        assertEquals(100_000.0, broker.getAccountState().balance());
    }
}
