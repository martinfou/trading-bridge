package com.martinfou.trading.strategies.newsweekly;

/**
 * 🟢 Wk 20-24 Jul — US Durable Goods (bidirectionnel)
 *
 * Commandes de biens durables US — indicateur avancé de l'investissement.
 * Lit la direction de la barre post-publication :
 *   Barre haussière EUR/USD → Durable Goods manque → BUY EUR/USD
 *   Barre baissière EUR/USD → Durable Goods beat → SELL EUR/USD
 *
 * Fix Red Team: passage en bidirectionnel — évite le biais 'SELL par défaut'.
 *
 * Sizing: 0.7% risque, SL 40 pips, TP 60 pips.
 * Capital: $1,000.
 *
 * Valide pour la semaine du 20-24 juillet 2026 UNIQUEMENT.
 */
public class NewsWeek20Jul_UsDurableGoods extends NewsWeeklyStrategy {

    public static class EurUsd extends NewsWeek20Jul_UsDurableGoods {
        public EurUsd() { super("EUR_USD", 40, 60, 0.007); }
    }

    protected NewsWeek20Jul_UsDurableGoods(String symbol, int slPips, int tpPips, double riskPct) {
        super(
            "NewsWeek20Jul_UsDurable_" + symbol,
            symbol,
            nyEvent(2026, 7, 24, 8, 35),   // Fri Jul 24, 08:35 ET — 5 min après Durable Goods (08:30)
            weekEndAfter(2026, 7, 24),
            slPips, tpPips,
            riskPct    // bidirectionnel: lit la barre post-publication
        );
    }
}
