package com.martinfou.trading.strategies.newsweekly;

/**
 * 🟢 Wk 20-24 Jul — US GDP Advance (bidirectionnel)
 *
 * Premier estimé du PIB US Q2 — indicateur le plus important du mois.
 * Lit la direction de la barre post-GDP pour déterminer le trade :
 *   Barre haussière EUR/USD → GDP manque le consensus → BUY EUR/USD
 *   Barre baissière EUR/USD → GDP beat le consensus → SELL EUR/USD
 *
 * Fix Red Team: passage en bidirectionnel pour éviter le biais directionnel fixe.
 *
 * Sizing: 1.0% risque (haute confiance), SL 50 pips, TP 80 pips.
 * Capital: $1,000.
 *
 * Valide pour la semaine du 20-24 juillet 2026 UNIQUEMENT.
 */
public class NewsWeek20Jul_UsGdpAdvance extends NewsWeeklyStrategy {

    public static class EurUsd extends NewsWeek20Jul_UsGdpAdvance {
        public EurUsd() { super("EUR_USD", 50, 80, 0.01); }
    }

    public static class GbpUsd extends NewsWeek20Jul_UsGdpAdvance {
        public GbpUsd() { super("GBP_USD", 50, 80, 0.008); }
    }

    protected NewsWeek20Jul_UsGdpAdvance(String symbol, int slPips, int tpPips, double riskPct) {
        super(
            "NewsWeek20Jul_UsGdp_" + symbol,
            symbol,
            nyEvent(2026, 7, 23, 8, 35),   // Thu Jul 23, 08:35 ET — 5 min après GDP (08:30)
            weekEndAfter(2026, 7, 24),
            slPips, tpPips,
            riskPct    // bidirectionnel: lit la barre post-GDP
        );
    }
}
