package com.martinfou.trading.backtest.reconciliation;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.backtest.persistence.SqliteTradeAlignmentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public final class TradeReconcilerTest {

    private final TradeReconciler reconciler = new TradeReconciler();

    @Test
    public void testReconciliationPerfectMatch() {
        Instant fillTime = Instant.parse("2026-06-20T12:00:00Z");
        
        Order btOrder = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000.0, 1.0850)
            .withCorrelationId("sig-1")
            .withStatus(Order.Status.FILLED)
            .withFilledAt(fillTime);
            
        Order liveOrder = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000.0, 1.0850)
            .withCorrelationId("sig-1")
            .withStatus(Order.Status.FILLED)
            .withFilledAt(fillTime);

        ReconciliationConfig config = new ReconciliationConfig(5L, 0.0002);
        List<ReconciliationAnomaly> anomalies = reconciler.reconcile(List.of(btOrder), List.of(liveOrder), config);
        
        assertTrue(anomalies.isEmpty(), "Expected no anomalies for perfect match");
    }

    @Test
    public void testReconciliationMissingLiveOrder() {
        Instant fillTime = Instant.parse("2026-06-20T12:00:00Z");
        
        Order btOrder = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000.0, 1.0850)
            .withCorrelationId("sig-1")
            .withStatus(Order.Status.FILLED)
            .withFilledAt(fillTime);

        ReconciliationConfig config = new ReconciliationConfig(5L, 0.0002);
        List<ReconciliationAnomaly> anomalies = reconciler.reconcile(List.of(btOrder), List.of(), config);
        
        assertEquals(1, anomalies.size());
        assertEquals(ReconciliationAnomaly.AnomalyType.MISSING_LIVE, anomalies.get(0).type());
        assertEquals(btOrder.id(), anomalies.get(0).orderId());
    }

    @Test
    public void testReconciliationGhostLiveOrder() {
        Instant fillTime = Instant.parse("2026-06-20T12:00:00Z");
        
        Order liveOrder = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000.0, 1.0850)
            .withCorrelationId("sig-2")
            .withStatus(Order.Status.FILLED)
            .withFilledAt(fillTime);

        ReconciliationConfig config = new ReconciliationConfig(5L, 0.0002);
        List<ReconciliationAnomaly> anomalies = reconciler.reconcile(List.of(), List.of(liveOrder), config);
        
        assertEquals(1, anomalies.size());
        assertEquals(ReconciliationAnomaly.AnomalyType.GHOST_LIVE, anomalies.get(0).type());
        assertEquals(liveOrder.id(), anomalies.get(0).orderId());
    }

    @Test
    public void testReconciliationTimeDrift() {
        Instant btFillTime = Instant.parse("2026-06-20T12:00:00Z");
        Instant liveFillTime = Instant.parse("2026-06-20T12:00:10Z"); // 10s difference
        
        Order btOrder = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000.0, 1.0850)
            .withCorrelationId("sig-1")
            .withStatus(Order.Status.FILLED)
            .withFilledAt(btFillTime);
            
        Order liveOrder = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000.0, 1.0850)
            .withCorrelationId("sig-1")
            .withStatus(Order.Status.FILLED)
            .withFilledAt(liveFillTime);

        ReconciliationConfig config = new ReconciliationConfig(5L, 0.0002); // 5s tolerance
        List<ReconciliationAnomaly> anomalies = reconciler.reconcile(List.of(btOrder), List.of(liveOrder), config);
        
        assertEquals(1, anomalies.size());
        assertEquals(ReconciliationAnomaly.AnomalyType.TIME_DRIFT, anomalies.get(0).type());
        assertEquals(liveOrder.id(), anomalies.get(0).orderId());
        assertEquals(10000L, anomalies.get(0).deltaTimeMs());
    }

    @Test
    public void testReconciliationPriceDrift() {
        Instant fillTime = Instant.parse("2026-06-20T12:00:00Z");
        
        Order btOrder = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000.0, 1.0850)
            .withCorrelationId("sig-1")
            .withStatus(Order.Status.FILLED)
            .withFilledAt(fillTime);
            
        Order liveOrder = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000.0, 1.0855) // 0.0005 price diff
            .withCorrelationId("sig-1")
            .withStatus(Order.Status.FILLED)
            .withFilledAt(fillTime);

        ReconciliationConfig config = new ReconciliationConfig(5L, 0.0002); // 0.0002 price tolerance
        List<ReconciliationAnomaly> anomalies = reconciler.reconcile(List.of(btOrder), List.of(liveOrder), config);
        
        assertEquals(1, anomalies.size());
        assertEquals(ReconciliationAnomaly.AnomalyType.PRICE_DRIFT, anomalies.get(0).type());
        assertEquals(liveOrder.id(), anomalies.get(0).orderId());
        assertEquals(0.0005, anomalies.get(0).deltaPrice(), 1e-6);
    }

    @Test
    public void testProximityMatchFallback() {
        Instant btTime = Instant.parse("2026-06-20T12:00:00Z");
        Instant liveTime = Instant.parse("2026-06-20T12:00:04Z");
        
        // No correlation ID
        Order btOrder = new Order("EUR_USD", Order.Side.SELL, Order.Type.MARKET, 5000.0, 1.0850)
            .withStatus(Order.Status.FILLED)
            .withFilledAt(btTime);
            
        Order liveOrder = new Order("EUR_USD", Order.Side.SELL, Order.Type.MARKET, 5000.0, 1.0850)
            .withStatus(Order.Status.FILLED)
            .withFilledAt(liveTime);

        ReconciliationConfig config = new ReconciliationConfig(5L, 0.0002);
        List<ReconciliationAnomaly> anomalies = reconciler.reconcile(List.of(btOrder), List.of(liveOrder), config);
        
        assertTrue(anomalies.isEmpty(), "Should match by proximity and have no drift anomalies (4s < 5s)");
    }

    @Test
    public void testSqliteTradeAlignmentStore(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("test-events.db");
        try (SqliteTradeAlignmentStore store = new SqliteTradeAlignmentStore(dbPath)) {
            ReconciliationAnomaly anomaly1 = new ReconciliationAnomaly(
                ReconciliationAnomaly.AnomalyType.PRICE_DRIFT,
                "order-123",
                "Price drift of 0.0005",
                0.0005,
                2000L
            );
            
            ReconciliationAnomaly anomaly2 = new ReconciliationAnomaly(
                ReconciliationAnomaly.AnomalyType.MISSING_LIVE,
                "order-456",
                "Missing live trade",
                0.0,
                0L
            );
            
            store.insert("run-abc", anomaly1);
            store.insertAll("run-abc", List.of(anomaly2));
            
            List<ReconciliationAnomaly> list = store.getAnomalies("run-abc");
            assertEquals(2, list.size());
            
            ReconciliationAnomaly fetched1 = list.stream().filter(a -> a.type() == ReconciliationAnomaly.AnomalyType.PRICE_DRIFT).findFirst().orElse(null);
            assertNotNull(fetched1);
            assertEquals("order-123", fetched1.orderId());
            assertEquals(0.0005, fetched1.deltaPrice(), 1e-6);
            assertEquals(2000L, fetched1.deltaTimeMs());
            
            store.deleteForRun("run-abc");
            assertTrue(store.getAnomalies("run-abc").isEmpty());
        }
    }
}
