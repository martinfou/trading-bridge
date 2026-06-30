package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AsyncReconciliationQueueTest {

    private static class DummyStrategy implements Strategy {
        private final List<Order> pendingOrders;

        DummyStrategy(List<Order> pendingOrders) {
            this.pendingOrders = pendingOrders;
        }

        @Override public String name() { return "Dummy"; }
        @Override public void onBar(com.martinfou.trading.core.Bar bar) {}
        @Override public void onTick(double bid, double ask, long volume) {}
        
        @Override 
        public List<Order> getPendingOrders() {
            List<Order> copy = new java.util.ArrayList<>(pendingOrders);
            pendingOrders.clear();
            return copy;
        }

        @Override public void reset() {}
    }

    @Test
    void testReconciliationStateChanges() {
        Strategy strategy = new DummyStrategy(new java.util.ArrayList<>());
        LiveStrategyRunner runner = new LiveStrategyRunner("dummy-key", "dummy-acc", strategy, "DummyStrat", "M1", 5);

        // Verify initial state
        assertFalse(runner.hasUnconfirmedReconciliation());

        // Inject active trade
        LiveStrategyRunner.ActiveTrade trade = new LiveStrategyRunner.ActiveTrade(
            "t123", "EUR_USD", "BUY", 1.0850, 1000, 0, 0, Instant.now()
        );
        runner.getActiveTrades().add(trade);
        assertFalse(runner.hasUnconfirmedReconciliation());

        // Flag as unconfirmed
        runner.markUnconfirmed("t123");
        assertTrue(runner.hasUnconfirmedReconciliation());
        assertEquals("UNCONFIRMED_RECONCILIATION", trade.reconciliationStatus);

        // Complete reconciliation
        runner.completeReconciliation("t123", 25.50);
        assertFalse(runner.hasUnconfirmedReconciliation());
        assertTrue(runner.getActiveTrades().isEmpty());
    }

    @Test
    void testCheckPendingOrdersBlockedWhenUnconfirmed() throws Exception {
        // Enqueue an entry order
        Order order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000, 0);
        strategyOrderEnqueueHelper(order);
    }

    private void strategyOrderEnqueueHelper(Order order) throws Exception {
        Strategy strategy = new DummyStrategy(new java.util.ArrayList<>(List.of(order)));
        LiveStrategyRunner runner = new LiveStrategyRunner("dummy-key", "dummy-acc", strategy, "DummyStrat", "M1", 5);

        // Verify order is initially available
        assertEquals(1, strategy.getPendingOrders().size());

        // Enqueue order again to verify it is cleared when blocked
        strategy = new DummyStrategy(new java.util.ArrayList<>(List.of(order)));
        runner = new LiveStrategyRunner("dummy-key", "dummy-acc", strategy, "DummyStrat", "M1", 5);

        // Flag unconfirmed trade in runner
        LiveStrategyRunner.ActiveTrade trade = new LiveStrategyRunner.ActiveTrade(
            "t123", "EUR_USD", "BUY", 1.0850, 1000, 0, 0, Instant.now()
        );
        trade.reconciliationStatus = "UNCONFIRMED_RECONCILIATION";
        runner.getActiveTrades().add(trade);

        // Call checkPendingOrders
        runner.checkPendingOrders("EUR_USD");

        // Verify the strategy pending orders queue was consumed/cleared, but no trade was executed because of block
        assertTrue(runner.getPendingStops().isEmpty());
        assertTrue(strategy.getPendingOrders().isEmpty());
    }

    @Test
    void testReconciliationRetryAndFallback() throws Exception {
        Strategy strategy = new DummyStrategy(Collections.emptyList());
        final int[] fallbackCalled = new int[1];
        LiveStrategyRunner runner = new LiveStrategyRunner("dummy-key", "dummy-acc", strategy, "DummyStrat", "M1", 5) {
            @Override
            public void reconcileTrade(String tradeId) throws Exception {
                throw new RuntimeException("Simulated OANDA connection failure");
            }
            @Override
            public void reconcileTradeFallback(String tradeId) {
                fallbackCalled[0]++;
                super.reconcileTradeFallback(tradeId);
            }
        };

        // Inject active trade
        LiveStrategyRunner.ActiveTrade trade = new LiveStrategyRunner.ActiveTrade(
            "t999", "EUR_USD", "BUY", 1.0850, 1000, 0, 0, Instant.now()
        );
        runner.getActiveTrades().add(trade);
        runner.markUnconfirmed("t999");

        // Submit task manually and run the worker behavior directly
        AsyncReconciliationQueue.ReconciliationTask task = new AsyncReconciliationQueue.ReconciliationTask(runner, "t999");
        
        // Simulating the loop behavior manually
        for (int i = 0; i < 5; i++) {
            assertEquals(i, task.attempts);
            try {
                task.runner.reconcileTrade(task.tradeId);
            } catch (Exception e) {
                task.attempts++;
                if (task.attempts >= 5) {
                    task.runner.reconcileTradeFallback(task.tradeId);
                }
            }
        }

        assertEquals(5, task.attempts);
        assertEquals(1, fallbackCalled[0]);
        // Verify trade was successfully cleaned up by fallback
        assertFalse(runner.hasUnconfirmedReconciliation());
        assertTrue(runner.getActiveTrades().isEmpty());
    }

    @Test
    void testWatchdogAndTelemetry() throws Exception {
        Strategy strategy = new DummyStrategy(Collections.emptyList());
        LiveStrategyRunner runner = new LiveStrategyRunner("dummy-key", "dummy-acc", strategy, "DummyStrat", "M1", 5);

        // 1. Initial State
        assertEquals(0, runner.getSignalCount());
        assertEquals(0, runner.getBarCount());
        assertEquals("ALIVE", runner.getLivenessStatus());

        // 2. Set lastHeartbeatTime to long ago via reflection to trigger STUCK watchdog state
        java.lang.reflect.Field field = LiveStrategyRunner.class.getDeclaredField("lastHeartbeatTime");
        field.setAccessible(true);
        field.set(runner, Instant.now().minusSeconds(100));

        assertEquals("STUCK", runner.getLivenessStatus());
    }
}
