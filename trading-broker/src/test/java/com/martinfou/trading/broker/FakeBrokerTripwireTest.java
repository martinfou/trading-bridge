package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FakeBroker} is an in-memory test double that never touches a broker, so it is deliberately
 * NOT wired to {@code OrderTripwire} — exactly like {@code StubOandaRestClient} and
 * {@code StubIbkrGatewayClient}. This test pins that contract: with the tripwire armed (no
 * {@code TB_ALLOW_ORDERS} flag, Surefire test runtime), a FakeBroker order still fills in memory.
 */
class FakeBrokerTripwireTest {

    @Test
    void fakeBroker_stillFillsOrdersDespiteTripwire() {
        FakeBroker broker = new FakeBroker(100_000.0);
        broker.connect();

        var order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000, 1.10);
        OrderSubmitResult result = broker.submitOrder(order);

        assertTrue(result.accepted(), "FakeBroker is an in-memory double and must not be gated by the tripwire");
    }
}
