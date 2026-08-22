package com.martinfou.trading.backtest.persistence;

import java.time.Instant;

/**
 * Immutable record representing a persisted Walk-Forward Analysis run summary.
 */
public record WfaRunRecord(
    String wfaId,
    String strategyName,
    String symbol,
    String assetClass,
    String timeframe,
    int inSampleDays,
    int outOfSampleDays,
    boolean anchored,
    double initialCapital,
    double wfe,
    double oosSharpe,
    double oosMaxDrawdownPct,
    double oosProfitFactor,
    double oosReturnPct,
    int oosTradesCount,
    String status,
    Instant createdAt,
    Instant completedAt,
    String errorMessage
) {
    public WfaRunRecord {
        if (wfaId == null || wfaId.isBlank()) {
            throw new IllegalArgumentException("wfaId cannot be null or blank");
        }
        if (strategyName == null || strategyName.isBlank()) {
            throw new IllegalArgumentException("strategyName cannot be null or blank");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be null or blank");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status cannot be null or blank");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt cannot be null");
        }
    }
}
