package com.martinfou.trading.runtime.wfa;

/**
 * Real-time progress response for a WFA task.
 */
public record WfaProgressResponse(
    String wfaId,
    String strategyName,
    String symbol,
    String assetClass,
    String status,
    int totalFolds,
    int completedFolds,
    int currentFold,
    Double oosSharpe,
    Double wfe,
    String errorMessage
) {}
