package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.broker.FakeBroker;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.oanda.StubOandaRestClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BrokerRunExecutorTest {

    @Test
    void execute_journalsBrokerEventsAndEnded() {
        try (EventStore store = EventStores.inMemory()) {
            var broker = new FakeBroker(100_000.0);
            Strategy strategy = new BuyOnceStrategy();
            List<Bar> bars = List.of(
                new Bar("EUR_USD", Instant.parse("2024-01-01T00:00:00Z"), 1.10, 1.11, 1.09, 1.105, 1000),
                new Bar("EUR_USD", Instant.parse("2024-01-01T01:00:00Z"), 1.105, 1.12, 1.10, 1.115, 1000));

            RunConfigSnapshot config = new RunConfigSnapshot(
                "Test",
                "EUR_USD",
                "PAPER",
                "sample",
                2,
                null,
                100_000.0,
                null,
                null,
                ExecutionLabel.PAPER_OANDA.name());

            BrokerRunExecutor.execute("run-1", config, bars, 100_000.0, strategy, broker, store, null);

            var events = store.replayAll("run-1");
            assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.RUN_STARTED));
            assertEquals(2, events.stream().filter(e -> e.type() == RunEventType.HEARTBEAT).count());
            assertEquals(
                Instant.parse("2024-01-01T00:00:00Z"),
                events.stream().filter(e -> e.type() == RunEventType.HEARTBEAT).findFirst().orElseThrow().timestamp());
            assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.ORDER_SUBMITTED));
            assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.FILL));
            assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.RUN_ENDED));
            assertTrue(events.getFirst().payload().containsKey("executionLabel"));
        }
    }

    @Test
    void execute_liveMode_journalsBrokerEventsWithLiveLabel() {
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

            BrokerRunExecutor.execute("run-live", config, bars, 100_000.0, strategy, broker, store, null);

            var events = store.replayAll("run-live");
            assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.RUN_STARTED));
            assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.FILL));
            assertEquals("LIVE", events.getFirst().mode());
            assertEquals("LIVE_OANDA", events.getFirst().payload().get("executionLabel"));
        }
    }

    @Test
    void execute_killSwitchBlocksOrders() {
        try (EventStore store = EventStores.inMemory()) {
            KillSwitchRegistry registry = new KillSwitchRegistry();
            registry.kill("Test");
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

            BrokerRunExecutor.execute("run-kill", config, bars, 100_000.0, strategy, broker, store, registry);

            var events = store.replayAll("run-kill");
            assertTrue(events.stream().noneMatch(e -> e.type() == RunEventType.FILL));
            assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.REJECT
                && e.payload().toString().contains("Kill switch")));
        }
    }

    @Test
    void ibkrBroker_stubClient_placesOrder() {
        var client = new com.martinfou.trading.data.ibkr.StubIbkrGatewayClient();
        var broker = BrokerProvider.ibkrBroker(client);
        broker.connect();

        var order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000, 1.10);
        assertTrue(broker.submitOrder(order).accepted());
        assertEquals(1, broker.getPositions().size());
    }

    @Test
    void brokerRunExecutor_ibkr_journalsFillEvents() {
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
                ExecutionLabel.PAPER_IBKR.name());

            BrokerRunExecutor.execute(
                "run-ibkr",
                config,
                bars,
                100_000.0,
                strategy,
                BrokerProvider.ibkrBroker(new com.martinfou.trading.data.ibkr.StubIbkrGatewayClient()),
                store,
                null,
                null,
                null);

            var events = store.replayAll("run-ibkr");
            assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.FILL));
            assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.RUN_STARTED
                && ExecutionLabel.PAPER_IBKR.name().equals(e.payload().get("executionLabel"))));
        }
    }

    @Test
    void oandaBroker_stubClient_placesOrder() {
        var client = new StubOandaRestClient();
        var broker = BrokerProvider.oandaBroker(client);
        broker.connect();

        var order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000, 1.10);
        assertTrue(broker.submitOrder(order).accepted());
        assertEquals(1, broker.getPositions().size());
    }

    @Test
    void startRun_liveOanda_withoutCredentials_rejected() {
        assumeTrue(BrokerProvider.oandaBrokerFromEnvironment().isEmpty(),
            "Skipped: OANDA credentials present in environment");

        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            assertThrows(IllegalArgumentException.class, () ->
                manager.startRun(new RunManager.StartRunRequest(
                    "LondonOpenRangeBreakout",
                    "EUR_USD",
                    "LIVE",
                    new BarSourceResolver.BarsSource("sample", 50, null),
                    100_000.0,
                null,
                    null,
                    null,
                    ExecutionLabel.LIVE_OANDA.name())));
        }
    }

    @Test
    void startRun_paperOanda_withoutCredentials_rejected() throws Exception {
        assumeTrue(BrokerProvider.oandaBrokerFromEnvironment().isEmpty(),
            "Skipped: OANDA credentials present in environment");

        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            assertThrows(IllegalArgumentException.class, () ->
                manager.startRun(new RunManager.StartRunRequest(
                    "LondonOpenRangeBreakout",
                    "EUR_USD",
                    "PAPER",
                    new BarSourceResolver.BarsSource("sample", 50, null),
                    100_000.0,
                null,
                    null,
                    null,
                    ExecutionLabel.PAPER_OANDA.name())));
        }
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
