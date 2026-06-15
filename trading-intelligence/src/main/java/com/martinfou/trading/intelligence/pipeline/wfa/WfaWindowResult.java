package com.martinfou.trading.intelligence.pipeline.wfa;

/**
 * Per-window metrics for Walk-Forward Analysis.
 */
public record WfaWindowResult(
    int index,
    double isProfitFactor,
    double isSharpe,
    double isReturnPct,
    double isMaxDd,
    int isTrades,
    double oosProfitFactor,
    double oosSharpe,
    double oosReturnPct,
    double oosMaxDd,
    int oosTrades
) {

    /** Degradation ratio: OOS PF / IS PF. < 0.5 = overfitting warning. */
    public double pfDegradation() {
        if (isProfitFactor <= 0) return 0;
        return oosProfitFactor / isProfitFactor;
    }

    public boolean passed(double minOosPf, double maxDegradation) {
        return oosProfitFactor >= minOosPf && pfDegradation() >= maxDegradation;
    }
}
