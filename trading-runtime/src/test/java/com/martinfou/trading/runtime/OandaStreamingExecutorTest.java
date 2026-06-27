package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.broker.BrokerEvent;
import com.martinfou.trading.broker.OrderSubmitResult;
import com.martinfou.trading.broker.AccountState;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;

class OandaStreamingExecutorTest {

    @Test
    void testReconcilePositionsOnReconnectMismatch() {
        var store = new InMemoryEventStore();
        String runId = "test-run-1";
        var config = new RunConfigSnapshot(
            "LondonOpenRangeBreakout",
            "EUR_USD",
            "LIVE",
            "sample",
            500,
            null,
            1000.0,
            0.07,
            1e-4,
            "LIVE_OANDA",
            "acct1"
        );

        // Expected local position has 10,000 BUY units
        var fillPayload = Map.<String, Object>of(
            "symbol", "EUR_USD",
            "side", "BUY",
            "quantity", 10000.0
        );
        var fillEvent = new RunEvent(
            RunEvent.SCHEMA_VERSION,
            RunEventType.FILL,
            Instant.now(),
            runId,
            "LondonOpenRangeBreakout",
            "EUR_USD",
            "LIVE",
            fillPayload
        );
        store.append(runId, fillEvent);

        // Broker returns empty positions (mismatch)
        var broker = new StubBroker() {
            @Override
            public List<Position> getPositions() {
                return Collections.emptyList();
            }
        };

        var strategy = new Strategy() {
            @Override public String name() { return "stub"; }
            @Override public void onBar(com.martinfou.trading.core.Bar bar) {}
            @Override public void onTick(double bid, double ask, long time) {}
            @Override public List<Order> getPendingOrders() { return Collections.emptyList(); }
            @Override public void reset() {}
        };

        var executor = new OandaStreamingExecutor(
            runId,
            null,
            config,
            strategy,
            broker,
            null,
            store,
            new KillSwitchRegistry(),
            null,
            new RunRiskContext(new RiskEngine(), (run, cfg, m, check) -> {}, metrics -> {})
        );

        executor.reconnectBroker();

        // Verify that a RECONCILIATION_ALERT event was written
        List<RunEvent> events = store.replayAll(runId);
        boolean hasAlert = events.stream().anyMatch(e -> e.type() == RunEventType.RECONCILIATION_ALERT);
        assertTrue(hasAlert, "Expected RECONCILIATION_ALERT event due to expected vs actual position mismatch");
    }

    @Test
    void testClosedMarketOrderRejectionPersist() {
        var store = new InMemoryEventStore();
        String runId = "test-run-2";
        var config = new RunConfigSnapshot(
            "LondonOpenRangeBreakout",
            "EUR_USD",
            "LIVE",
            "sample",
            500,
            null,
            1000.0,
            0.07,
            1e-4,
            "LIVE_OANDA",
            "acct1"
        );

        var strategy = new Strategy() {
            @Override public String name() { return "stub"; }
            @Override public void onBar(com.martinfou.trading.core.Bar bar) {}
            @Override public void onTick(double bid, double ask, long time) {}
            @Override public List<Order> getPendingOrders() { return Collections.emptyList(); }
            @Override public void reset() {}
        };

        var executor = new OandaStreamingExecutor(
            runId,
            null,
            config,
            strategy,
            new StubBroker(),
            null,
            store,
            new KillSwitchRegistry(),
            null,
            new RunRiskContext(new RiskEngine(), (run, cfg, m, check) -> {}, metrics -> {})
        );

        Order order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 5000, 0.0);
        executor.persistMarketClosedReject(order);

        // Verify REJECT event with MARKET_CLOSED reason exists
        List<RunEvent> events = store.replayAll(runId);
        RunEvent rejectEvent = events.stream()
            .filter(e -> e.type() == RunEventType.REJECT)
            .findFirst()
            .orElse(null);

        assertNotNull(rejectEvent, "Expected REJECT event");
        assertEquals("MARKET_CLOSED", rejectEvent.payload().get("reason"));
        assertNotNull(rejectEvent.payload().get("nextOpenEstimate"), "Expected next open time estimate");
    }

    @Test
    void testStalePriceDetectionEvent() {
        var store = new InMemoryEventStore();
        String runId = "test-run-3";
        var config = new RunConfigSnapshot(
            "LondonOpenRangeBreakout",
            "EUR_USD",
            "LIVE",
            "sample",
            500,
            null,
            1000.0,
            0.07,
            1e-4,
            "LIVE_OANDA",
            "acct1"
        );

        var strategy = new Strategy() {
            @Override public String name() { return "stub"; }
            @Override public void onBar(com.martinfou.trading.core.Bar bar) {}
            @Override public void onTick(double bid, double ask, long time) {}
            @Override public List<Order> getPendingOrders() { return Collections.emptyList(); }
            @Override public void reset() {}
        };

        var executor = new OandaStreamingExecutor(
            runId,
            null,
            config,
            strategy,
            new StubBroker(),
            null,
            store,
            new KillSwitchRegistry(),
            null,
            new RunRiskContext(new RiskEngine(), (run, cfg, m, check) -> {}, metrics -> {})
        );

        Instant checkTime = Instant.now();
        executor.emitStalePriceWarningEvent(checkTime);

        // Verify STALE_PRICE_DETECTED event is in store
        List<RunEvent> events = store.replayAll(runId);
        RunEvent staleEvent = events.stream()
            .filter(e -> e.type() == RunEventType.STALE_PRICE_DETECTED)
            .findFirst()
            .orElse(null);

        assertNotNull(staleEvent, "Expected STALE_PRICE_DETECTED event");
        assertEquals("STALE", staleEvent.payload().get("status"));
        assertEquals("EUR_USD", staleEvent.payload().get("symbol"));
    }

    private static class StubBroker implements Broker {
        @Override public boolean isConnected() { return true; }
        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public void reconnect() {}
        @Override public OrderSubmitResult submitOrder(Order order) { return new OrderSubmitResult(true, "stub-id", null); }
        @Override public OrderSubmitResult cancelOrder(String brokerOrderId) { return new OrderSubmitResult(true, brokerOrderId, null); }
        @Override public List<Position> getPositions() { return Collections.emptyList(); }
        @Override public AccountState getAccountState() { return new AccountState(10000.0, 10000.0, "USD"); }
        @Override public void addEventListener(Consumer<BrokerEvent> listener) {}
    }
}
