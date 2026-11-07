package com.martinfou.trading.runtime.ibkr;

import com.martinfou.trading.broker.AccountState;
import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.broker.IbkrBroker;
import com.martinfou.trading.broker.OrderSubmitResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.ibkr.StubIbkrGatewayClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IbkrLiveRunner, SqStrategyEvaluator & IbkrOrderDispatcher Tests (Story 46.10)")
class IbkrLiveRunnerTest {

    @Test
    @DisplayName("IBKR_PORT=7496, 7497, and 4001 are strictly refused")
    void validateConfig_refusesForbiddenPorts(@TempDir Path tempDir) {
        for (int badPort : List.of(7496, 7497, 4001, 0, -1)) {
            IbkrLiveRunner.RunnerConfig cfg = new IbkrLiveRunner.RunnerConfig(
                "127.0.0.1", badPort, "DU12345", 11, "MGC", "30 mins", 30,
                "sq-mgc-m30-breakout-0-24542", "1", tempDir, false
            );
            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> IbkrLiveRunner.validateConfig(cfg),
                "Port " + badPort + " must be refused"
            );
            assertTrue(ex.getMessage().contains(String.valueOf(badPort)));
        }
    }

    @Test
    @DisplayName("Refuses comma-separated symbols, unknown strategy, or timeframe/interval mismatch")
    void validateConfig_refusesInvalidInputs(@TempDir Path tempDir) {
        // Comma-separated symbol
        assertThrows(IllegalArgumentException.class, () -> IbkrLiveRunner.validateConfig(
            new IbkrLiveRunner.RunnerConfig("127.0.0.1", 4002, "DU12345", 11, "MGC,MES", "30 mins", 30,
                "sq-mgc-m30-breakout-0-24542", "1", tempDir, false)
        ));

        // Timeframe / interval mismatch
        assertThrows(IllegalArgumentException.class, () -> IbkrLiveRunner.validateConfig(
            new IbkrLiveRunner.RunnerConfig("127.0.0.1", 4002, "DU12345", 11, "MGC", "30 mins", 60,
                "sq-mgc-m30-breakout-0-24542", "1", tempDir, false)
        ));

        // Missing TB_ALLOW_ORDERS when DRY_RUN=false
        assertThrows(IllegalArgumentException.class, () -> IbkrLiveRunner.validateConfig(
            new IbkrLiveRunner.RunnerConfig("127.0.0.1", 4002, "DU12345", 11, "MGC", "30 mins", 30,
                "sq-mgc-m30-breakout-0-24542", "0", tempDir, false)
        ));

        // Unknown strategy name fails closed
        assertThrows(IllegalArgumentException.class, () ->
            SqStrategyEvaluator.instantiateStrategy("non-existent-strategy", "MGC")
        );
    }

    @Test
    @DisplayName("Stopless entry never reaches Broker.submitOrder")
    void orderDispatcher_refusesStoplessEntryLocally() {
        List<Order> submitted = new ArrayList<>();
        Broker fakeBroker = new FakeRecordingBroker(submitted);
        AtomicBoolean halted = new AtomicBoolean(false);

        IbkrOrderDispatcher dispatcher = new IbkrOrderDispatcher(
            fakeBroker, "sq-mgc-m30-breakout-0-24542", "MGC", () -> halted.set(true), false
        );

        Order stopless = new Order("MGC", Order.Side.BUY, Order.Type.MARKET, 1.0, 2500.0); // stopLoss == 0.0
        dispatcher.accept(stopless);

        assertTrue(submitted.isEmpty(), "Stopless entry must never reach submitOrder");
        assertFalse(halted.get());
    }

    @Test
    @DisplayName("Entry is refused when broker already holds an open position for the symbol")
    void orderDispatcher_refusesDuplicateEntryWhenPositionOpen() {
        List<Order> submitted = new ArrayList<>();
        Broker brokerWithOpenPos = new FakeRecordingBroker(submitted) {
            @Override
            public List<Position> getPositions() {
                return List.of(new Position("MGC", Order.Side.SELL, 1.0, 4003.6));
            }
        };
        AtomicBoolean halted = new AtomicBoolean(false);

        IbkrOrderDispatcher dispatcher = new IbkrOrderDispatcher(
            brokerWithOpenPos, "sq-mgc-m30-breakout-0-24542", "MGC", () -> halted.set(true), false
        );

        Order secondEntry = new Order("MGC", Order.Side.SELL, Order.Type.MARKET, 1.0, 3977.7)
            .withStopLoss(3998.6)
            .withTakeProfit(3943.2);
        dispatcher.accept(secondEntry);

        assertTrue(submitted.isEmpty(), "Must not pyramid into a 2nd MGC contract when 1 is already open");
        assertFalse(halted.get());
    }

    @Test
    @DisplayName("Accepted entry followed by rejected protective stop flattens position and halts lane")
    void orderDispatcher_whenStopRejected_flattensAndHaltsLane() {
        // 1st order (MARKET entry) succeeds; 2nd order (STOP) fails!
        Order entry = new Order("MGC", Order.Side.BUY, Order.Type.MARKET, 1.0, 2500.0)
            .withStopLoss(2480.0)
            .withTakeProfit(2540.0);

        AtomicBoolean laneHalted = new AtomicBoolean(false);
        AtomicBoolean flattened = new AtomicBoolean(false);
        List<Order> submitted = new ArrayList<>();
        Broker rejectingStopBroker = new FakeRecordingBroker(submitted) {
            @Override
            public OrderSubmitResult submitOrder(Order order) {
                submitted.add(order);
                if (order.type() == Order.Type.STOP) {
                    return OrderSubmitResult.rejected("Venue rejected stop price");
                }
                return OrderSubmitResult.filled("101");
            }

            @Override
            public int flattenAllPositions() {
                flattened.set(true);
                return 1;
            }
        };

        IbkrOrderDispatcher dispatcher = new IbkrOrderDispatcher(
            rejectingStopBroker, "sq-mgc-m30-breakout-0-24542", "MGC", () -> laneHalted.set(true), false
        );

        dispatcher.accept(entry);

        assertTrue(dispatcher.isHalted(), "Dispatcher must mark itself halted when stop is rejected");
        assertTrue(laneHalted.get(), "Halt callback must be invoked when stop is rejected");
        assertTrue(flattened.get(), "Position must be flattened when protective stop is rejected");
        assertEquals(2, submitted.size(), "Entry and protective stop were attempted; TP must not be sent after stop rejection");
        assertEquals("sq-mgc-m30-breakout-0-24542_MGC", submitted.get(0).strategyId());
        assertEquals("sq-mgc-m30-breakout-0-24542_MGC", submitted.get(1).strategyId());
    }

    @Test
    @DisplayName("Same bar is not dispatched twice after a restart (cursor persistence)")
    void evaluator_persistsCursorAndSkipsSameBarAfterRestart(@TempDir Path tempDir) {
        Instant barTs = Instant.parse("2026-08-19T14:00:00Z");
        Bar bar = new Bar("MGC", barTs, 2500.0, 2510.0, 2495.0, 2505.0, 100L);

        Strategy alwaysBuyStrategy = new AlwaysSignalStrategy();
        SqStrategyEvaluator firstRun = new SqStrategyEvaluator(
            "sq-mgc-m30-breakout-0-24542", "MGC", alwaysBuyStrategy, tempDir, false
        );

        List<Order> firstOrders = firstRun.apply(List.of(bar));
        assertEquals(1, firstOrders.size());
        assertEquals("sq-mgc-m30-breakout-0-24542_MGC", firstOrders.get(0).strategyId());
        assertEquals(barTs, firstRun.lastProcessedBarTimestamp());

        // Simulate container restart with a new evaluator reading the same stateDir
        SqStrategyEvaluator restartedRun = new SqStrategyEvaluator(
            "sq-mgc-m30-breakout-0-24542", "MGC", new AlwaysSignalStrategy(), tempDir, false
        );
        assertEquals(barTs, restartedRun.lastProcessedBarTimestamp(), "Cursor must be restored from disk");

        List<Order> duplicateAttempt = restartedRun.apply(List.of(bar));
        assertTrue(duplicateAttempt.isEmpty(), "Restarted evaluator must not re-trade the same bar timestamp");
    }

    @Test
    @DisplayName("DRY_RUN=true evaluates strategy and dispatches nothing, and startup sequence completes")
    void runner_dryRunAndStartupSequence(@TempDir Path tempDir) {
        Instant now = Instant.parse("2026-08-19T14:30:05Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);

        List<Bar> bars = new ArrayList<>();
        Instant start = now.minus(Duration.ofMinutes(30 * 65));
        for (int i = 0; i < 65; i++) {
            bars.add(new Bar("MGC", start.plus(Duration.ofMinutes(30L * i)),
                2500.0 + i, 2505.0 + i, 2495.0 + i, 2502.0 + i, 100L));
        }

        StubIbkrGatewayClient stubClient = new StubIbkrGatewayClient();
        IbkrBroker broker = new IbkrBroker(stubClient);

        IbkrLiveRunner.RunnerConfig cfg = new IbkrLiveRunner.RunnerConfig(
            "127.0.0.1", 4002, "DU12345", 11, "MGC", "30 mins", 30,
            "sq-mgc-m30-breakout-0-24542", "0", tempDir, true
        );

        try (IbkrLiveRunner runner = new IbkrLiveRunner(cfg, broker, sym -> bars, () -> {}, fixedClock)) {
            runner.start();
            assertNotNull(runner.scheduler());
            assertEquals(Instant.parse("2026-08-19T15:00:05Z"), runner.scheduler().nextTriggerTime());

            int dispatched = runner.scheduler().runCycle();
            assertEquals(0, dispatched, "DRY_RUN=true must never dispatch orders");
            assertEquals(bars.get(bars.size() - 1).timestamp(), runner.evaluator().lastProcessedBarTimestamp());
        }
    }

    @Test
    @DisplayName("Multi-strategy comma-separated STRATEGY initializes all 3 ELITE MGC evaluators and evaluates each")
    void runner_multiStrategyEnsembleEvaluatesAll(@TempDir Path tempDir) {
        Instant now = Instant.parse("2026-08-19T14:30:05Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);

        List<Bar> bars = new ArrayList<>();
        Instant start = now.minus(Duration.ofMinutes(30 * 65));
        for (int i = 0; i < 65; i++) {
            bars.add(new Bar("MGC", start.plus(Duration.ofMinutes(30L * i)),
                2500.0 + i, 2505.0 + i, 2495.0 + i, 2502.0 + i, 100L));
        }

        StubIbkrGatewayClient stubClient = new StubIbkrGatewayClient();
        IbkrBroker broker = new IbkrBroker(stubClient);

        IbkrLiveRunner.RunnerConfig cfg = new IbkrLiveRunner.RunnerConfig(
            "127.0.0.1", 4002, "DU12345", 11, "MGC", "30 mins", 30,
            "sq-mgc-m30-breakout-0-24542,sq-mgc-m30-breakout-1-29-102,sq-mgc-m30-breakout-0-32921",
            "0", tempDir, true
        );

        try (IbkrLiveRunner runner = new IbkrLiveRunner(cfg, broker, sym -> bars, () -> {}, fixedClock)) {
            runner.start();
            assertEquals(3, runner.evaluators().size());
            runner.scheduler().runCycle();
            for (SqStrategyEvaluator ev : runner.evaluators()) {
                assertEquals(bars.get(bars.size() - 1).timestamp(), ev.lastProcessedBarTimestamp());
            }
        }
    }

    @Test
    @DisplayName("Default TB_STATE_DIR (/app/data/state/futures) and container state dirs resolve fills directory to /app/data/fills")
    void runnerConfig_defaultStateDirResolvesUnifiedDataFills() {
        IbkrLiveRunner.RunnerConfig cfg = IbkrLiveRunner.RunnerConfig.fromEnv(java.util.Map.of(
            "IBKR_ACCOUNT_ID", "DU12345",
            "STRATEGY", "sq-mgc-m30-breakout-0-24542",
            "TB_ALLOW_ORDERS", "1"
        ));
        assertEquals(Path.of("/app/data/state/futures"), cfg.stateDir());
        assertEquals(Path.of("/app/data/fills"), IbkrLiveRunner.resolveFillsDir(cfg.stateDir()));
        assertEquals(Path.of("/app/data/fills"), IbkrLiveRunner.resolveFillsDir(Path.of("/app/data/state/ibkr-paper")));
        assertEquals(Path.of("/app/data/fills"), IbkrLiveRunner.resolveFillsDir(Path.of("/app/data/state/ibkr-live")));
    }

    @Test
    @DisplayName("Multi-symbol FUTURES_SYMBOL=MGC,MNQ,MES routes each strategy to its matching futures contract in one container")
    void runner_multiSymbolRoutesStrategiesToTheirMatchingContract(@TempDir Path tempDir) {
        Instant now = Instant.parse("2026-08-19T14:00:05Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);

        StubIbkrGatewayClient stubClient = new StubIbkrGatewayClient();
        IbkrBroker broker = new IbkrBroker(stubClient);

        IbkrLiveRunner.RunnerConfig cfg = new IbkrLiveRunner.RunnerConfig(
            "127.0.0.1", 4002, "DU12345", 11, "MGC,MNQ,MES", "1 hour", 60,
            "MgcH1Breakout38023,MnqH1Breakout31697,MesH1Breakout30808",
            "0", tempDir, true, "paper"
        );

        try (IbkrLiveRunner runner = new IbkrLiveRunner(cfg, broker, sym -> {
            List<Bar> bars = new ArrayList<>();
            Instant start = now.minus(Duration.ofMinutes(60L * 65));
            for (int i = 0; i < 65; i++) {
                bars.add(new Bar(sym, start.plus(Duration.ofMinutes(60L * i)),
                    2500.0 + i, 2505.0 + i, 2495.0 + i, 2502.0 + i, 100L));
            }
            return bars;
        }, () -> {}, fixedClock)) {
            runner.start();
            assertEquals(3, runner.evaluators().size());
            assertEquals("MGC", runner.evaluators().get(0).symbol());
            assertEquals("MNQ", runner.evaluators().get(1).symbol());
            assertEquals("MES", runner.evaluators().get(2).symbol());

            runner.scheduler().runCycle();
            for (SqStrategyEvaluator ev : runner.evaluators()) {
                assertNotNull(ev.lastProcessedBarTimestamp(),
                    "Evaluator for " + ev.strategyName() + " (" + ev.symbol() + ") must process its symbol's bars");
            }
        }
    }

    @Test
    @DisplayName("Multi-timeframe + multi-symbol runner schedules at 30m base interval, evaluates M30 at :30 and both M30+H1 at :00")
    void runner_multiTimeframeAndMultiSymbolScheduleAndCursorFallback(@TempDir Path tempDir) throws Exception {
        // Pre-populate legacy cursor file for sq-mgc-m30-breakout-0-24542 to verify MgcM30Breakout49205 picks it up
        Instant legacyCursorTs = Instant.parse("2026-08-19T13:30:00Z");
        java.nio.file.Files.writeString(
            tempDir.resolve("sq-mgc-m30-breakout-0-24542-MGC-cursor.json"),
            "{\"strategy\":\"sq-mgc-m30-breakout-0-24542\",\"symbol\":\"MGC\",\"lastProcessedBarTimestamp\":\"" + legacyCursorTs + "\"}"
        );

        // Start at 14:10:00Z -> next base trigger is 14:30:05Z (since M30 has 30m interval)
        Instant startClock = Instant.parse("2026-08-19T14:10:00Z");
        Clock fixedClock = Clock.fixed(startClock, ZoneOffset.UTC);

        StubIbkrGatewayClient stubClient = new StubIbkrGatewayClient();
        IbkrBroker broker = new IbkrBroker(stubClient);

        IbkrLiveRunner.RunnerConfig cfg = new IbkrLiveRunner.RunnerConfig(
            "127.0.0.1", 4002, "DU12345", 11, "MGC,MNQ,MES", "1 hour", 60,
            "MgcM30Breakout49205,MgcH1Breakout38023,MnqH1Breakout31697,MesH1Breakout30808",
            "0", tempDir, true, "paper"
        );

        List<String> requestedSlices = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Instant> latestM30BarOpen =
            new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-08-19T14:00:00Z"));
        try (IbkrLiveRunner runner = new IbkrLiveRunner(cfg, broker, (sym, tf) -> {
            requestedSlices.add(sym + ":" + tf);
            int stepMin = "30 mins".equals(tf) ? 30 : 60;
            List<Bar> bars = new ArrayList<>();
            Instant endBar = "30 mins".equals(tf) ? latestM30BarOpen.get() : Instant.parse("2026-08-19T14:00:00Z");
            Instant base = endBar.minus(Duration.ofMinutes(stepMin * 64L));
            for (int i = 0; i < 65; i++) {
                bars.add(new Bar(sym, base.plus(Duration.ofMinutes((long) stepMin * i)),
                    2500.0 + i, 2505.0 + i, 2495.0 + i, 2502.0 + i, 100L));
            }
            return bars;
        }, () -> {}, fixedClock)) {
            runner.start();

            assertEquals(4, runner.evaluators().size());
            assertEquals("30 mins", runner.evaluators().get(0).timeframe());
            assertEquals("1 hour", runner.evaluators().get(1).timeframe());
            assertEquals(legacyCursorTs, runner.evaluators().get(0).lastProcessedBarTimestamp(),
                "MgcM30Breakout49205 must inherit legacy sq-mgc-m30-breakout-0-24542 cursor on first boot");
            assertEquals(30, runner.scheduler().intervalMinutes(), "Scheduler base cadence must be 30m (fastest slice)");
            assertEquals(Instant.parse("2026-08-19T14:30:05Z"), runner.scheduler().nextTriggerTime());

            // 1. Trigger at 14:30:05Z (half-hour): ONLY MGC (30 mins) slice should be fetched & evaluated
            requestedSlices.clear();
            runner.scheduler().runCycle(Instant.parse("2026-08-19T14:30:05Z"), 15_000.0, List.of());
            assertEquals(List.of("MGC:30 mins"), requestedSlices, "At :30:05 only M30 slice must be fetched");
            assertEquals(Instant.parse("2026-08-19T14:00:00Z"), runner.evaluators().get(0).lastProcessedBarTimestamp());
            assertNull(runner.evaluators().get(1).lastProcessedBarTimestamp(), "H1 evaluator must not run at :30:05");

            // 2. Trigger at 15:00:05Z (top of hour): BOTH M30 (with new 14:30 bar) and H1 slices must be fetched & evaluated
            latestM30BarOpen.set(Instant.parse("2026-08-19T14:30:00Z"));
            requestedSlices.clear();
            runner.scheduler().runCycle(Instant.parse("2026-08-19T15:00:05Z"), 15_000.0, List.of());
            assertEquals(
                List.of("MGC:30 mins", "MGC:1 hour", "MNQ:1 hour", "MES:1 hour"),
                requestedSlices,
                "At :00:05 both M30 and H1 slices must be fetched sequentially"
            );
            assertEquals(Instant.parse("2026-08-19T14:30:00Z"), runner.evaluators().get(0).lastProcessedBarTimestamp(),
                "M30 evaluator must advance its cursor to 14:30:00Z at :00:05 trigger");
            assertEquals(Instant.parse("2026-08-19T14:00:00Z"), runner.evaluators().get(1).lastProcessedBarTimestamp(),
                "H1 evaluator must run at :00:05");
        }
    }

    @Test
    @DisplayName("resolveTimeframeForStrategy uses token boundaries and does not misclassify M10/EM1/EMD1 substrings")
    void resolveTimeframeForStrategy_respectsTokenBoundaries() {
        assertEquals("30 mins", SqStrategyEvaluator.resolveTimeframeForStrategy("MgcM30Breakout49205", "1 hour"));
        assertEquals("30 mins", SqStrategyEvaluator.resolveTimeframeForStrategy("sq-mgc-m30-breakout-0-24542", "1 hour"));
        assertEquals("15 mins", SqStrategyEvaluator.resolveTimeframeForStrategy("MnqM15MeanRev81824", "1 hour"));
        assertEquals("1 hour", SqStrategyEvaluator.resolveTimeframeForStrategy("EmdH1Pullback25691", "30 mins"));
        assertEquals("1 hour", SqStrategyEvaluator.resolveTimeframeForStrategy("MgcBreakoutM100", "1 hour"),
            "M100 must not be misclassified as M1");
        assertEquals("1 hour", SqStrategyEvaluator.resolveTimeframeForStrategy("Emd1Breakout", "1 hour"),
            "Emd1 must not be misclassified as D1");
    }

    @Test
    @DisplayName("IBKR_ENV=live allows port 4001 with live account and refuses port 4002 or DU* paper accounts")
    void validateConfig_ibkrLiveEnvRules(@TempDir Path tempDir) {
        // Valid live config (port 4001 + U* live account)
        assertDoesNotThrow(() -> IbkrLiveRunner.validateConfig(
            new IbkrLiveRunner.RunnerConfig("127.0.0.1", 4001, "U1234567", 21, "MGC,MNQ,MES", "1 hour", 60,
                "MgcH1Breakout38023,MnqH1Breakout31697,MesH1Breakout30808", "1", tempDir, false, "live")
        ));

        // Refuses paper port 4002 when IBKR_ENV=live
        assertThrows(IllegalArgumentException.class, () -> IbkrLiveRunner.validateConfig(
            new IbkrLiveRunner.RunnerConfig("127.0.0.1", 4002, "U1234567", 21, "MGC", "1 hour", 60,
                "MgcH1Breakout38023", "1", tempDir, false, "live")
        ));

        // Refuses DU* paper account when IBKR_ENV=live
        assertThrows(IllegalArgumentException.class, () -> IbkrLiveRunner.validateConfig(
            new IbkrLiveRunner.RunnerConfig("127.0.0.1", 4001, "DUR967322", 21, "MGC", "1 hour", 60,
                "MgcH1Breakout38023", "1", tempDir, false, "live")
        ));
    }

    private static final class AlwaysSignalStrategy implements Strategy {
        private final List<Order> pending = new ArrayList<>();
        @Override public String name() { return "AlwaysSignal"; }
        @Override public void onTick(double bid, double ask, long volume) {}
        @Override public void onBar(Bar bar) {
            pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 1.0, bar.close())
                .withStopLoss(bar.close() - 10.0)
                .withTakeProfit(bar.close() + 20.0));
        }
        @Override public List<Order> getPendingOrders() {
            List<Order> out = List.copyOf(pending);
            pending.clear();
            return out;
        }
        @Override public void reset() { pending.clear(); }
    }

    private static class FakeRecordingBroker implements Broker {
        private final List<Order> submitted;
        FakeRecordingBroker(List<Order> submitted) { this.submitted = submitted; }
        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public void reconnect() {}
        @Override public boolean isConnected() { return true; }
        @Override public OrderSubmitResult submitOrder(Order order) {
            submitted.add(order);
            return OrderSubmitResult.filled("1");
        }
        @Override public OrderSubmitResult cancelOrder(String brokerOrderId) {
            return OrderSubmitResult.filled(brokerOrderId);
        }
        @Override public List<Position> getPositions() { return List.of(); }
        @Override public AccountState getAccountState() { return new AccountState(10_000, 10_000, "USD"); }
        @Override public void addEventListener(java.util.function.Consumer<com.martinfou.trading.broker.BrokerEvent> l) {}
    }
}
