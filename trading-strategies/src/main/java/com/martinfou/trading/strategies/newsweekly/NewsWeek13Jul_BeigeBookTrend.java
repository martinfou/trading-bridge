package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟢 Wk 13-17 Jul — Fed Beige Book Pre-Trend (directionnel)
 *
 * Ne trade PAS la publication, mais entre 30 min AVANT (Wed Jul 15, 13:30 ET).
 * Prend la direction du momentum du jour. Le Beige Book confirme rarement
 * un changement de direction — il renforce le trend existant.
 *
 * Sizing: 0.5% risque, SL 40 pips, TP 50 pips.
 * Capital: $1,000.
 *
 * Valide pour la semaine du 13-17 juillet 2026 UNIQUEMENT.
 */
public class NewsWeek13Jul_BeigeBookTrend extends NewsWeeklyStrategy {

    public static class EurUsd extends NewsWeek13Jul_BeigeBookTrend {
        public EurUsd() { super("EUR_USD", 40, 50, 0.005, Order.Side.BUY); }
    }

    protected NewsWeek13Jul_BeigeBookTrend(String symbol, int slPips, int tpPips, double riskPct, Order.Side defaultSide) {
        super(
            "NewsWeek13Jul_BeigeBook_" + symbol,
            symbol,
            nyEvent(2026, 7, 15, 13, 30),  // Wed Jul 15, 13:30 ET — 30 min avant Beige Book (14:00)
            weekEndAfter(2026, 7, 17),
            slPips, tpPips,
            defaultSide,
            riskPct
        );
    }
}
