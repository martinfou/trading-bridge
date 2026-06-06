package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.util.List;

/** Synchronous validation backtest using shared {@link BacktestExecutionCost} (Stories 19.4–19.5). */
final class ValidationBacktestRunner {

    private ValidationBacktestRunner() {}

    static BacktestResult run(RunConfigSnapshot snapshot, List<Bar> bars, BacktestExecutionCost cost) {
        double capital = snapshot.resolvedCapital();
        BacktestExecutionCost profile = cost != null ? cost : snapshot.executionCost();
        RunContext context = RunContext.forStrategy(
            null,
            snapshot.strategyId(),
            StrategyCatalog.create(snapshot.strategyId(), snapshot.symbol(), snapshot.quantity()),
            snapshot.symbol(),
            RunMode.BACKTEST,
            bars,
            capital,
            null,
            profile);
        return context.run();
    }
}
