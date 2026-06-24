package com.martinfou.trading.backtest.wfa;

import com.martinfou.trading.core.Trade;
import java.util.List;

/**
 * Record representing the finalWalk-Forward Analysis (WFA) report,
 * ready to be serialized to JSON and consumed by the UI.
 */
public record WfaReport(
    String wfaId,
    String strategyName,
    String instrument,
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
    List<WfaFoldResult> folds,
    List<Trade> oosTrades
) {}
