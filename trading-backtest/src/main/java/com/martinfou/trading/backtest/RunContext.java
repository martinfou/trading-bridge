package com.martinfou.trading.backtest;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.persistence.BacktestPersistenceService;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Unified runtime context for strategy execution across backtest, paper, and live modes.
 * Accepts a pre-built {@link Strategy}; catalog resolution belongs in the application layer.
 */
public record RunContext(
    String strategyId,
    String symbol,
    RunMode mode,
    List<Bar> bars,
    double initialCapital,
    Strategy strategy,
    Consumer<RunEvent> eventListener,
    String assignedRunId,
    BacktestExecutionCost executionCost,
    String dataTimeframe,
    String strategyTimeframe,
    boolean persist
) {

    public RunContext {
        bars = List.copyOf(bars);
        if (executionCost == null) {
            executionCost = BacktestExecutionCost.ZERO;
        }
        if (dataTimeframe == null) {
            dataTimeframe = "H1";
        }
        if (strategyTimeframe == null) {
            strategyTimeframe = "H1";
        }
    }

    /**
     * Run with an already-instantiated strategy (e.g. legacy prop suite or examples).
     */
    public static RunContext forStrategy(
        Strategy strategy,
        String symbol,
        RunMode mode,
        List<Bar> bars,
        double initialCapital
    ) {
        return forStrategy(strategy, symbol, mode, bars, initialCapital, null);
    }

    public static RunContext forStrategy(
        Strategy strategy,
        String symbol,
        RunMode mode,
        List<Bar> bars,
        double initialCapital,
        Consumer<RunEvent> eventListener
    ) {
        return forStrategy(null, strategy, symbol, mode, bars, initialCapital, eventListener);
    }

    /**
     * Run with a catalog-resolved strategy id for event metadata (composition layer).
     */
    public static RunContext forStrategy(
        String strategyId,
        Strategy strategy,
        String symbol,
        RunMode mode,
        List<Bar> bars,
        double initialCapital,
        Consumer<RunEvent> eventListener
    ) {
        return forStrategy(null, strategyId, strategy, symbol, mode, bars, initialCapital, eventListener);
    }

    /**
     * Control-plane run with a pre-assigned run id (events use this id consistently).
     */
    public static RunContext forStrategy(
        String assignedRunId,
        String strategyId,
        Strategy strategy,
        String symbol,
        RunMode mode,
        List<Bar> bars,
        double initialCapital,
        Consumer<RunEvent> eventListener
    ) {
        return new RunContext(
            strategyId,
            symbol,
            mode,
            bars,
            initialCapital,
            strategy,
            eventListener,
            assignedRunId,
            BacktestExecutionCost.ZERO,
            null,
            null,
            true);
    }

    public static RunContext forStrategy(
        String assignedRunId,
        String strategyId,
        Strategy strategy,
        String symbol,
        RunMode mode,
        List<Bar> bars,
        double initialCapital,
        Consumer<RunEvent> eventListener,
        BacktestExecutionCost executionCost
    ) {
        return new RunContext(
            strategyId,
            symbol,
            mode,
            bars,
            initialCapital,
            strategy,
            eventListener,
            assignedRunId,
            executionCost,
            null,
            null,
            true);
    }

    public static RunContext forStrategy(
        String assignedRunId,
        String strategyId,
        Strategy strategy,
        String symbol,
        RunMode mode,
        List<Bar> bars,
        double initialCapital,
        Consumer<RunEvent> eventListener,
        BacktestExecutionCost executionCost,
        String dataTimeframe,
        String strategyTimeframe
    ) {
        return new RunContext(
            strategyId,
            symbol,
            mode,
            bars,
            initialCapital,
            strategy,
            eventListener,
            assignedRunId,
            executionCost,
            dataTimeframe,
            strategyTimeframe,
            true);
    }

    /** Returns a copy wired to the given event listener. */
    public RunContext withEventListener(Consumer<RunEvent> listener) {
        return new RunContext(strategyId, symbol, mode, bars, initialCapital, strategy, listener, assignedRunId, executionCost, dataTimeframe, strategyTimeframe, persist);
    }

    /** Returns a copy with the given persist flag. */
    public RunContext withPersist(boolean persist) {
        return new RunContext(strategyId, symbol, mode, bars, initialCapital, strategy, eventListener, assignedRunId, executionCost, dataTimeframe, strategyTimeframe, persist);
    }

    /**
     * Executes this run. {@link RunMode#BACKTEST} and {@link RunMode#PAPER} (stub) are supported.
     */
    public BacktestResult run() {
        String runId = assignedRunId != null ? assignedRunId : UUID.randomUUID().toString();
        String eventStrategyId = strategyId != null ? strategyId : strategy.name();
        var startedPayload = new java.util.LinkedHashMap<String, Object>();
        startedPayload.put("barCount", bars.size());
        startedPayload.put("initialCapital", initialCapital);
        if (!executionCost.isZero()) {
            startedPayload.put("executionCost", executionCost.toMap());
        }
        emit(RunEvent.started(runId, eventStrategyId, symbol, mode, Map.copyOf(startedPayload)));

        try {
            BacktestResult result = switch (mode) {
                case BACKTEST -> executionCost.configure(
                    new BacktestEngine(strategy, bars, initialCapital)
                        .withDataTimeframe(dataTimeframe)
                        .withStrategyTimeframe(strategyTimeframe)
                ).run();
                case PAPER -> executionCost.configure(
                    new BacktestEngine(strategy, bars, initialCapital)
                        .withDataTimeframe(dataTimeframe)
                        .withStrategyTimeframe(strategyTimeframe)
                ).run();
                case LIVE -> throw new UnsupportedOperationException(mode + " not implemented");
            };
            var endedPayload = new java.util.LinkedHashMap<>(BacktestResultPayload.toEndedPayload(result));
            emit(RunEvent.ended(runId, eventStrategyId, symbol, mode, Map.copyOf(endedPayload)));
            if (mode == RunMode.BACKTEST && persist) {
                BacktestPersistenceService.save(runId, this, result);
            }
            return result;
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            emit(RunEvent.error(runId, eventStrategyId, symbol, mode, msg));
            throw e;
        }
    }

    private void emit(RunEvent event) {
        if (eventListener != null) {
            eventListener.accept(event);
        }
    }
}
