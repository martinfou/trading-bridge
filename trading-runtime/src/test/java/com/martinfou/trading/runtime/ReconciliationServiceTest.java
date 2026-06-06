package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.broker.AccountState;
import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.broker.FakeBroker;
import com.martinfou.trading.broker.OrderSubmitResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationServiceTest {

    private final ReconciliationService service = new ReconciliationService();

    @Test
    void reconcile_skippedForPaperStub() {
        try (EventStore store = EventStores.inMemory()) {
            RunConfigSnapshot config = new RunConfigSnapshot(
                "Test",
                "EUR_USD",
                "PAPER",
                "sample",
                1,
                null,
                100_000.0,
                null,
                null,
                ExecutionLabel.PAPER_STUB.name());

            ReconciliationService.ReconcileResult result = service.reconcile(
                "run-stub",
                config,
                new FakeBroker(100_000.0),
                store);

            assertTrue(result.skipped());
            assertTrue(result.aligned());
            assertTrue(store.replayAll("run-stub").isEmpty());
        }
    }

    @Test
    void reconcile_alignedWhenBrokerMatchesJournal() {
        try (EventStore store = EventStores.inMemory()) {
            var broker = new FakeBroker(100_000.0);
            Strategy strategy = new BuyOnceStrategy();
            List<Bar> bars = List.of(
                new Bar("EUR_USD", Instant.parse("2024-01-01T00:00:00Z"), 1.10, 1.11, 1.09, 1.105, 1000));

            RunConfigSnapshot config = new RunConfigSnapshot(
                "Test",
                "EUR_USD",
                "LIVE",
                "sample",
                1,
                null,
                100_000.0,
                null,
                null,
                ExecutionLabel.LIVE_OANDA.name());

            BrokerRunExecutor.execute("run-ok", config, bars, 100_000.0, strategy, broker, store, null);

            ReconciliationService.ReconcileResult result = service.reconcile("run-ok", config, broker, store);
            assertFalse(result.skipped());
            assertTrue(result.aligned());
            assertTrue(store.replayAll("run-ok").stream()
                .noneMatch(e -> e.type() == RunEventType.RECONCILIATION_ALERT));
        }
    }

    @Test
    void reconcile_emitsAlertOnGhostFillAtBroker() {
        try (EventStore store = EventStores.inMemory()) {
            RunConfigSnapshot config = new RunConfigSnapshot(
                "Test",
                "EUR_USD",
                "LIVE",
                "sample",
                1,
                null,
                100_000.0,
                null,
                null,
                ExecutionLabel.LIVE_OANDA.name());

            Broker divergent = new DivergentBroker();

            ReconciliationService.ReconcileResult result = service.reconcile(
                "run-div",
                config,
                divergent,
                store);

            assertFalse(result.skipped());
            assertFalse(result.aligned());
            assertEquals(1, result.divergences().size());
            assertTrue(result.divergences().getFirst().reason().contains("ghost fill"));

            var events = store.replayAll("run-div");
            assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.RECONCILIATION_ALERT));
        }
    }

    @Test
    void brokerRunExecutor_emitsReconciliationAlertWhenPositionsDiverge() {
        try (EventStore store = EventStores.inMemory()) {
            Strategy strategy = new BuyOnceStrategy();
            List<Bar> bars = List.of(
                new Bar("EUR_USD", Instant.parse("2024-01-01T00:00:00Z"), 1.10, 1.11, 1.09, 1.105, 1000));

            RunConfigSnapshot config = new RunConfigSnapshot(
                "Test",
                "EUR_USD",
                "PAPER",
                "sample",
                1,
                null,
                100_000.0,
                null,
                null,
                ExecutionLabel.PAPER_OANDA.name());

            BrokerRunExecutor.execute(
                "run-exec", config, bars, 100_000.0, strategy, new InflatingBroker(), store, null);

            assertTrue(store.replayAll("run-exec").stream()
                .anyMatch(e -> e.type() == RunEventType.RECONCILIATION_ALERT));
        }
    }

    /** After first fill, reports inflated quantity at broker vs journal. */
    private static final class InflatingBroker implements Broker {
        private final FakeBroker delegate = new FakeBroker(100_000.0);
        private volatile boolean inflate;

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public void connect() {
            delegate.connect();
        }

        @Override
        public void disconnect() {
            delegate.disconnect();
        }

        @Override
        public void reconnect() {
            delegate.reconnect();
        }

        @Override
        public OrderSubmitResult submitOrder(Order order) {
            var result = delegate.submitOrder(order);
            if (result.accepted()) {
                inflate = true;
            }
            return result;
        }

        @Override
        public OrderSubmitResult cancelOrder(String brokerOrderId) {
            return delegate.cancelOrder(brokerOrderId);
        }

        @Override
        public List<Position> getPositions() {
            if (!inflate) {
                return delegate.getPositions();
            }
            return delegate.getPositions().stream()
                .map(p -> new Position(p.symbol(), p.side(), p.quantity() + 5000, p.entryPrice()))
                .toList();
        }

        @Override
        public AccountState getAccountState() {
            return delegate.getAccountState();
        }

        @Override
        public void addEventListener(java.util.function.Consumer<com.martinfou.trading.broker.BrokerEvent> listener) {
            delegate.addEventListener(listener);
        }
    }

    /** Reports an extra broker position not reflected in the journal. */
    private static final class DivergentBroker implements Broker {
        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void connect() {}

        @Override
        public void disconnect() {}

        @Override
        public void reconnect() {}

        @Override
        public OrderSubmitResult submitOrder(Order order) {
            return OrderSubmitResult.rejected("not used");
        }

        @Override
        public OrderSubmitResult cancelOrder(String brokerOrderId) {
            return OrderSubmitResult.rejected("not used");
        }

        @Override
        public List<Position> getPositions() {
            return List.of(new Position("EUR_USD", Order.Side.BUY, 5000, 1.10));
        }

        @Override
        public AccountState getAccountState() {
            return new AccountState(100_000.0, 100_000.0, "USD");
        }

        @Override
        public void addEventListener(java.util.function.Consumer<com.martinfou.trading.broker.BrokerEvent> listener) {}
    }

    private static final class BuyOnceStrategy implements Strategy {
        private boolean ordered;

        @Override
        public String name() {
            return "BuyOnce";
        }

        @Override
        public void onBar(Bar bar) {
            if (!ordered) {
                submitOrder(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 1000, bar.open()));
                ordered = true;
            }
        }

        @Override
        public void onTick(double bid, double ask, long volume) {}

        private final List<Order> pending = new ArrayList<>();

        private void submitOrder(Order order) {
            pending.add(order);
        }

        @Override
        public List<Order> getPendingOrders() {
            List<Order> copy = List.copyOf(pending);
            pending.clear();
            return copy;
        }

        @Override
        public void reset() {
            ordered = false;
            pending.clear();
        }
    }
}
