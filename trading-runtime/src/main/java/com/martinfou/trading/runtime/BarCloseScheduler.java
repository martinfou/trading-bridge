package com.martinfou.trading.runtime;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.CmeGlobexMarketCalendar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.core.SmallAccountMarginGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Deterministic Bar-Close Scheduler and Multi-Regime Ensemble Runner (Stories 46.6 &amp; 46.7).
 *
 * <p>Wakes up precisely at {@code :00:05} seconds after each bar interval boundary (e.g. M30 or H1)
 * during CME Globex open sessions, fetches closed bars, verifies bar freshness (skipping stale bars
 * older than {@code interval + 2 minutes}), evaluates the symbol-keyed strategy evaluator map
 * ({@code Map<String, Function<List<Bar>, List<Order>>>}) so each symbol preserves its own
 * {@code Strategy} instance and state, queries live account equity and open positions, applies the
 * {@link SmallAccountMarginGuard}, and dispatches approved orders.</p>
 */
public final class BarCloseScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BarCloseScheduler.class);

    public static final int DEFAULT_INTERVAL_MINUTES = 60;
    public static final long TRIGGER_OFFSET_SECONDS = 5L;
    public static final Duration STALE_BAR_TOLERANCE = Duration.ofMinutes(2);

    private final ScheduledExecutorService scheduler;
    private final Function<String, List<Bar>> barSupplier;
    private final Map<String, Function<List<Bar>, List<Order>>> strategyEvaluators;
    private final SmallAccountMarginGuard marginGuard;
    private final Consumer<Order> orderDispatcher;
    private final List<String> symbols;
    private final int intervalMinutes;
    private final Clock clock;
    private final DoubleSupplier equitySupplier;
    private final Supplier<List<Position>> positionSupplier;

    private volatile boolean running;
    private volatile Instant nextTriggerTime;
    private ScheduledFuture<?> scheduledTask;

    public BarCloseScheduler(
        List<String> symbols,
        Function<String, List<Bar>> barSupplier,
        Function<List<Bar>, List<Order>> strategyEvaluator,
        SmallAccountMarginGuard marginGuard,
        Consumer<Order> orderDispatcher
    ) {
        this(
            symbols,
            DEFAULT_INTERVAL_MINUTES,
            Clock.systemUTC(),
            barSupplier,
            toSymbolMap(symbols, Objects.requireNonNull(strategyEvaluator, "strategyEvaluator is required")),
            () -> 0.0,
            List::of,
            marginGuard,
            orderDispatcher,
            defaultExecutor()
        );
    }

    public BarCloseScheduler(
        List<String> symbols,
        Function<String, List<Bar>> barSupplier,
        Function<List<Bar>, List<Order>> strategyEvaluator,
        SmallAccountMarginGuard marginGuard,
        Consumer<Order> orderDispatcher,
        ScheduledExecutorService scheduler
    ) {
        this(
            symbols,
            DEFAULT_INTERVAL_MINUTES,
            Clock.systemUTC(),
            barSupplier,
            toSymbolMap(symbols, Objects.requireNonNull(strategyEvaluator, "strategyEvaluator is required")),
            () -> 0.0,
            List::of,
            marginGuard,
            orderDispatcher,
            scheduler
        );
    }

    public BarCloseScheduler(
        List<String> symbols,
        int intervalMinutes,
        Clock clock,
        Function<String, List<Bar>> barSupplier,
        Map<String, Function<List<Bar>, List<Order>>> strategyEvaluators,
        DoubleSupplier equitySupplier,
        Supplier<List<Position>> positionSupplier,
        SmallAccountMarginGuard marginGuard,
        Consumer<Order> orderDispatcher
    ) {
        this(
            symbols,
            intervalMinutes,
            clock,
            barSupplier,
            strategyEvaluators,
            equitySupplier,
            positionSupplier,
            marginGuard,
            orderDispatcher,
            defaultExecutor()
        );
    }

    public BarCloseScheduler(
        List<String> symbols,
        int intervalMinutes,
        Clock clock,
        Function<String, List<Bar>> barSupplier,
        Map<String, Function<List<Bar>, List<Order>>> strategyEvaluators,
        DoubleSupplier equitySupplier,
        Supplier<List<Position>> positionSupplier,
        SmallAccountMarginGuard marginGuard,
        Consumer<Order> orderDispatcher,
        ScheduledExecutorService scheduler
    ) {
        if (intervalMinutes <= 0) {
            throw new IllegalArgumentException("intervalMinutes must be > 0, got: " + intervalMinutes);
        }
        this.symbols = (symbols != null && !symbols.isEmpty())
            ? List.copyOf(symbols)
            : List.of("MES", "MNQ", "M2K", "MGC");
        this.intervalMinutes = intervalMinutes;
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.barSupplier = Objects.requireNonNull(barSupplier, "barSupplier is required");
        this.strategyEvaluators = Map.copyOf(Objects.requireNonNull(strategyEvaluators, "strategyEvaluators is required"));
        this.equitySupplier = Objects.requireNonNull(equitySupplier, "equitySupplier is required");
        this.positionSupplier = Objects.requireNonNull(positionSupplier, "positionSupplier is required");
        this.marginGuard = Objects.requireNonNull(marginGuard, "marginGuard is required");
        this.orderDispatcher = Objects.requireNonNull(orderDispatcher, "orderDispatcher is required");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler is required");
    }

    private static ScheduledExecutorService defaultExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bar-close-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    private static Map<String, Function<List<Bar>, List<Order>>> toSymbolMap(
        List<String> symbols,
        Function<List<Bar>, List<Order>> evaluator
    ) {
        List<String> syms = (symbols != null && !symbols.isEmpty())
            ? symbols
            : List.of("MES", "MNQ", "M2K", "MGC");
        Map<String, Function<List<Bar>, List<Order>>> map = new LinkedHashMap<>();
        for (String sym : syms) {
            map.put(sym, evaluator);
        }
        return map;
    }

    /**
     * Computes the next boundary trigger time: truncates {@code now} to {@code intervalMinutes},
     * advances to the next boundary, and adds {@link #TRIGGER_OFFSET_SECONDS} ({@code :00:05}).
     * If {@code now} is before the current boundary's {@code +5s} offset (e.g. process started at
     * {@code 10:00:01}), the trigger is still placed at the next full interval boundary's {@code :00:05}
     * as required by "truncate now to the interval, add the interval, add 5 seconds".
     */
    public static Instant computeNextTrigger(Instant now, int intervalMinutes) {
        Objects.requireNonNull(now, "now is required");
        if (intervalMinutes <= 0) {
            throw new IllegalArgumentException("intervalMinutes must be > 0");
        }
        long intervalSeconds = intervalMinutes * 60L;
        long epochSecond = now.getEpochSecond();
        long truncated = (epochSecond / intervalSeconds) * intervalSeconds;
        long nextBoundary = truncated + intervalSeconds;
        return Instant.ofEpochSecond(nextBoundary + TRIGGER_OFFSET_SECONDS);
    }

    public Instant nextTriggerTime() {
        return nextTriggerTime;
    }

    public int intervalMinutes() {
        return intervalMinutes;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        log.info("Starting BarCloseScheduler for symbols: {} (interval={}m)...", symbols, intervalMinutes);
        scheduleNext();
    }

    private synchronized void scheduleNext() {
        if (!running) return;
        Instant now = clock.instant();
        Instant target = computeNextTrigger(now, intervalMinutes);
        this.nextTriggerTime = target;
        long delayMillis = Math.max(0L, Duration.between(now, target).toMillis());
        log.info("BarCloseScheduler next trigger scheduled at {} (in {} ms)", target, delayMillis);
        scheduledTask = scheduler.schedule(this::runCycleAndReschedule, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void runCycleAndReschedule() {
        if (!running) return;
        runCycleSafe();
        synchronized (this) {
            if (running) {
                scheduleNext();
            }
        }
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
        }
        log.info("BarCloseScheduler stopped.");
    }

    /**
     * Executes a single evaluation cycle using the configured clock, equity supplier, and position supplier.
     */
    public int runCycle() {
        return runCycle(clock.instant(), equitySupplier.getAsDouble(), positionSupplier.get());
    }

    public int runCycle(Instant triggerTime, double accountEquity) {
        return runCycle(triggerTime, accountEquity, positionSupplier.get());
    }

    public int runCycle(Instant triggerTime, double accountEquity, List<Position> openPositions) {
        if (triggerTime == null) triggerTime = clock.instant();

        if (!CmeGlobexMarketCalendar.isTradingTime(triggerTime)) {
            log.info("CME Globex is CLOSED at {}. Skipping strategy evaluation cycle.", triggerTime);
            return 0;
        }

        log.info("Executing Bar-Close Evaluation Cycle at {}...", triggerTime);
        int totalDispatched = 0;
        List<Position> currentPositions = openPositions != null ? openPositions : List.of();
        Duration maxBarAge = Duration.ofMinutes(intervalMinutes).plus(STALE_BAR_TOLERANCE);

        for (String sym : symbols) {
            try {
                Function<List<Bar>, List<Order>> evaluator = strategyEvaluators.get(sym);
                if (evaluator == null) {
                    log.warn("No strategy evaluator registered for symbol {}", sym);
                    continue;
                }

                List<Bar> bars = barSupplier.apply(sym);
                if (bars == null || bars.isEmpty()) {
                    log.warn("No bar data available for symbol {}", sym);
                    continue;
                }

                Bar newestBar = bars.get(bars.size() - 1);
                if (newestBar.timestamp() != null) {
                    Duration age = Duration.between(newestBar.timestamp(), triggerTime);
                    if (age.compareTo(maxBarAge) > 0) {
                        log.warn("Stale bar data for symbol {}: newest bar timestamp {} is {} old (max allowed {} for interval={}m). Skipping evaluation.",
                            sym, newestBar.timestamp(), age, maxBarAge, intervalMinutes);
                        continue;
                    }
                }

                List<Order> generatedOrders = evaluator.apply(bars);
                if (generatedOrders == null || generatedOrders.isEmpty()) {
                    continue;
                }

                for (Order o : generatedOrders) {
                    SmallAccountMarginGuard.ValidationResult val = marginGuard.validateOrder(
                        o, accountEquity, currentPositions
                    );

                    if (val.allowed()) {
                        Order approved = o.rescaleQuantity(val.approvedQuantity());
                        orderDispatcher.accept(approved);
                        totalDispatched++;
                        log.info("Dispatched approved order for {}: {} {} @ {}",
                            sym, approved.side(), approved.quantity(), approved.price());
                    } else {
                        log.warn("Order rejected by SmallAccountMarginGuard: {}", val.reason());
                    }
                }
            } catch (Exception e) {
                log.error("Error evaluating strategy on symbol {}: {}", sym, e.getMessage(), e);
            }
        }

        return totalDispatched;
    }

    private void runCycleSafe() {
        if (!running) return;
        try {
            double equity = equitySupplier.getAsDouble();
            List<Position> positions = positionSupplier.get();
            runCycle(clock.instant(), equity, positions);
        } catch (Exception e) {
            log.error("Error in scheduled bar cycle: {}", e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        stop();
        scheduler.shutdownNow();
    }
}
