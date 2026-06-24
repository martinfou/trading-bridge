package com.martinfou.trading.backtest.reconciliation;

public record ReconciliationConfig(
    long maxTimeDriftSeconds,
    double maxPriceDrift
) {
    public static final ReconciliationConfig DEFAULT = new ReconciliationConfig(5L, 0.0005);
}
