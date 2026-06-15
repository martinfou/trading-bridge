package com.martinfou.trading.core.agent;

import java.time.Instant;
import java.util.List;

/**
 * Résultats de backtest par paire.
 */
public record PairResult(
    String symbol,
    double pf,
    double sharpe,
    double dd,
    double winRate,
    int trades,
    double totalPnl
) {
    public boolean qualified(double minPf, double maxDd, int minTrades) {
        return pf >= minPf && dd < maxDd && trades >= minTrades;
    }
}
