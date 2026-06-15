package com.martinfou.trading.intelligence.pipeline;

import com.martinfou.trading.core.agent.PairResult;
import com.martinfou.trading.core.agent.PipelineResult;
import com.martinfou.trading.core.agent.StrategyProfile;
import com.martinfou.trading.core.agent.ValidationProfile;

import java.util.List;

/**
 * Validation profile for LONG_TERM strategies.
 *
 * Walk-forward validation on 4 pairs (EUR/USD, GBP/USD, USD/JPY, AUD/USD)
 * over FULL (2010-2025), IS (2010-2018), OOS1 (2019-2022), OOS2 (2023-2025).
 *
 * Criteria:
 * - PF FULL ≥ 1.05
 * - PF OOS1 ≥ 1.0
 * - PF OOS2 ≥ 1.0
 * - DD max < 20%
 * - Trades ≥ 100
 * - ≥ 2/4 pairs qualified
 */
public class LongTermValidator implements ValidationProfile {

    private static final String[] PAIRS = {"EUR_USD", "GBP_USD", "USD_JPY", "AUD_USD"};
    private static final double MIN_PF_FULL = 1.05;
    private static final double MIN_PF_OOS = 1.0;
    private static final double MAX_DD = 20.0;
    private static final int MIN_TRADES = 100;
    private static final int MIN_QUALIFIED_PAIRS = 2;

    @Override
    public String name() {
        return "LONG_TERM";
    }

    @Override
    public boolean qualifies(PipelineResult result) {
        int qualified = 0;
        for (var pr : result.pairResults()) {
            if (pr.qualified(MIN_PF_FULL, MAX_DD, MIN_TRADES)) {
                qualified++;
            }
        }
        return qualified >= MIN_QUALIFIED_PAIRS
            && result.maxDrawdown() < MAX_DD;
    }

    @Override
    public String whyRejected(PipelineResult result) {
        var sb = new StringBuilder();
        int qualified = 0;
        for (var pr : result.pairResults()) {
            boolean q = pr.qualified(MIN_PF_FULL, MAX_DD, MIN_TRADES);
            if (q) qualified++;
            sb.append(String.format("  %s: PF %.2f%s Sharpe %.2f DD %.1f%% WR %.0f%% trades %d %s%n",
                pr.symbol(), pr.pf(), pr.pf() >= MIN_PF_FULL ? "" : " < " + MIN_PF_FULL,
                pr.sharpe(), pr.dd(), pr.winRate(), pr.trades(),
                q ? "✅" : "❌"));
        }
        if (qualified < MIN_QUALIFIED_PAIRS) {
            sb.append(String.format("→ Seulement %d/%d paires qualifiées (min: %d)%n",
                qualified, result.pairResults().size(), MIN_QUALIFIED_PAIRS));
        }
        if (result.maxDrawdown() >= MAX_DD) {
            sb.append(String.format("→ Drawdown max %.1f%% ≥ %.0f%%%n",
                result.maxDrawdown(), MAX_DD));
        }
        return sb.toString();
    }

    @Override
    public String[] requiredPairs() {
        return PAIRS;
    }

    @Override
    public double referenceCapital() {
        return 10_000;
    }
}
