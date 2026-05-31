package com.martinfou.trading.runtime;

/** Live daily drawdown metrics exposed on control summary (Story 17.10). */
public record DailyDrawdownMetrics(
    double drawdownPct,
    double maxDailyDrawdownPct,
    boolean breached
) {}
