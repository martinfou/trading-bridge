package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟢 Wk 20-24 Jul — US GDP Advance (directionnel)
 *
 * Premier estimé du PIB US Q2 — indicateur le plus important du mois.
 * GDP > consensus → BUY USD. GDP < consensus → SELL USD.
 * Entrée 5 min après publication pour laisser la volatilité initiale passer.
 *
 * Sizing: 1.0% risque (haute confiance), SL 50 pips, TP 80 pips.
 * Capital: $1,000.
 *
 * Valide pour la semaine du 20-24 juillet 2026 UNIQUEMENT.
 */
public class NewsWeek20Jul_UsGdpAdvance extends NewsWeeklyStrategy {

    public static class EurUsd extends NewsWeek20Jul_UsGdpAdvance {
        public EurUsd() { super("EUR_USD", 50, 80, 0.01, Order.Side.SELL); }
    }

    public static class GbpUsd extends NewsWeek20Jul_UsGdpAdvance {
        public GbpUsd() { super("GBP_USD", 50, 80, 0.008, Order.Side.SELL); }
    }

    protected NewsWeek20Jul_UsGdpAdvance(String symbol, int slPips, int tpPips, double riskPct, Order.Side defaultSide) {
        super(
            "NewsWeek20Jul_UsGdp_" + symbol,
            symbol,
            nyEvent(2026, 7, 23, 8, 35),   // Thu Jul 23, 08:35 ET — 5 min après GDP (08:30)
            weekEndAfter(2026, 7, 24),
            slPips, tpPips,
            defaultSide,
            riskPct
        );
    }
}
