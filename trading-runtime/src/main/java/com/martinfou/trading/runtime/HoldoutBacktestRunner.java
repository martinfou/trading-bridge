package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;

import java.util.List;

/** Runs holdout slice backtest with frozen config (Story 19.4). */
final class HoldoutBacktestRunner {

    private HoldoutBacktestRunner() {}

    static BacktestResult runHoldout(RunConfigSnapshot snapshot, List<Bar> holdoutBars) {
        return ValidationBacktestRunner.run(snapshot, holdoutBars, snapshot.executionCost());
    }
}
