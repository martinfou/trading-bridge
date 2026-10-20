package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.ibkr.IbkrOrderUpdate;
import com.martinfou.trading.data.ibkr.StubIbkrGatewayClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class IbkrBrokerTest {

    private static List<BrokerEvent> record(IbkrBroker broker) {
        List<BrokerEvent> events = new CopyOnWriteArrayList<>();
        broker.addEventListener(events::add);
        return events;
    }

    private static List<BrokerEvent> ofType(List<BrokerEvent> events, BrokerEventType type) {
        return events.stream().filter(e -> e.type() == type).toList();
    }

    @Test
    void marketOrderFillsWithRealPriceAndSingleFillEvent() {
        var stub = new StubIbkrGatewayClient().price("MES", 5012.25);
        var broker = new IbkrBroker(stub);
        var events = record(broker);
        broker.connect();

        var order = new Order("MES", Order.Side.BUY, Order.Type.MARKET, 2, 0);
        var result = broker.submitOrder(order);

        assertTrue(result.accepted());
        assertEquals("1", result.brokerOrderId());
        var fills = ofType(events, BrokerEventType.FILL);
        assertEquals(1, fills.size());
        assertEquals(5012.25, fills.get(0).price());
        assertEquals(2.0, fills.get(0).quantity());
        assertEquals(order.id(), fills.get(0).orderId());
        assertEquals(1, broker.getPositions().size());
        assertEquals(Order.Side.BUY, broker.getPositions().get(0).side());
    }

    @Test
    void limitOrderIsAcceptedWorkingThenFilledAsync() {
        var stub = new StubIbkrGatewayClient();
        var broker = new IbkrBroker(stub);
        var events = record(broker);
        broker.connect();

        var order = new Order("AAPL", Order.Side.BUY, Order.Type.LIMIT, 10, 180.0);
        var result = broker.submitOrder(order);
        assertTrue(result.accepted());
        assertTrue(ofType(events, BrokerEventType.FILL).isEmpty(), "limit order must not fill synchronously");
        assertEquals(180.0, stub.placedOrders().get(0).limitPrice());

        stub.fillWorkingOrder(result.brokerOrderId(), 179.95);
        var fills = ofType(events, BrokerEventType.FILL);
        assertEquals(1, fills.size());
        assertEquals(179.95, fills.get(0).price());
    }

    @Test
    void stopOrderUsesPriceAsTrigger() {
        var stub = new StubIbkrGatewayClient();
        var broker = new IbkrBroker(stub);
        broker.connect();
        broker.submitOrder(new Order("MES", Order.Side.SELL, Order.Type.STOP, 1, 4950.0));
        var req = stub.placedOrders().get(0);
        assertEquals(Order.Type.STOP, req.type());
        assertEquals(4950.0, req.stopPrice());
    }

    @Test
    void bracketChildFillCancelsSiblingAndCarriesCorrelation() {
        var stub = new StubIbkrGatewayClient().price("MES", 5000.0);
        var broker = new IbkrBroker(stub);
        var events = record(broker);
        broker.connect();

        var order = new Order("MES", Order.Side.BUY, Order.Type.MARKET, 1, 0, 4990.0, 5020.0)
            .withCorrelationId("corr-1");
        var result = broker.submitOrder(order);
        assertTrue(result.accepted());
        assertTrue(stub.placedOrders().get(0).hasBracket());

        var entry = ofType(events, BrokerEventType.FILL).get(0);
        assertEquals(4990.0, entry.stopLoss());
        assertEquals(5020.0, entry.takeProfit());

        stub.fillWorkingOrder("2", 5020.0); // TP child (ids: 1 parent, 2 TP, 3 SL)

        var fills = ofType(events, BrokerEventType.FILL);
        assertEquals(2, fills.size());
        assertEquals(order.id() + "-TP", fills.get(1).orderId());
        assertEquals("SELL", fills.get(1).side());
        assertEquals("corr-1", fills.get(1).correlationId());
        var cancels = ofType(events, BrokerEventType.REJECT);
        assertEquals(1, cancels.size());
        assertEquals(order.id() + "-SL", cancels.get(0).orderId());
        assertEquals("CANCELLED", cancels.get(0).message());
        assertTrue(broker.getPositions().isEmpty(), "TP exit flattens the position");
    }

    @Test
    void cancelOrderReallyCancels() {
        var stub = new StubIbkrGatewayClient();
        var broker = new IbkrBroker(stub);
        var events = record(broker);
        broker.connect();

        var order = new Order("MES", Order.Side.BUY, Order.Type.LIMIT, 1, 4000.0);
        var placed = broker.submitOrder(order);
        assertTrue(broker.cancelOrder(placed.brokerOrderId()).accepted());
        assertFalse(broker.cancelOrder(placed.brokerOrderId()).accepted(), "second cancel finds nothing working");

        var cancels = ofType(events, BrokerEventType.REJECT);
        assertEquals(1, cancels.size());
        assertEquals(order.id(), cancels.get(0).orderId());
        assertTrue(stub.fetchOpenOrders().isEmpty());
    }

    @Test
    void gatewayRejectIsReportedOnce() {
        var stub = new StubIbkrGatewayClient().scriptFailure("201: insufficient margin");
        var broker = new IbkrBroker(stub);
        var events = record(broker);
        broker.connect();

        var result = broker.submitOrder(new Order("MES", Order.Side.BUY, Order.Type.MARKET, 1, 0));
        assertFalse(result.accepted());
        assertEquals("201: insufficient margin", result.rejectReason());
        assertEquals(1, ofType(events, BrokerEventType.REJECT).size());
    }

    @Test
    void invalidOrderRejectedBeforeReachingGateway() {
        var stub = new StubIbkrGatewayClient();
        var broker = new IbkrBroker(stub);
        broker.connect();
        var result = broker.submitOrder(new Order("MES", Order.Side.BUY, Order.Type.LIMIT, 1, 0.0));
        assertFalse(result.accepted());
        assertTrue(stub.placedOrders().isEmpty());
    }

    @Test
    void submitOrder_whenNotConnected_rejects() {
        var broker = new IbkrBroker(new StubIbkrGatewayClient());
        var order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 100, 1.10);
        assertFalse(broker.submitOrder(order).accepted());
    }

    @Test
    void partialFillThenCancelEmitsPartialFillAndCancel() {
        var client = new ScriptedClient();
        var broker = new IbkrBroker(client, Duration.ZERO);
        var events = record(broker);
        broker.connect();
        var order = new Order("MES", Order.Side.BUY, Order.Type.LIMIT, 3, 5000.0);
        broker.submitOrder(order);

        client.push(exec(order.id(), 1, 5000.0, 1, 2));
        client.push(exec(order.id(), 1, 4999.5, 2, 1));
        assertTrue(ofType(events, BrokerEventType.FILL).isEmpty(), "no fill until complete or terminal");
        client.push(new IbkrOrderUpdate(IbkrOrderUpdate.Kind.CANCELLED, Instant.now(), "1", order.id(), "MES",
            Order.Side.BUY, 3, 0, 2, 1, 0, "Cancelled", null, "Cancelled"));

        var fills = ofType(events, BrokerEventType.FILL);
        assertEquals(1, fills.size());
        assertEquals(2.0, fills.get(0).quantity());
        assertEquals(4999.75, fills.get(0).price(), 1e-9);
        assertEquals("CANCELLED", ofType(events, BrokerEventType.REJECT).get(0).message());
    }

    @Test
    void connectionClosedMarksBrokerDisconnected() {
        var client = new ScriptedClient();
        var broker = new IbkrBroker(client, Duration.ZERO);
        var events = record(broker);
        broker.connect();
        client.push(IbkrOrderUpdate.connection("CLOSED", "socket closed"));
        assertFalse(broker.isConnected());
        assertEquals("CLOSED:socket closed", ofType(events, BrokerEventType.CONNECTION).get(0).message());
    }

    private static IbkrOrderUpdate exec(String ref, double qty, double price, double cum, double remaining) {
        return new IbkrOrderUpdate(IbkrOrderUpdate.Kind.EXECUTION, Instant.now(), "1", ref, "MES", Order.Side.BUY,
            qty, price, cum, remaining, price, "Submitted", "X" + cum, null);
    }

    /** Stub that accepts orders but lets the test push IB updates manually. */
    private static final class ScriptedClient implements com.martinfou.trading.data.ibkr.IbkrGatewayClient {
        private final List<java.util.function.Consumer<IbkrOrderUpdate>> listeners = new CopyOnWriteArrayList<>();

        void push(IbkrOrderUpdate u) {
            listeners.forEach(l -> l.accept(u));
        }

        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public boolean isConnected() { return true; }
        @Override public com.martinfou.trading.data.ibkr.IbkrOrderAck placeOrder(com.martinfou.trading.data.ibkr.IbkrOrderRequest r) {
            return com.martinfou.trading.data.ibkr.IbkrOrderAck.accepted("1", List.of(), "Submitted");
        }
        @Override public com.martinfou.trading.data.ibkr.IbkrOrderAck cancelOrder(String id) {
            return com.martinfou.trading.data.ibkr.IbkrOrderAck.accepted(id, List.of(), "PendingCancel");
        }
        @Override public void addOrderListener(java.util.function.Consumer<IbkrOrderUpdate> l) { listeners.add(l); }
        @Override public com.martinfou.trading.data.ibkr.IbkrAccountSnapshot fetchAccountSummary() {
            return new com.martinfou.trading.data.ibkr.IbkrAccountSnapshot(100_000, 100_000, "USD");
        }
        @Override public List<com.martinfou.trading.data.ibkr.IbkrPositionSnapshot> fetchOpenPositions() { return List.of(); }
    }
}
