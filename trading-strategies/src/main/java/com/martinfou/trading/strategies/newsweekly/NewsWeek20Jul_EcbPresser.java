package com.martinfou.trading.strategies.newsweekly;

/**
 * 🟢 Wk 20-24 Jul — ECB Press Conference (bidirectionnel)
 *
 * Thu Jul 23, 08:45 ET — HIGH impact. Lagarde's tone often moves EUR more than the rate.
 * Bidirectionnel: 30 min after rate decision, follow the press conference momentum.
 */
public class NewsWeek20Jul_EcbPresser extends NewsWeeklyStrategy {
    public static class EurUsd extends NewsWeek20Jul_EcbPresser {
        public EurUsd() { super("EUR_USD", 60, 100, 0.006); }
    }
    protected NewsWeek20Jul_EcbPresser(String symbol, int slPips, int tpPips, double riskPct) {
        super("NewsWeek20Jul_ECB_Presser_" + symbol, symbol,
            nyEvent(2026, 7, 23, 8, 45), weekEndAfter(2026, 7, 23),
            slPips, tpPips, riskPct);
    }
}
