package com.martinfou.trading.broker;

import com.martinfou.trading.data.oanda.OandaAccountSnapshot;
import com.martinfou.trading.data.oanda.OandaMarketOrderResult;
import com.martinfou.trading.data.oanda.OandaPositionSnapshot;
import com.martinfou.trading.data.oanda.OandaRestClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OandaBrokerTest {

    @Test
    void toOandaInstrument_normalizesSymbol() {
        assertEquals("EUR_USD", OandaBroker.toOandaInstrument("EUR_USD"));
        assertEquals("EUR_USD", OandaBroker.toOandaInstrument("EUR/USD"));
    }

    @Test
    void submitOrder_delegatesToRestClient() {
        var client = new RecordingClient();
        var broker = new OandaBroker(client);
        broker.connect();

        var order = new com.martinfou.trading.core.Order(
            "EUR_USD", com.martinfou.trading.core.Order.Side.BUY,
            com.martinfou.trading.core.Order.Type.MARKET, 1000, 1.10);
        var result = broker.submitOrder(order);

        assertTrue(result.accepted());
        assertEquals("1", result.brokerOrderId());
        assertEquals("EUR_USD", client.lastInstrument);
        assertEquals(1000L, client.lastUnits);
    }

    @Test
    void submitOrder_whenNotConnected_rejects() {
        var broker = new OandaBroker(new RecordingClient());
        var order = new com.martinfou.trading.core.Order(
            "EUR_USD", com.martinfou.trading.core.Order.Side.BUY,
            com.martinfou.trading.core.Order.Type.MARKET, 100, 1.10);
        assertFalse(broker.submitOrder(order).accepted());
    }

    @Test
    void testKeepaliveFailsConnectedFalse() throws Exception {
        var client = new RecordingClient();
        var broker = new OandaBroker(client, 10);
        
        java.util.List<BrokerEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        broker.addEventListener(events::add);
        
        broker.connect();
        assertTrue(broker.isConnected());
        
        client.throwOnFetchSummary = true;
        
        for (int i = 0; i < 50; i++) {
            if (!broker.isConnected()) break;
            Thread.sleep(10);
        }
        
        assertFalse(broker.isConnected());
        assertTrue(events.stream().anyMatch(e -> e.type() == BrokerEventType.CONNECTION && e.message().startsWith("DISCONNECTED")));
    }

    @Test
    void testReconnectRestoresState() {
        var client = new RecordingClient();
        var broker = new OandaBroker(client);
        broker.connect();
        assertTrue(broker.isConnected());
        
        broker.reconnect();
        assertTrue(broker.isConnected());
        assertEquals(1, client.resetCount);
    }

    @Test
    void testRateLimitQueueing() throws Exception {
        var client = new RecordingClient();
        // 30s keepalive, 100ms window, 50 max orders, 10ms delay
        var broker = new OandaBroker(client, 30_000, 100, 50, 10);
        broker.connect();
        
        java.util.List<BrokerEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        broker.addEventListener(events::add);
        
        long start = System.currentTimeMillis();
        for (int i = 0; i < 51; i++) {
            var order = new com.martinfou.trading.core.Order(
                "EUR_USD", com.martinfou.trading.core.Order.Side.BUY,
                com.martinfou.trading.core.Order.Type.MARKET, 1000, 1.10);
            broker.submitOrder(order);
        }
        long duration = System.currentTimeMillis() - start;
        
        assertTrue(duration >= 10, "Should have delayed 51st order by at least 10ms. Duration: " + duration);
        assertTrue(events.stream().anyMatch(e -> e.type() == BrokerEventType.RATE_LIMIT));
    }

    @Test
    void testBackoffDelays() throws Exception {
        var client = new RecordingClient();
        var broker = new OandaBroker(client);
        client.throwOnFetchSummary = true;
        
        try {
            broker.reconnect();
        } catch (Exception e) {
            // expected
        }
        assertEquals(1, broker.getRetryCount());
    }

    @Test
    void testOrderRejectRateAlerting() {
        var client = new RecordingClient() {
            @Override
            public OandaMarketOrderResult placeMarketOrder(String instrument, long units, String clientTag) {
                return OandaMarketOrderResult.failure(400, "Insufficient margin");
            }
        };
        var broker = new OandaBroker(client);
        broker.connect();

        for (int i = 0; i < 5; i++) {
            var order = new com.martinfou.trading.core.Order(
                "EUR_USD", com.martinfou.trading.core.Order.Side.BUY,
                com.martinfou.trading.core.Order.Type.MARKET, 1000, 1.10);
            var result = broker.submitOrder(order);
            assertFalse(result.accepted());
        }

        assertEquals(5, broker.totalOrdersSubmitted());
        assertEquals(5, broker.totalOrdersRejected());
    }

    private static class RecordingClient implements OandaRestClient {
        String lastInstrument;
        long lastUnits;
        boolean throwOnFetchSummary = false;
        int resetCount = 0;

        @Override
        public OandaMarketOrderResult placeMarketOrder(String instrument, long units, String clientTag) {
            lastInstrument = instrument;
            lastUnits = units;
            return OandaMarketOrderResult.success("1", "T1", 1.1001);
        }

        @Override
        public OandaMarketOrderResult placeOrder(String type, String instrument, long units, double price, double stopLoss, double takeProfit, double trailingStop, boolean guaranteed, String clientTag) {
            lastInstrument = instrument;
            lastUnits = units;
            return OandaMarketOrderResult.success("1", "T1", price > 0 ? price : 1.1001);
        }

        @Override
        public boolean cancelOrder(String orderId) {
            return true;
        }

        @Override
        public double closeTrade(String tradeId, String units) {
            return 1.1001;
        }

        @Override
        public java.util.List<java.util.Map<String, Object>> fetchTransactions(int limit) {
            return java.util.List.of();
        }

        @Override
        public OandaAccountSnapshot fetchAccountSummary() {
            if (throwOnFetchSummary) {
                throw new RuntimeException("Simulated OANDA connection timeout");
            }
            return new OandaAccountSnapshot(100_000, 100_000, 0, "USD", 100_000, 0, 0);
        }

        @Override
        public java.util.List<OandaPositionSnapshot> fetchOpenPositions() {
            return java.util.List.of();
        }

        @Override
        public java.util.Map<String, Object> fetchOrderBook(String instrument) {
            return java.util.Map.of("instrument", instrument, "orderBook", java.util.Map.of());
        }

        @Override
        public void reset() {
            resetCount++;
        }
    }
}
