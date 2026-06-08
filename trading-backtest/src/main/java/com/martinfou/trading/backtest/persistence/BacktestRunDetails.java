package com.martinfou.trading.backtest.persistence;

import java.time.Instant;

/**
 * Detailed information of a persisted backtest run, including the full equity curve JSON.
 */
public record BacktestRunDetails(
    String runId,
    String strategyId,
    String symbol,
    Instant periodStart,
    Instant periodEnd,
    String parameters,
    String parameterHash,
    double initialCapital,
    double finalEquity,
    double totalPnl,
    double totalReturnPct,
    int totalTrades,
    int winningTrades,
    int losingTrades,
    double winRatePct,
    double maxDrawdownPct,
    double avgTradePnl,
    double sharpeRatio,
    double sortinoRatio,
    double profitFactor,
    double calmarRatio,
    double totalCommission,
    double totalSlippage,
    String equityCurve,
    Instant createdAt
) {
    public BacktestRunSummary toSummary() {
        return new BacktestRunSummary(
            runId, strategyId, symbol, periodStart, periodEnd, parameters, parameterHash,
            initialCapital, finalEquity, totalPnl, totalReturnPct, totalTrades, winningTrades,
            losingTrades, winRatePct, maxDrawdownPct, avgTradePnl, sharpeRatio, sortinoRatio,
            profitFactor, calmarRatio, totalCommission, totalSlippage, createdAt
        );
    }
}
