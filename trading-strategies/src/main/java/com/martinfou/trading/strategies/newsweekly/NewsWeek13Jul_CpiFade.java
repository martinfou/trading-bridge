package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟢 Wk 13-17 Jul — CPI/PPI Momentum Fade (bidirectionnel)
 *
 * Attend le premier spike 1 barre après US CPI (Wed Jul 15, 08:30 ET).
 * Si la barre est directionnelle (> 15 pips range), fade dans la direction opposée.
 * Le premier spike émotionnel se retourne souvent dans les minutes suivantes.
 *
 * Sizing: 0.7% risque, SL 50 pips, TP 40 pips (TP plus serré car fade).
 * Capital: $1,000.
 *
 * Valide pour la semaine du 13-17 juillet 2026 UNIQUEMENT.
 */
public class NewsWeek13Jul_CpiFade extends NewsWeeklyStrategy {

    public static class EurUsd extends NewsWeek13Jul_CpiFade {
        public EurUsd() { super("EUR_USD", 50, 40, 0.007); }
    }

    public static class GbpUsd extends NewsWeek13Jul_CpiFade {
        public GbpUsd() { super("GBP_USD", 50, 40, 0.005); }
    }

    protected NewsWeek13Jul_CpiFade(String symbol, int slPips, int tpPips, double riskPct) {
        super(
            "NewsWeek13Jul_CpiFade_" + symbol,
            symbol,
            nyEvent(2026, 7, 15, 8, 30),   // Wed Jul 15, 08:30 ET — US CPI release
            weekEndAfter(2026, 7, 17),
            slPips, tpPips,
            riskPct    // bidirectionnel: fade le spike
        );
    }
}
