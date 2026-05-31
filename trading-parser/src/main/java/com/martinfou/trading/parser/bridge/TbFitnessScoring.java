package com.martinfou.trading.parser.bridge;

import com.martinfou.trading.backtest.BacktestResult;

/** Composite fitness score for SQ external indicator feedback (story 21-8). */
public final class TbFitnessScoring {

    private TbFitnessScoring() {}

    public static double compositeScore(BacktestResult result) {
        double sharpe = Math.max(result.sharpeRatio(), 0.0);
        double pf = Math.max(result.profitFactor(), 0.0);
        double ddPenalty = Math.max(result.maxDrawdownPct(), 0.0) / 100.0;
        double returnBoost = Math.max(result.totalReturnPct(), -100.0) / 100.0;
        return sharpe * 0.45 + pf * 0.25 + returnBoost * 0.15 + (1.0 - Math.min(ddPenalty, 1.0)) * 0.15;
    }
}
