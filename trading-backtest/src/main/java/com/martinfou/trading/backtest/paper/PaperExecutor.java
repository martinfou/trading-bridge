package com.martinfou.trading.backtest.paper;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;

import java.util.List;

/**
 * Paper-trading stub: replays historical bars bar-by-bar with the same fill semantics as
 * {@link BacktestEngine}. No broker network calls — validates the backtest → paper pipeline
 * before Epic 4 live paper integration.
 */
public final class PaperExecutor {

    private PaperExecutor() {}

    /**
     * Runs a paper session over the given bar history (stub = delegates to backtest engine).
     */
    public static BacktestResult run(Strategy strategy, List<Bar> bars, double initialCapital) {
        return new BacktestEngine(strategy, bars, initialCapital).run();
    }
}
