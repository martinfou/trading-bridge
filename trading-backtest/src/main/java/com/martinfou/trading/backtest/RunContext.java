package com.martinfou.trading.backtest;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.paper.PaperExecutor;
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
    Consumer<RunEvent> eventListener
) {

    public RunContext {
        bars = List.copyOf(bars);
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
        return new RunContext(
            strategyId,
            symbol,
            mode,
            bars,
            initialCapital,
            strategy,
            eventListener);
    }

    /** Returns a copy wired to the given event listener. */
    public RunContext withEventListener(Consumer<RunEvent> listener) {
        return new RunContext(strategyId, symbol, mode, bars, initialCapital, strategy, listener);
    }

    /**
     * Executes this run. {@link RunMode#BACKTEST} and {@link RunMode#PAPER} (stub) are supported.
     */
    public BacktestResult run() {
        String runId = UUID.randomUUID().toString();
        String eventStrategyId = strategyId != null ? strategyId : strategy.name();
        emit(RunEvent.started(runId, eventStrategyId, symbol, mode, Map.of(
            "barCount", bars.size(),
            "initialCapital", initialCapital)));

        try {
            BacktestResult result = switch (mode) {
                case BACKTEST -> new BacktestEngine(strategy, bars, initialCapital).run();
                case PAPER -> PaperExecutor.run(strategy, bars, initialCapital);
                case LIVE -> throw new UnsupportedOperationException(mode + " not implemented");
            };
            emit(RunEvent.ended(runId, eventStrategyId, symbol, mode, Map.of(
                "totalTrades", result.totalTrades(),
                "totalReturnPct", result.totalReturnPct(),
                "finalEquity", result.finalEquity())));
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
