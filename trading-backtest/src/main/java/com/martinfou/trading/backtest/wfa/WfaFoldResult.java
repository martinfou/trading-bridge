package com.martinfou.trading.backtest.wfa;

import java.util.Map;

/**
 * Record representing the performance metrics and chosen parameters for a single fold.
 */
public record WfaFoldResult(
    int index,
    String isStart,
    String isEnd,
    String oosStart,
    String oosEnd,
    Map<String, Double> chosenParameters,
    double isSharpe,
    double oosSharpe,
    int isTradesCount,
    int oosTradesCount,
    double isReturnPct,
    double oosReturnPct,
    double isMaxDrawdownPct,
    double oosMaxDrawdownPct,
    int purgedTradesCount
) {}
