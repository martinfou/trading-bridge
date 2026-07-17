package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟢 Wk 20-24 Jul — US Durable Goods (directionnel)
 *
 * Commandes de biens durables US — indicateur avancé de l'investissement.
 * Suit le momentum post-publication.
 *
 * Sizing: 0.7% risque, SL 40 pips, TP 60 pips.
 * Capital: $1,000.
 *
 * Valide pour la semaine du 20-24 juillet 2026 UNIQUEMENT.
 */
public class NewsWeek20Jul_UsDurableGoods extends NewsWeeklyStrategy {

    public static class EurUsd extends NewsWeek20Jul_UsDurableGoods {
        public EurUsd() { super("EUR_USD", 40, 60, 0.007, Order.Side.SELL); }
    }

    protected NewsWeek20Jul_UsDurableGoods(String symbol, int slPips, int tpPips, double riskPct, Order.Side defaultSide) {
        super(
            "NewsWeek20Jul_UsDurable_" + symbol,
            symbol,
            nyEvent(2026, 7, 24, 8, 35),   // Fri Jul 24, 08:35 ET — 5 min après Durable Goods (08:30)
            weekEndAfter(2026, 7, 24),
            slPips, tpPips,
            defaultSide,
            riskPct
        );
    }
}
