package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class IbkrTransactionRegistryTest {

    @Test
    void testExecutionDetailsArrivesFirstThenCommission() {
        IbkrTransactionRegistry registry = new IbkrTransactionRegistry();
        AtomicReference<IbkrTransactionRegistry.ReconciledTransaction> callbackRef = new AtomicReference<>();
        registry.addListener(callbackRef::set);

        String execId = "0001834a.65d.01";
        Instant t0 = Instant.parse("2024-01-02T10:00:00Z");

        // Barrier 1: Execution Details
        var opt1 = registry.onExecutionDetails(execId, "ord-101", "MES", Order.Side.BUY, 2.0, 5000.25, t0);
        assertTrue(opt1.isEmpty());
        assertEquals(1, registry.pendingExecutionCount());
        assertEquals(0, registry.pendingCommissionCount());
        assertNull(callbackRef.get());

        // Barrier 2: Commission Report
        var opt2 = registry.onCommissionReport(execId, 1.24, 0.0, "USD", t0.plusMillis(50));
        assertTrue(opt2.isPresent());
        assertEquals(0, registry.pendingExecutionCount());
        assertEquals(0, registry.pendingCommissionCount());

        var tx = opt2.get();
        assertEquals(execId, tx.execId());
        assertEquals("ord-101", tx.orderId());
        assertEquals("MES", tx.symbol());
        assertEquals(2.0, tx.quantity(), 1e-6);
        assertEquals(5000.25, tx.price(), 1e-6);
        assertEquals(1.24, tx.commission(), 1e-6);
        assertNotNull(callbackRef.get());
    }

    @Test
    void testCommissionArrivesFirstThenExecutionDetails() {
        IbkrTransactionRegistry registry = new IbkrTransactionRegistry();
        String execId = "0001834b.65e.02";
        Instant t0 = Instant.parse("2024-01-02T10:05:00Z");

        // Barrier 2 arrives first
        var opt1 = registry.onCommissionReport(execId, 2.50, 45.0, "USD", t0);
        assertTrue(opt1.isEmpty());
        assertEquals(0, registry.pendingExecutionCount());
        assertEquals(1, registry.pendingCommissionCount());

        // Barrier 1 arrives second
        var opt2 = registry.onExecutionDetails(execId, "ord-102", "IWM", Order.Side.SELL, 100.0, 205.50, t0.plusMillis(30));
        assertTrue(opt2.isPresent());
        assertEquals(0, registry.pendingExecutionCount());
        assertEquals(0, registry.pendingCommissionCount());

        var tx = opt2.get();
        assertEquals("IWM", tx.symbol());
        assertEquals(100.0, tx.quantity(), 1e-6);
        assertEquals(205.50, tx.price(), 1e-6);
        assertEquals(2.50, tx.commission(), 1e-6);
        assertEquals(45.0, tx.realizedPnL(), 1e-6);
    }
}
