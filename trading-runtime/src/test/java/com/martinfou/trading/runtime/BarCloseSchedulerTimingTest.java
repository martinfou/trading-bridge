package com.martinfou.trading.runtime;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.core.SmallAccountMarginGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BarCloseScheduler Contract & Timing Tests (Story 46.7)")
class BarCloseSchedulerTimingTest {

    @Test
    @DisplayName("With a fixed Clock, first trigger lands at HH:MM:05 and consecutive triggers are intervalMinutes apart")
    void testTriggerAlignmentAndCadence() {
        Instant t0 = Instant.parse("2026-08-19T14:12:43Z");
        Instant firstTrigger = BarCloseScheduler.computeNextTrigger(t0, 30);
        assertEquals(Instant.parse("2026-08-19T14:30:05Z"), firstTrigger);

        Instant secondTrigger = BarCloseScheduler.computeNextTrigger(firstTrigger, 30);
        assertEquals(Instant.parse("2026-08-19T15:00:05Z"), secondTrigger);
        assertEquals(Duration.ofMinutes(30), Duration.between(firstTrigger, secondTrigger));

        // Verify start() schedules using the exact delay to HH:MM:05 and reschedules after each run
        AtomicReference<Instant> currentInstant = new AtomicReference<>(t0);
        Clock mutableClock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return currentInstant.get(); }
        };

        List<Long> scheduledDelaysMs = new ArrayList<>();
        List<Runnable> scheduledCommands = new ArrayList<>();
        ScheduledExecutorService fakeExecutor = new AbstractScheduledExecutor() {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                scheduledDelaysMs.add(unit.toMillis(delay));
                scheduledCommands.add(command);
                return new CompletedScheduledFuture<>();
            }
        };

        AtomicInteger equityCalls = new AtomicInteger(0);
        List<Order> dispatched = new ArrayList<>();

        try (BarCloseScheduler scheduler = new BarCloseScheduler(
            List.of("MGC"),
            30,
            mutableClock,
            sym -> List.of(new Bar("MGC", currentInstant.get().minus(Duration.ofMinutes(30)), 2500.0, 2510.0, 2495.0, 2505.0, 100L)),
            Map.of("MGC", bars -> List.of(new Order("MGC", Order.Side.BUY, Order.Type.MARKET, 1.0, 2505.0))),
            () -> {
                equityCalls.incrementAndGet();
                return 11_270.0;
            },
            List::of,
            new SmallAccountMarginGuard(0.50, 2, 5000.0),
            dispatched::add,
            fakeExecutor
        )) {
            scheduler.start();
            assertEquals(1, scheduledDelaysMs.size());
            assertEquals(Instant.parse("2026-08-19T14:30:05Z"), scheduler.nextTriggerTime());
            assertEquals(Duration.between(t0, firstTrigger).toMillis(), scheduledDelaysMs.get(0));

            // Advance clock to first trigger and fire scheduled command
            currentInstant.set(firstTrigger);
            scheduledCommands.get(0).run();

            assertEquals(1, equityCalls.get(), "Equity supplier must be invoked during scheduled cycle");
            assertEquals(1, dispatched.size());
            assertEquals(2, scheduledDelaysMs.size(), "Must reschedule after cycle completes");
            assertEquals(Instant.parse("2026-08-19T15:00:05Z"), scheduler.nextTriggerTime());
            assertEquals(Duration.ofMinutes(30).toMillis(), scheduledDelaysMs.get(1));
        }
    }

    @Test
    @DisplayName("validateOrder receives non-empty position list when supplier returns positions")
    void testPositionSupplierBlocksOverLimit() {
        Instant trigger = Instant.parse("2026-08-19T14:30:05Z");
        Bar freshBar = new Bar("MGC", Instant.parse("2026-08-19T14:00:00Z"), 2500.0, 2510.0, 2490.0, 2505.0, 200L);
        List<Order> dispatched = new ArrayList<>();

        // 2 open positions reach maxConcurrentPositions = 2
        List<Position> openPositions = List.of(
            new Position("MGC", Order.Side.BUY, 1.0, 2480.0, trigger.minusSeconds(3600)),
            new Position("MES", Order.Side.BUY, 1.0, 5000.0, trigger.minusSeconds(3600))
        );

        try (BarCloseScheduler scheduler = new BarCloseScheduler(
            List.of("MGC"),
            30,
            Clock.fixed(trigger, ZoneOffset.UTC),
            sym -> List.of(freshBar),
            Map.of("MGC", bars -> List.of(new Order("MGC", Order.Side.BUY, Order.Type.MARKET, 1.0, 2505.0))),
            () -> 25_000.0,
            () -> openPositions,
            new SmallAccountMarginGuard(0.50, 2, 5000.0),
            dispatched::add
        )) {
            int count = scheduler.runCycle();
            assertEquals(0, count, "Order must be rejected when position supplier returns 2 active positions");
            assertTrue(dispatched.isEmpty());
        }
    }

    @Test
    @DisplayName("Stale bar list older than interval + 2 minutes produces zero orders")
    void testStaleBarsSkipped() {
        Instant trigger = Instant.parse("2026-08-19T14:30:05Z");
        // For M30, maxBarAge is 32 minutes. A bar at 13:57:00Z is 33m05s old -> stale!
        Bar staleBar = new Bar("MGC", Instant.parse("2026-08-19T13:57:00Z"), 2500.0, 2510.0, 2490.0, 2505.0, 200L);
        List<Order> dispatched = new ArrayList<>();
        AtomicInteger evaluatorCalls = new AtomicInteger(0);

        try (BarCloseScheduler scheduler = new BarCloseScheduler(
            List.of("MGC"),
            30,
            Clock.fixed(trigger, ZoneOffset.UTC),
            sym -> List.of(staleBar),
            Map.of("MGC", bars -> {
                evaluatorCalls.incrementAndGet();
                return List.of(new Order("MGC", Order.Side.BUY, Order.Type.MARKET, 1.0, 2505.0));
            }),
            () -> 15_000.0,
            List::of,
            new SmallAccountMarginGuard(0.50, 2, 5000.0),
            dispatched::add
        )) {
            int count = scheduler.runCycle();
            assertEquals(0, count);
            assertEquals(0, evaluatorCalls.get(), "Strategy evaluator must not be called on stale bars");
            assertTrue(dispatched.isEmpty());
        }
    }

    @Test
    @DisplayName("Equity supplier is invoked dynamically and not hardcoded")
    void testDynamicEquitySupplier() {
        Instant trigger = Instant.parse("2026-08-19T14:30:05Z");
        Bar freshBar = new Bar("MGC", Instant.parse("2026-08-19T14:00:00Z"), 2500.0, 2510.0, 2490.0, 2505.0, 200L);
        List<Order> dispatched = new ArrayList<>();
        AtomicReference<Double> liveEquity = new AtomicReference<>(500.0); // Too low for MGC margin -> rejected

        try (BarCloseScheduler scheduler = new BarCloseScheduler(
            List.of("MGC"),
            30,
            Clock.fixed(trigger, ZoneOffset.UTC),
            sym -> List.of(freshBar),
            Map.of("MGC", bars -> List.of(new Order("MGC", Order.Side.BUY, Order.Type.MARKET, 1.0, 2505.0))),
            liveEquity::get,
            List::of,
            new SmallAccountMarginGuard(0.50, 2, 5000.0),
            dispatched::add
        )) {
            assertEquals(0, scheduler.runCycle(), "With $500 equity from supplier, margin guard must reject");
            assertTrue(dispatched.isEmpty());

            liveEquity.set(12_000.0);
            assertEquals(1, scheduler.runCycle(), "With $12,000 equity from supplier, margin guard must approve");
            assertEquals(1, dispatched.size());
        }
    }

    private abstract static class AbstractScheduledExecutor extends AbstractExecutorService implements ScheduledExecutorService {
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) { command.run(); }
        @Override public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
    }

    private static final class CompletedScheduledFuture<V> implements ScheduledFuture<V> {
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed o) { return 0; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return true; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return true; }
        @Override public V get() { return null; }
        @Override public V get(long timeout, TimeUnit unit) { return null; }
    }
}

