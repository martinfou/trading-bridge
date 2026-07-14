package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟢 Wk 13-17 Jul — UK CPI Momentum (bidirectionnel)
 *
 * Attend 5 minutes après UK CPI (Wed Jul 15, 02:00 ET), puis entre
 * dans la direction du momentum. La BoE est data-dépendante —
 * chaque CPI change les attentes de taux.
 *
 * Direction: lue de la barre post-CPI.
 *   Barre haussière (close > open) → BUY GBP
 *   Barre baissière (close < open) → SELL GBP
 *
 * Sizing: 0.7% risque, SL 50 pips, TP 60 pips.
 * Capital: $1,000.
 *
 * Valide pour la semaine du 13-17 juillet 2026 UNIQUEMENT.
 */
public class NewsWeek13Jul_UkCpiMomentum extends NewsWeeklyStrategy {

    public static class GbpUsd extends NewsWeek13Jul_UkCpiMomentum {
        public GbpUsd() { super("GBP_USD", 50, 60, 0.007); }
    }

    public static class EurGbp extends NewsWeek13Jul_UkCpiMomentum {
        public EurGbp() { super("EUR_GBP", 40, 50, 0.005); }
    }

    protected NewsWeek13Jul_UkCpiMomentum(String symbol, int slPips, int tpPips, double riskPct) {
        super(
            "NewsWeek13Jul_UkCpi_" + symbol,
            symbol,
            nyEvent(2026, 7, 15, 2, 0),    // Wed Jul 15, 02:00 ET — UK CPI
            weekEndAfter(2026, 7, 17),
            slPips, tpPips,
            riskPct    // bidirectionnel
        );
    }
}
