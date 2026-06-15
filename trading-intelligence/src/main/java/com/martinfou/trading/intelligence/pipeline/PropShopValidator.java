package com.martinfou.trading.intelligence.pipeline;

import com.martinfou.trading.core.agent.PairResult;
import com.martinfou.trading.core.agent.PipelineResult;
import com.martinfou.trading.core.agent.ValidationProfile;

/**
 * Validation profile for PROP_SHOP strategies.
 *
 * Criteria (adapted from weekly-forex-prop-shop scoring):
 * - ≥ 2/9 pairs qualified
 * - PF ≥ 1.3 on qualified pairs
 * - Sharpe ≥ 0.8
 * - DD < 15%
 * - WR > 40%
 * - Trades ≥ 50 per pair
 *
 * Full 15-point scoring (COT, seasonality, OANDA, soft signals, HMM)
 * requires live market data and is done by the weekly cron separately.
 * This validator checks structural backtest quality.
 */
public class PropShopValidator implements ValidationProfile {

    private static final String[] PAIRS = {
        "EUR_USD", "GBP_USD", "USD_JPY", "AUD_USD",
        "NZD_USD", "USD_CAD", "USD_CHF", "GBP_JPY", "XAU_USD"
    };
    private static final double MIN_PF = 1.3;
    private static final double MIN_SHARPE = 0.8;
    private static final double MAX_DD = 15.0;
    private static final double MIN_WR = 40.0;
    private static final int MIN_TRADES = 50;
    private static final int MIN_QUALIFIED_PAIRS = 2;

    @Override
    public String name() {
        return "PROP_SHOP";
    }

    @Override
    public boolean qualifies(PipelineResult result) {
        int qualified = 0;
        for (var pr : result.pairResults()) {
            if (pr.qualified(MIN_PF, MAX_DD, MIN_TRADES) && pr.sharpe() >= MIN_SHARPE && pr.winRate() >= MIN_WR) {
                qualified++;
            }
        }
        return qualified >= MIN_QUALIFIED_PAIRS;
    }

    @Override
    public String whyRejected(PipelineResult result) {
        var sb = new StringBuilder();
        int qualified = 0;
        for (var pr : result.pairResults()) {
            boolean pfOk = pr.pf() >= MIN_PF;
            boolean ddOk = pr.dd() < MAX_DD;
            boolean trOk = pr.trades() >= MIN_TRADES;
            boolean shOk = pr.sharpe() >= MIN_SHARPE;
            boolean wrOk = pr.winRate() >= MIN_WR;
            boolean q = pfOk && ddOk && trOk && shOk && wrOk;
            if (q) qualified++;

            var issues = new java.util.ArrayList<String>();
            if (!pfOk) issues.add("PF " + String.format("%.2f", pr.pf()) + " < " + MIN_PF);
            if (!shOk) issues.add("Sharpe " + String.format("%.2f", pr.sharpe()) + " < " + MIN_SHARPE);
            if (!ddOk) issues.add("DD " + String.format("%.1f%%", pr.dd()) + " ≥ " + MAX_DD);
            if (!wrOk) issues.add("WR " + String.format("%.0f%%", pr.winRate()) + " < " + MIN_WR);
            if (!trOk) issues.add("trades " + pr.trades() + " < " + MIN_TRADES);

            sb.append(String.format("  %s: PF %.2f Sharpe %.2f DD %.1f%% WR %.0f%% trades %d %s",
                pr.symbol(), pr.pf(), pr.sharpe(), pr.dd(), pr.winRate(), pr.trades(),
                q ? "✅" : "❌"));
            if (!issues.isEmpty()) {
                sb.append(" — ").append(String.join(", ", issues));
            }
            sb.append("\n");
        }
        if (qualified < MIN_QUALIFIED_PAIRS) {
            sb.append(String.format("→ %d/%d pairs qualified (min: %d)%n",
                qualified, result.pairResults().size(), MIN_QUALIFIED_PAIRS));
        }
        return sb.toString();
    }

    @Override
    public String[] requiredPairs() {
        return PAIRS;
    }

    @Override
    public double referenceCapital() {
        return 50_000;
    }
}
