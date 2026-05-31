package com.martinfou.trading.broker;

import com.martinfou.trading.data.ibkr.IbkrAccountSnapshot;
import com.martinfou.trading.data.ibkr.IbkrMarketOrderResult;
import com.martinfou.trading.data.ibkr.IbkrPositionSnapshot;
import com.martinfou.trading.data.ibkr.IbkrGatewayClient;
import org.junit.jupiter.api.Test;

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

        var order = new com.martinfou.trading.core.Order(
            "EUR_USD", com.martinfou.trading.core.Order.Side.BUY,
            com.martinfou.trading.core.Order.Type.MARKET, 1000, 1.10);
        var result = broker.submitOrder(order);

        assertTrue(result.accepted());
        assertEquals("1", result.brokerOrderId());
        assertEquals("EURUSD", client.lastSymbol);
        assertEquals(1000.0, client.lastQuantity);
    }

    @Test
    void submitOrder_whenNotConnected_rejects() {
        var broker = new IbkrBroker(new RecordingClient());
        var order = new com.martinfou.trading.core.Order(
            "EUR_USD", com.martinfou.trading.core.Order.Side.BUY,
            com.martinfou.trading.core.Order.Type.MARKET, 100, 1.10);
        assertFalse(broker.submitOrder(order).accepted());
    }

    private static final class RecordingClient implements IbkrGatewayClient {
        String lastSymbol;
        double lastQuantity;

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
            com.martinfou.trading.core.Order.Side side,
            String clientTag
        ) {
            lastSymbol = symbol;
            lastQuantity = quantity;
            return IbkrMarketOrderResult.success("1", "E1", 1.1001);
        }

        @Override
        public IbkrAccountSnapshot fetchAccountSummary() {
            return new IbkrAccountSnapshot(100_000, 100_000, "USD");
        }

        @Override
        public java.util.List<IbkrPositionSnapshot> fetchOpenPositions() {
            return java.util.List.of();
        }
    }
}
