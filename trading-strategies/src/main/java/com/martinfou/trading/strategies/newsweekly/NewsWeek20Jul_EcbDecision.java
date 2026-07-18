package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟢 Wk 20-24 Jul — ECB Rate Decision (bidirectionnel)
 *
 * Thu Jul 23, 08:15 ET — HIGH impact on EUR/USD, EUR/GBP.
 * Bidirectionnel: suit la direction post-publication (hike/hold/cut).
 */
public class NewsWeek20Jul_EcbDecision extends NewsWeeklyStrategy {
    public static class EurUsd extends NewsWeek20Jul_EcbDecision {
        public EurUsd() { super("EUR_USD", 60, 100, 0.008); }
    }
    protected NewsWeek20Jul_EcbDecision(String symbol, int slPips, int tpPips, double riskPct) {
        super("NewsWeek20Jul_ECB_Decision_" + symbol, symbol,
            nyEvent(2026, 7, 23, 8, 15), weekEndAfter(2026, 7, 23),
            slPips, tpPips, riskPct);
    }
}
