package com.martinfou.trading.parser.bridge;

import com.martinfou.trading.backtest.BacktestResult;

import java.time.Instant;

/**
 * Summary written beside a processed XML as {@code *-result.json} (story 21-2).
 */
public record SqInboxResult(
    String manifestId,
    String symbol,
    String disposition,
    boolean success,
    int totalTrades,
    double totalReturnPct,
    double finalEquity,
    String errorMessage,
    Instant processedAt,
    Double sharpeRatio,
    Double profitFactor,
    Double maxDrawdownPct,
    Double compositeScore
) {
    public static SqInboxResult success(
        String manifestId,
        String symbol,
        int totalTrades,
        double totalReturnPct,
        double finalEquity
    ) {
        return success(manifestId, symbol, totalTrades, totalReturnPct, finalEquity, null, null, null, null);
    }

    public static SqInboxResult success(
        String manifestId,
        String symbol,
        BacktestResult result
    ) {
        Instant processedAt = Instant.now();
        return success(
            manifestId,
            symbol,
            result.totalTrades(),
            result.totalReturnPct(),
            result.finalEquity(),
            result.sharpeRatio(),
            result.profitFactor(),
            result.maxDrawdownPct(),
            TbFitnessScoring.compositeScore(result),
            processedAt
        );
    }

    public static SqInboxResult success(
        String manifestId,
        String symbol,
        int totalTrades,
        double totalReturnPct,
        double finalEquity,
        Double sharpeRatio,
        Double profitFactor,
        Double maxDrawdownPct,
        Double compositeScore
    ) {
        return success(
            manifestId, symbol, totalTrades, totalReturnPct, finalEquity,
            sharpeRatio, profitFactor, maxDrawdownPct, compositeScore, Instant.now()
        );
    }

    public static SqInboxResult success(
        String manifestId,
        String symbol,
        int totalTrades,
        double totalReturnPct,
        double finalEquity,
        Double sharpeRatio,
        Double profitFactor,
        Double maxDrawdownPct,
        Double compositeScore,
        Instant processedAt
    ) {
        return new SqInboxResult(
            manifestId,
            symbol,
            "passed",
            true,
            totalTrades,
            totalReturnPct,
            finalEquity,
            null,
            processedAt,
            sharpeRatio,
            profitFactor,
            maxDrawdownPct,
            compositeScore
        );
    }

    public static SqInboxResult failure(String manifestId, String symbol, String errorMessage) {
        return new SqInboxResult(
            manifestId,
            symbol,
            "failed",
            false,
            0,
            0.0,
            0.0,
            errorMessage,
            Instant.now(),
            null,
            null,
            null,
            null
        );
    }

    public static SqInboxResult dlq(String manifestId, String symbol, String reason) {
        return new SqInboxResult(
            manifestId,
            symbol,
            "dlq",
            false,
            0,
            0.0,
            0.0,
            reason,
            Instant.now(),
            null,
            null,
            null,
            null
        );
    }
}
