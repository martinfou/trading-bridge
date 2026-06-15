package com.martinfou.trading.intelligence.pipeline.wfa;

import java.util.List;

/**
 * Result of a Walk-Forward Analysis.
 * Contains the merged OOS equity curve and per-window metrics.
 */
public record WfaResult(
    List<WfaWindowResult> windows,
    double mergedOosProfitFactor,
    double mergedOosSharpe,
    double mergedOosReturnPct,
    double mergedOosMaxDd,
    int totalOosTrades,
    boolean passed
) {

    public String summary() {
        var sb = new StringBuilder();
        sb.append(String.format("WFA: %d windows, merged OOS PF=%.2f Sharpe=%.2f return=%.1f%% DD=%.1f%% trades=%d %s%n",
            windows.size(), mergedOosProfitFactor, mergedOosSharpe,
            mergedOosReturnPct, mergedOosMaxDd, totalOosTrades,
            passed ? "✅" : "❌"));
        for (var w : windows) {
            sb.append(String.format("  Window %d: IS PF=%.2f → OOS PF=%.2f Sharpe=%.2f trades=%d%n",
                w.index(), w.isProfitFactor(), w.oosProfitFactor(),
                w.oosSharpe(), w.oosTrades()));
        }
        return sb.toString();
    }
}
