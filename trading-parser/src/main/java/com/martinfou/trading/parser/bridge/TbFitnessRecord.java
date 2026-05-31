package com.martinfou.trading.parser.bridge;

import com.martinfou.trading.backtest.BacktestResult;

import java.time.Instant;

/**
 * TB backtest fitness metrics exported to StrategyQuant (story 21-8).
 *
 * @param manifestId       strategy key from inbox manifest
 * @param symbol           instrument
 * @param processedAt      UTC timestamp for CSV row
 * @param sharpeRatio      annualised Sharpe
 * @param profitFactor     gross profit / gross loss
 * @param maxDrawdownPct   peak-to-trough drawdown %
 * @param compositeScore   weighted TB validation score
 */
public record TbFitnessRecord(
    String manifestId,
    String symbol,
    Instant processedAt,
    double sharpeRatio,
    double profitFactor,
    double maxDrawdownPct,
    double compositeScore
) {

    public static TbFitnessRecord fromBacktest(String manifestId, String symbol, BacktestResult result, Instant processedAt) {
        return new TbFitnessRecord(
            manifestId,
            symbol,
            processedAt,
            result.sharpeRatio(),
            result.profitFactor(),
            result.maxDrawdownPct(),
            TbFitnessScoring.compositeScore(result)
        );
    }

    public static TbFitnessRecord fromInboxResult(SqInboxResult result) {
        if (result.sharpeRatio() == null || result.profitFactor() == null
            || result.maxDrawdownPct() == null || result.compositeScore() == null) {
            throw new IllegalArgumentException("Inbox result missing fitness metrics: " + result.manifestId());
        }
        return new TbFitnessRecord(
            result.manifestId(),
            result.symbol(),
            result.processedAt(),
            result.sharpeRatio(),
            result.profitFactor(),
            result.maxDrawdownPct(),
            result.compositeScore()
        );
    }
}
