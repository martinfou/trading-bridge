package com.martinfou.trading.runtime;

import com.martinfou.trading.broker.FakeBroker;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.oanda.StubOandaRestClient;
import com.martinfou.trading.data.oanda.OandaStreamingClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OandaStreamingExecutorTest {

    @Test
    void bootstrapHistory_discardsPendingOrders() {
        try (EventStore store = EventStores.inMemory()) {
            var broker = new FakeBroker(100_000.0);
            var strategy = new BuyOnceStrategy();

            RunConfigSnapshot config = new RunConfigSnapshot(
                "Test",
                "EUR_USD",
                "PAPER",
                "sample",
                10,
                null,
                100_000.0,
                null,
                null,
                ExecutionLabel.PAPER_OANDA.name());

            var restClient = new StubOandaRestClient();
            var streamingClient = new OandaStreamingClient("", "", true);

            var executor = new OandaStreamingExecutor(
                "run-1",
                config,
                strategy,
                broker,
                restClient,
                store,
                new KillSwitchRegistry(),
                streamingClient,
                null
            );

            // Invoke private bootstrapHistory via reflection
            java.lang.reflect.Method method = OandaStreamingExecutor.class.getDeclaredMethod("bootstrapHistory");
            method.setAccessible(true);
            method.invoke(executor);

            // Assert that strategy's pending orders were cleared
            assertTrue(strategy.getPendingOrders().isEmpty(),
                "Pending orders generated during history bootstrap must be discarded");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class BuyOnceStrategy implements Strategy {
        private boolean ordered;
        private final List<Order> pending = new ArrayList<>();

        @Override
        public String name() {
            return "BuyOnce";
        }

        @Override
        public void onBar(Bar bar) {
            if (!ordered) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 1000, bar.open()));
                ordered = true;
            }
        }

        @Override
        public void onTick(double bid, double ask, long volume) {}

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
