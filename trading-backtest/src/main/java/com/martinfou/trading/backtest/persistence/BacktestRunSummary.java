package com.martinfou.trading.backtest.persistence;

import java.time.Instant;

/**
 * Summarised information of a persisted backtest run, excluding the heavy equity curve.
 */
public record BacktestRunSummary(
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
    Instant createdAt
) {}
