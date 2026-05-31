package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;

/** Per-run risk state for broker execution loops (Story 17.10). */
final class RunRiskContext {

    @FunctionalInterface
    interface BreachHandler {
        void onBreach(String runId, RunConfigSnapshot config, RunMode mode, RiskCheckResult check);
    }

    final DailyDrawdownTracker tracker = new DailyDrawdownTracker();
    final RiskEngine riskEngine;
    final double maxDailyDrawdownPct;
    final BreachHandler breachHandler;
    final java.util.function.Consumer<DailyDrawdownMetrics> metricsSink;
    boolean dailyDdPaused;
    boolean breachHandled;

    RunRiskContext(
        RiskEngine riskEngine,
        BreachHandler breachHandler,
        java.util.function.Consumer<DailyDrawdownMetrics> metricsSink
    ) {
        this.riskEngine = riskEngine != null ? riskEngine : new RiskEngine();
        this.maxDailyDrawdownPct = this.riskEngine.limits().maxDailyDrawdownPct();
        this.breachHandler = breachHandler;
        this.metricsSink = metricsSink;
    }
}
