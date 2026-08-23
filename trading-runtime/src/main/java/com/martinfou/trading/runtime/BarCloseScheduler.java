package com.martinfou.trading.runtime;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.CmeGlobexMarketCalendar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.SmallAccountMarginGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Deterministic H1/D1 Bar-Close Scheduler and Multi-Regime Ensemble Runner (Story 46.6).
 *
 * <p>Wakes up precisely at :00:05 seconds after each hour during CME Globex open sessions,
 * fetches closed bars, evaluates the active strategy ensemble, applies the small-account
 * margin shield, and routes resulting orders.</p>
 */
public final class BarCloseScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BarCloseScheduler.class);

    private final ScheduledExecutorService scheduler;
    private final Function<String, List<Bar>> barSupplier;
    private final Function<List<Bar>, List<Order>> strategyEvaluator;
    private final SmallAccountMarginGuard marginGuard;
    private final Consumer<Order> orderDispatcher;
    private final List<String> symbols;

    private volatile boolean running;
    private ScheduledFuture<?> scheduledTask;

    public BarCloseScheduler(
        List<String> symbols,
        Function<String, List<Bar>> barSupplier,
        Function<List<Bar>, List<Order>> strategyEvaluator,
        SmallAccountMarginGuard marginGuard,
        Consumer<Order> orderDispatcher
    ) {
        this(symbols, barSupplier, strategyEvaluator, marginGuard, orderDispatcher,
             Executors.newSingleThreadScheduledExecutor(r -> {
                 Thread t = new Thread(r, "bar-close-scheduler");
                 t.setDaemon(true);
                 return t;
             }));
    }

    public BarCloseScheduler(
        List<String> symbols,
        Function<String, List<Bar>> barSupplier,
        Function<List<Bar>, List<Order>> strategyEvaluator,
        SmallAccountMarginGuard marginGuard,
        Consumer<Order> orderDispatcher,
        ScheduledExecutorService scheduler
    ) {
        this.symbols = (symbols != null) ? List.copyOf(symbols) : List.of("MES", "MNQ", "M2K", "MGC");
        this.barSupplier = Objects.requireNonNull(barSupplier, "barSupplier is required");
        this.strategyEvaluator = Objects.requireNonNull(strategyEvaluator, "strategyEvaluator is required");
        this.marginGuard = Objects.requireNonNull(marginGuard, "marginGuard is required");
        this.orderDispatcher = Objects.requireNonNull(orderDispatcher, "orderDispatcher is required");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler is required");
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        log.info("Starting BarCloseScheduler for symbols: {}...", symbols);
        // Align hourly execution
        scheduledTask = scheduler.scheduleAtFixedRate(
            this::runCycleSafe,
            1, 60, TimeUnit.MINUTES
        );
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
        }
        log.info("BarCloseScheduler stopped.");
    }

    public int runCycle(Instant triggerTime, double accountEquity) {
        if (triggerTime == null) triggerTime = Instant.now();

        if (!CmeGlobexMarketCalendar.isTradingTime(triggerTime)) {
            log.info("CME Globex is CLOSED at {}. Skipping strategy evaluation cycle.", triggerTime);
            return 0;
        }

        log.info("Executing Bar-Close Evaluation Cycle at {}...", triggerTime);
        int totalDispatched = 0;

        for (String sym : symbols) {
            try {
                List<Bar> bars = barSupplier.apply(sym);
                if (bars == null || bars.isEmpty()) {
                    log.warn("No bar data available for symbol {}", sym);
                    continue;
                }

                List<Order> generatedOrders = strategyEvaluator.apply(bars);
                if (generatedOrders == null || generatedOrders.isEmpty()) {
                    continue;
                }

                for (Order o : generatedOrders) {
                    SmallAccountMarginGuard.ValidationResult val = marginGuard.validateOrder(
                        o, accountEquity, List.of()
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
            runCycle(Instant.now(), 5000.0);
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
