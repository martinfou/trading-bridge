package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.broker.AccountState;
import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.broker.BrokerEvent;
import com.martinfou.trading.broker.FakeBroker;
import com.martinfou.trading.broker.OrderSubmitResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskEngineTest {

    private static final RiskLimits TIGHT = new RiskLimits(5000, 10_000, 0.0);

    @Test
    void checkPreTrade_passesWithinLimits() {
        RiskEngine engine = new RiskEngine(TIGHT);
        Order order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000, 1.10);

        RiskCheckResult result = engine.checkPreTrade(order, List.of());

        assertTrue(result.passed());
    }

    @Test
    void checkPreTrade_rejectsMaxPositionSize() {
        RiskEngine engine = new RiskEngine(TIGHT);
        Order order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 6000, 1.10);
        List<Position> open = List.of(new Position("EUR_USD", Order.Side.BUY, 2000, 1.10));

        RiskCheckResult result = engine.checkPreTrade(order, open);

        assertFalse(result.passed());
        assertEquals("max_position_size", result.limitName());
        assertEquals(TIGHT.maxPositionSize(), result.threshold());
        assertEquals(8000.0, result.actual());
    }

    @Test
    void checkPreTrade_rejectsMaxOpenExposure() {
        RiskEngine engine = new RiskEngine(new RiskLimits(100_000, 5000, 0.0));
        Order order = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 3000, 2.0);
        List<Position> open = List.of(new Position("GBP_USD", Order.Side.BUY, 1000, 3.0));

        RiskCheckResult result = engine.checkPreTrade(order, open);

        assertFalse(result.passed());
        assertEquals("max_open_exposure", result.limitName());
        assertEquals(5000.0, result.threshold());
        assertEquals(9000.0, result.actual());
    }

    @Test
    void checkDailyDrawdown_passesWithinLimit() {
        RiskEngine engine = new RiskEngine(new RiskLimits(1_000_000, 2_000_000, 5.0));
        DailyDrawdownTracker tracker = new DailyDrawdownTracker();
        Instant ts = Instant.parse("2024-06-01T12:00:00Z");

        tracker.update(ts, 100_000.0);
        RiskCheckResult result = engine.checkDailyDrawdown(tracker, 98_000.0, ts);

        assertTrue(result.passed());
        assertEquals(2.0, tracker.drawdownPct(98_000.0), 0.01);
    }

    @Test
    void checkDailyDrawdown_breachesLimit() {
        RiskEngine engine = new RiskEngine(new RiskLimits(1_000_000, 2_000_000, 3.0));
        DailyDrawdownTracker tracker = new DailyDrawdownTracker();
        Instant ts = Instant.parse("2024-06-01T12:00:00Z");

        tracker.update(ts, 100_000.0);
        RiskCheckResult result = engine.checkDailyDrawdown(tracker, 96_000.0, ts);

        assertFalse(result.passed());
        assertEquals("max_daily_drawdown_pct", result.limitName());
        assertTrue(result.message().contains("DAILY_DD_BREACH"));
        assertEquals(3.0, result.threshold());
        assertEquals(4.0, result.actual());
    }

    @Test
    void brokerRunExecutor_dailyDrawdownPausesAndBlocksOrders() {
        try (EventStore store = EventStores.inMemory()) {
            Strategy strategy = new BuyEveryBarStrategy();
            List<Bar> bars = List.of(
                new Bar("EUR_USD", Instant.parse("2024-06-01T10:00:00Z"), 1.10, 1.11, 1.09, 1.105, 1000),
                new Bar("EUR_USD", Instant.parse("2024-06-01T11:00:00Z"), 1.10, 1.11, 1.09, 1.105, 1000));

            RunConfigSnapshot config = new RunConfigSnapshot(
                "Test",
                "EUR_USD",
                "LIVE",
                "sample",
                2,
                null,
                100_000.0,
                null,
                null,
                ExecutionLabel.LIVE_OANDA.name());

            var broker = new DrawdownBroker();
            RunRiskContext riskContext = new RunRiskContext(
                new RiskEngine(new RiskLimits(1_000_000, 2_000_000, 2.0)),
                (runId, cfg, mode, check) -> {},
                metrics -> {});

            BrokerRunExecutor.execute(
                "run-dd", config, bars, 100_000.0, strategy, broker, store, null, null, riskContext);

            var events = store.replayAll("run-dd");
            assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.OPERATOR_ACTION
                && "DAILY_DD_BREACH".equals(e.payload().get("action"))));
            assertTrue(events.stream().filter(e -> e.type() == RunEventType.FILL).count() <= 1);
        }
    }

    /** Returns lower equity after the first bar's account queries. */
    private static final class DrawdownBroker implements Broker {
        private final FakeBroker delegate = new FakeBroker(100_000.0);
        private int accountStateCalls;

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
            return delegate.submitOrder(order);
        }

        @Override
        public OrderSubmitResult cancelOrder(String brokerOrderId) {
            return delegate.cancelOrder(brokerOrderId);
        }

        @Override
        public List<Position> getPositions() {
            return delegate.getPositions();
        }

        @Override
        public AccountState getAccountState() {
            accountStateCalls++;
            if (accountStateCalls <= 1) {
                return new AccountState(100_000.0, 100_000.0, "USD");
            }
            return new AccountState(100_000.0, 90_000.0, "USD");
        }

        @Override
        public void addEventListener(Consumer<BrokerEvent> listener) {
            delegate.addEventListener(listener);
        }
    }

    private static final class BuyEveryBarStrategy implements Strategy {
        @Override
        public String name() {
            return "BuyEveryBar";
        }

        @Override
        public void onBar(Bar bar) {
            submitOrder(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 1000, bar.open()));
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
            pending.clear();
        }
    }

    @Test
    void brokerRunExecutor_blocksOrderBeforeBrokerSubmit() {
        try (EventStore store = EventStores.inMemory()) {
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

            BrokerRunExecutor.execute(
                "run-risk",
                config,
                bars,
                100_000.0,
                strategy,
                new FakeBroker(100_000.0),
                store,
                null,
                new RiskEngine(new RiskLimits(500, 100_000, 0.0)));

            var events = store.replayAll("run-risk");
            assertTrue(events.stream().noneMatch(e -> e.type() == RunEventType.FILL));
            assertTrue(events.stream().anyMatch(e -> e.type() == RunEventType.REJECT
                && "RISK_ENGINE".equals(e.payload().get("rejectSource"))
                && "max_position_size".equals(e.payload().get("limit"))));
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

    @Test
    void checkDailyLossLimit_passesWithinLimit() {
        RiskEngine engine = new RiskEngine(new RiskLimits(1_000_000, 2_000_000, 5.0, 5.0, 10.0));
        RiskCheckResult result = engine.checkDailyLossLimit(100_000.0, 96_000.0);
        assertTrue(result.passed());
    }

    @Test
    void checkDailyLossLimit_breachesLimit() {
        RiskEngine engine = new RiskEngine(new RiskLimits(1_000_000, 2_000_000, 5.0, 5.0, 10.0));
        RiskCheckResult result = engine.checkDailyLossLimit(100_000.0, 94_000.0);
        assertFalse(result.passed());
        assertEquals("daily_loss_limit", result.limitName());
        assertEquals(5.0, result.threshold());
        assertEquals(6.0, result.actual());
    }

    @Test
    void checkWeeklyLossLimit_passesWithinLimit() {
        RiskEngine engine = new RiskEngine(new RiskLimits(1_000_000, 2_000_000, 5.0, 5.0, 10.0));
        RiskCheckResult result = engine.checkWeeklyLossLimit(100_000.0, 91_000.0);
        assertTrue(result.passed());
    }

    @Test
    void checkWeeklyLossLimit_breachesLimit() {
        RiskEngine engine = new RiskEngine(new RiskLimits(1_000_000, 2_000_000, 5.0, 5.0, 10.0));
        RiskCheckResult result = engine.checkWeeklyLossLimit(100_000.0, 89_000.0);
        assertFalse(result.passed());
        assertEquals("weekly_loss_limit", result.limitName());
        assertEquals(10.0, result.threshold());
        assertEquals(11.0, result.actual());
    }
}
