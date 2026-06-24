package com.martinfou.trading.backtest.reconciliation;

public record ReconciliationAnomaly(
    AnomalyType type,
    String orderId,
    String message,
    double deltaPrice,
    long deltaTimeMs
) {
    public enum AnomalyType {
        MISSING_LIVE,
        GHOST_LIVE,
        TIME_DRIFT,
        PRICE_DRIFT
    }
}
