package com.martinfou.trading.runtime.wfa;

import com.martinfou.trading.backtest.persistence.WfaRunRecord;
import java.time.Instant;

/**
 * Summary DTO of a finished or in-progress WFA run.
 */
public record WfaSummaryResponse(
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
    public static WfaSummaryResponse fromRecord(WfaRunRecord r) {
        if (r == null) return null;
        return new WfaSummaryResponse(
            r.wfaId(),
            r.strategyName(),
            r.symbol(),
            r.assetClass(),
            r.timeframe(),
            r.inSampleDays(),
            r.outOfSampleDays(),
            r.anchored(),
            r.initialCapital(),
            r.wfe(),
            r.oosSharpe(),
            r.oosMaxDrawdownPct(),
            r.oosProfitFactor(),
            r.oosReturnPct(),
            r.oosTradesCount(),
            r.status(),
            r.createdAt(),
            r.completedAt(),
            r.errorMessage()
        );
    }
}
