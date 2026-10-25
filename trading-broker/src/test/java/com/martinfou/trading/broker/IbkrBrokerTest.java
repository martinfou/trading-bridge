package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.ibkr.IbkrAccountSnapshot;
import com.martinfou.trading.data.ibkr.IbkrExecution;
import com.martinfou.trading.data.ibkr.IbkrGatewayClient;
import com.martinfou.trading.data.ibkr.IbkrMarketOrderResult;
import com.martinfou.trading.data.ibkr.IbkrPositionSnapshot;
import com.martinfou.trading.data.ibkr.StubIbkrGatewayClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IbkrBrokerTest {

    @Test
    void toIbkrSymbol_normalizesSymbol() {
        assertEquals("EURUSD", IbkrBroker.toIbkrSymbol("EUR_USD"));
        assertEquals("EURUSD", IbkrBroker.toIbkrSymbol("EUR/USD"));
    }

    @Test
    void submitOrder_delegatesToGatewayClient() {
        var client = new RecordingClient();
        var broker = new IbkrBroker(client);
        broker.connect();

        var order = new Order(
            "EUR_USD", Order.Side.BUY,
            Order.Type.MARKET, 1000, 1.10);
        var result = broker.submitOrder(order);

        assertTrue(result.accepted());
        assertEquals("1", result.brokerOrderId());
        assertEquals("EURUSD", client.lastSymbol);
        assertEquals(1000.0, client.lastQuantity);
    }

    @Test
    void flattenStampsTheControlRef() {
        // Un flatten ne nomme aucune strategie : son ref doit dire « flatten », sinon le close tombe en leak.
        var client = new RecordingClient();
        client.positions = List.of(new IbkrPositionSnapshot("EUR_USD", Order.Side.BUY, 1000, 1.10));
        var broker = new IbkrBroker(client);
        broker.connect();

        broker.flattenAllPositions();

        assertEquals("flatten", client.lastOrderRef);
    }

    @Test
    void submitOrder_carriesTheStrategyNameAsOrderRef() {
        // Sans ce champ, le fill arrive sans nom de strategie et la lane P&L le classe en leak : c'est la
        // seule chose qui rattache une execution a la strategie qui l'a produite.
        var client = new RecordingClient();
        var broker = new IbkrBroker(client);
        broker.connect();

        var order = new Order(
            "id-1", "EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000, 1.10,
            0.0, 0.0, 0.0, false, false, Order.Status.PENDING,
            java.time.Instant.now(), null, "mesbreakout", null, 0.0, null, 0, null);

        broker.submitOrder(order);

        assertEquals("mesbreakout", client.lastOrderRef);
    }

    @Test
    void submitOrder_whenNotConnected_rejects() {
        var broker = new IbkrBroker(new RecordingClient());
        var order = new Order(
            "EUR_USD", Order.Side.BUY,
            Order.Type.MARKET, 100, 1.10);
        assertFalse(broker.submitOrder(order).accepted());
    }

    @Test
    void submitOrder_doesNotFabricateFill() {
        var client = new RecordingClient();
        var broker = new IbkrBroker(client);
        broker.connect();

        List<BrokerEvent> events = new ArrayList<>();
        broker.addEventListener(events::add);

        var order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000, 1.10);
        var result = broker.submitOrder(order);

        assertTrue(result.accepted());
        // The order must remain PENDING (IBKR fills arrive async via execDetails).
        assertEquals(Order.Status.PENDING, order.status());
        // No FILL event may be emitted from a mere submission.
        assertTrue(events.stream().noneMatch(e -> e.type() == BrokerEventType.FILL));
    }

    @Test
    void cancelOrder_returnsRejected_notFilled() {
        var broker = new IbkrBroker(new RecordingClient());
        broker.connect();
        OrderSubmitResult result = broker.cancelOrder("999");
        assertFalse(result.accepted());
    }

    @Test
    void asyncFill_emitsSingleFillAndPurgesPendingOrder() {
        var client = new ListenerCapturingClient();
        var broker = new IbkrBroker(client);
        broker.connect();

        List<BrokerEvent> events = new ArrayList<>();
        broker.addEventListener(events::add);

        var order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000, 1.10);
        assertTrue(broker.submitOrder(order).accepted());

        // Fill arrives AFTER submitOrder returns (real IBKR ordering), delivered async.
        client.listener.accept(IbkrExecution.filled("1", "E1", order.id(), "EURUSD",
            Order.Side.BUY, 1000, 1.105));

        assertEquals(1, events.stream().filter(e -> e.type() == BrokerEventType.FILL).count());

        // Duplicate execDetails redelivery must NOT re-emit a fill (pending order already purged).
        client.listener.accept(IbkrExecution.filled("1", "E1", order.id(), "EURUSD",
            Order.Side.BUY, 1000, 1.105));
        assertEquals(1, events.stream().filter(e -> e.type() == BrokerEventType.FILL).count());
    }

    @Test
    void asyncReject_emitsRejectAndPurgesPendingOrder() {
        var client = new ListenerCapturingClient();
        var broker = new IbkrBroker(client);
        broker.connect();

        List<BrokerEvent> events = new ArrayList<>();
        broker.addEventListener(events::add);

        var order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000, 1.10);
        assertTrue(broker.submitOrder(order).accepted());

        client.listener.accept(IbkrExecution.rejected("1", order.id(), "EURUSD", "insufficient margin"));
        assertEquals(1, events.stream().filter(e -> e.type() == BrokerEventType.REJECT).count());

        client.listener.accept(IbkrExecution.rejected("1", order.id(), "EURUSD", "insufficient margin"));
        assertEquals(1, events.stream().filter(e -> e.type() == BrokerEventType.REJECT).count());
    }

    @Test
    void flattenAllPositions_submitsOppositeMarketOrders() {
        var client = new StubIbkrGatewayClient();
        var broker = new IbkrBroker(client, 3, 10);
        broker.connect();

        // Open a BUY position via the stub.
        broker.submitOrder(new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000, 1.10));
        assertEquals(1, broker.getPositions().size());

        int flattened = broker.flattenAllPositions();
        assertEquals(1, flattened);
        // Stub nets the closing SELL against the open BUY -> flat.
        assertEquals(0, broker.getPositions().size());
    }

    @Test
    void flattenAllPositions_doesNotClaimSuccessOnAnAcceptedButUnfilledClose() {
        var client = new StubIbkrGatewayClient();
        var broker = new IbkrBroker(client, 2, 10);
        broker.connect();

        broker.submitOrder(new Order("MES", Order.Side.BUY, Order.Type.MARKET, 1.0, 0.0));
        assertEquals(1, broker.getPositions().size());

        // The venue accepts the closing order but the position never goes away.
        client.scriptAcceptedWithoutFill("9999", 7766.5);

        assertEquals(0, broker.flattenAllPositions());
        assertEquals(1, broker.getPositions().size());
    }

    @Test
    void flattenAllPositions_doesNotClaimSuccessWhenThePositionQueryWasNeverAnswered() {
        var client = new StubIbkrGatewayClient();
        var broker = new IbkrBroker(client, 2, 10);
        broker.connect();

        broker.submitOrder(new Order("MES", Order.Side.BUY, Order.Type.MARKET, 1.0, 0.0));
        assertEquals(1, broker.getPositions().size());

        // The venue stops answering: an empty list would be ignorance, not flatness.
        client.positionQueryConfirmed(false);

        assertEquals(0, broker.flattenAllPositions());
    }

    private static final class ListenerCapturingClient implements IbkrGatewayClient {
        java.util.function.Consumer<IbkrExecution> listener;

        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public boolean isConnected() { return true; }

        @Override
        public IbkrMarketOrderResult placeMarketOrder(String symbol, double quantity, Order.Side side, String clientTag) {
            return IbkrMarketOrderResult.success("1", "E1", 1.10);
        }

        @Override public IbkrAccountSnapshot fetchAccountSummary() {
            return new IbkrAccountSnapshot(100_000, 100_000, "USD");
        }
        @Override public List<IbkrPositionSnapshot> fetchOpenPositions() {
            return List.of();
        }
        @Override public void addExecutionListener(java.util.function.Consumer<IbkrExecution> listener) {
            this.listener = listener;
        }
    }

    private static final class RecordingClient implements IbkrGatewayClient {
        String lastSymbol;
        double lastQuantity;
        String lastOrderRef;
        java.util.List<IbkrPositionSnapshot> positions = java.util.List.of();

        @Override
        public void connect() {}

        @Override
        public void disconnect() {}

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public IbkrMarketOrderResult placeMarketOrder(
            String symbol,
            double quantity,
            Order.Side side,
            String clientTag
        ) {
            lastSymbol = symbol;
            lastQuantity = quantity;
            return IbkrMarketOrderResult.success("1", "E1", 1.1001);
        }

        @Override
        public IbkrMarketOrderResult placeMarketOrder(String symbol, double quantity, Order.Side side,
                                                      String clientTag, String orderRef) {
            lastOrderRef = orderRef;
            return placeMarketOrder(symbol, quantity, side, clientTag);
        }

        @Override
        public IbkrAccountSnapshot fetchAccountSummary() {
            return new IbkrAccountSnapshot(100_000, 100_000, "USD");
        }

        @Override
        public List<IbkrPositionSnapshot> fetchOpenPositions() {
            return positions;
        }

        @Override
        public void addExecutionListener(java.util.function.Consumer<IbkrExecution> listener) {
            // No-op for recording client; async fills not simulated here.
        }
    }
}
