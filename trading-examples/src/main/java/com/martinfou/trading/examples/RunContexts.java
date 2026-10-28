package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesContract;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Composition-layer factories: resolve strategy IDs via {@link StrategyCatalog},
 * then build {@link RunContext} for the backtest runtime.
 */
public final class RunContexts {

    private static final double FUTURES_COMMISSION_PER_CONTRACT = 0.62;

    private RunContexts() {}

    public static String inferTimeframe(String strategyId) {
        if (strategyId == null) return "H1";
        String upper = strategyId.toUpperCase();
        if (upper.contains("M15")) return "M15";
        if (upper.contains("M30")) return "M30";
        if (upper.contains("H4")) return "H4";
        if (upper.contains("D1")) return "D1";
        return "H1";
    }

    public static BacktestExecutionCost executionCostFor(String symbol) {
        Optional<FuturesContract> fut = FuturesRegistry.find(symbol);
        if (fut.isPresent()) {
            return new BacktestExecutionCost(
                FUTURES_COMMISSION_PER_CONTRACT, 0.0, 0.0, fut.get().minTick(), 0.0);
        }
        return BacktestExecutionCost.DEFAULT;
    }

    public static RunContext backtest(String strategyId, String symbol, List<Bar> bars, double capital) {
        return backtest(strategyId, symbol, bars, capital, null);
    }

    public static RunContext backtest(
        String strategyId,
        String symbol,
        List<Bar> bars,
        double capital,
        Consumer<RunEvent> eventListener
    ) {
        String tf = inferTimeframe(strategyId);
        return RunContext.forStrategy(
            null,
            strategyId,
            StrategyCatalog.create(strategyId, symbol),
            symbol,
            RunMode.BACKTEST,
            bars,
            capital,
            eventListener,
            executionCostFor(symbol),
            tf,
            tf);
    }

    public static RunContext paper(String strategyId, String symbol, List<Bar> bars, double capital) {
        return paper(strategyId, symbol, bars, capital, null);
    }

    public static RunContext paper(
        String strategyId,
        String symbol,
        List<Bar> bars,
        double capital,
        Consumer<RunEvent> eventListener
    ) {
        String tf = inferTimeframe(strategyId);
        return RunContext.forStrategy(
            null,
            strategyId,
            StrategyCatalog.create(strategyId, symbol),
            symbol,
            RunMode.PAPER,
            bars,
            capital,
            eventListener,
            executionCostFor(symbol),
            tf,
            tf);
    }
}
