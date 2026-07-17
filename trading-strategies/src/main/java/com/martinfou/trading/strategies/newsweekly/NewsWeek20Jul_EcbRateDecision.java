package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟢 Wk 20-24 Jul — ECB Rate Decision (directionnel)
 *
 * ECB rate decision typically late July. If rates hold (expected):
 * fade the initial spike, enter 15 min after in opposite direction.
 * If surprise cut/hike: follow the momentum.
 *
 * Sizing: 0.7% risque, SL 50 pips, TP 60 pips.
 * Capital: $1,000.
 *
 * Valide pour la semaine du 20-24 juillet 2026 UNIQUEMENT.
 */
public class NewsWeek20Jul_EcbRateDecision extends NewsWeeklyStrategy {

    public static class EurUsd extends NewsWeek20Jul_EcbRateDecision {
        public EurUsd() { super("EUR_USD", 50, 60, 0.007); }
    }

    protected NewsWeek20Jul_EcbRateDecision(String symbol, int slPips, int tpPips, double riskPct) {
        super(
            "NewsWeek20Jul_Ecb_" + symbol,
            symbol,
            nyEvent(2026, 7, 23, 8, 15),   // Thu Jul 23, 08:15 ET — ECB decision
            weekEndAfter(2026, 7, 24),
            slPips, tpPips,
            riskPct    // bidirectionnel — fade ou suit le spike
        );
    }
}
