package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IbkrOrderExecutionManager Unit Tests (Story 46.3)")
class IbkrOrderExecutionManagerTest {

    @Test
    @DisplayName("Should block duplicate order tags and allow distinct tags")
    void testIdempotencyTagging() {
        IbkrOrderExecutionManager manager = new IbkrOrderExecutionManager();
        Order o1 = new Order("MES", Order.Side.BUY, Order.Type.MARKET, 1.0, 5000.0);
        Order o2 = new Order("MES", Order.Side.BUY, Order.Type.MARKET, 1.0, 5000.0);

        String tag1 = Order.generateCanonicalTag("MES", "SQUEEZE", Instant.parse("2026-08-23T14:00:00Z"), Order.Side.BUY);

        assertTrue(manager.canSubmitOrder(o1, tag1));
        assertFalse(manager.canSubmitOrder(o2, tag1)); // Duplicate blocked

        String tag2 = Order.generateCanonicalTag("MES", "SQUEEZE", Instant.parse("2026-08-23T15:00:00Z"), Order.Side.BUY);
        assertTrue(manager.canSubmitOrder(o2, tag2)); // Distinct time allowed
    }

    @Test
    @DisplayName("Should accurately accumulate partial fills and calculate VWAP")
    void testPartialFillAccumulation() {
        IbkrOrderExecutionManager manager = new IbkrOrderExecutionManager();
        Order o = new Order("MES", Order.Side.BUY, Order.Type.MARKET, 3.0, 5000.0);
        manager.canSubmitOrder(o, "TAG-1");

        // Fill 1 contract @ 5000.00
        IbkrOrderExecutionManager.ExecutionTracker t1 = manager.recordFill(o.id(), 1.0, 5000.0);
        assertNotNull(t1);
        assertEquals(1.0, t1.cumulativeFilledQuantity());
        assertEquals(5000.0, t1.volumeWeightedPrice(), 0.001);
        assertEquals(Order.Status.PARTIAL, t1.status());
        assertFalse(t1.isComplete());

        // Fill 2 contracts @ 5003.00 -> VWAP = (1*5000 + 2*5003)/3 = 15006/3 = 5002.00
        IbkrOrderExecutionManager.ExecutionTracker t2 = manager.recordFill(o.id(), 2.0, 5003.0);
        assertNotNull(t2);
        assertEquals(3.0, t2.cumulativeFilledQuantity());
        assertEquals(5002.0, t2.volumeWeightedPrice(), 0.001);
        assertEquals(Order.Status.FILLED, t2.status());
        assertTrue(t2.isComplete());
    }

    @Test
    @DisplayName("Should pause strategy on margin rejection Error 201 and clear pause on demand")
    void testMarginRejectionHandling() {
        IbkrOrderExecutionManager manager = new IbkrOrderExecutionManager();
        String strat = "STRAT-MOMENTUM";
        Order o = new Order("MES", Order.Side.BUY, Order.Type.MARKET, 1.0, 5000.0).withStrategyId(strat);

        manager.canSubmitOrder(o, "TAG-MARG-1");

        // Report Error 201 (Insufficient margin)
        manager.recordError(o.id(), 201, "Order rejected - insufficient margin", strat);

        assertTrue(manager.isStrategyMarginPaused(strat));

        // Attempting another order with same strategy is blocked
        Order oNext = new Order("MES", Order.Side.BUY, Order.Type.MARKET, 1.0, 5000.0).withStrategyId(strat);
        assertFalse(manager.canSubmitOrder(oNext, "TAG-MARG-2"));

        // Clear margin pause
        manager.clearMarginPause(strat);
        assertFalse(manager.isStrategyMarginPaused(strat));
        assertTrue(manager.canSubmitOrder(oNext, "TAG-MARG-2"));
    }
}
