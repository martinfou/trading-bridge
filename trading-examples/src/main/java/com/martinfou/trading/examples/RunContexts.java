package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.util.List;
import java.util.function.Consumer;

/**
 * Composition-layer factories: resolve strategy IDs via {@link StrategyCatalog},
 * then build {@link RunContext} for the backtest runtime.
 */
public final class RunContexts {

    private RunContexts() {}

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
        return RunContext.forStrategy(
            strategyId,
            StrategyCatalog.create(strategyId, symbol),
            symbol,
            RunMode.BACKTEST,
            bars,
            capital,
            eventListener);
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
        return RunContext.forStrategy(
            strategyId,
            StrategyCatalog.create(strategyId, symbol),
            symbol,
            RunMode.PAPER,
            bars,
            capital,
            eventListener);
    }
}
