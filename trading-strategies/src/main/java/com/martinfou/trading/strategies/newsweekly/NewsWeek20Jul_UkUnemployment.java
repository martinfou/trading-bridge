package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟢 Wk 20-24 Jul — UK Unemployment Rate (directionnel)
 *
 * Tue Jul 21, 02:00 ET — HIGH impact on GBP/USD.
 * Directionnel: lit la barre post-publication et suit le momentum.
 */
public class NewsWeek20Jul_UkUnemployment extends NewsWeeklyStrategy {
    public static class GbpUsd extends NewsWeek20Jul_UkUnemployment {
        public GbpUsd() { super("GBP_USD", 50, 80, 0.008); }
    }
    protected NewsWeek20Jul_UkUnemployment(String symbol, int slPips, int tpPips, double riskPct) {
        super("NewsWeek20Jul_UK_Unemployment_" + symbol, symbol,
            nyEvent(2026, 7, 21, 2, 0), weekEndAfter(2026, 7, 21),
            slPips, tpPips, riskPct);
    }
}
