package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟢 Wk 8-12 Jun — CPI Momentum Sell USD
 *
 * Enters SELL on EUR/USD, AUD/USD, NZD/USD at CPI release (Wed Jun 10, 08:30 ET).
 * Thesis: NFP beat (172K vs 85K) + Core CPI expected 0.5% = USD continues rally.
 *
 * SL/TP rationale:
 *   AUD/USD (Très haute) : SL 50 — CPI miss gives +50-80 pips, we exit before that.
 *                           TP 80 — CPI beat historically moves 80-120 pips.
 *   NZD/USD (Très haute) : SL 50, TP 80 — same reasoning, NZD most vulnerable.
 *   EUR/USD (Haute)      : SL 50, TP 100 — more room because ECB next day adds uncertainty.
 *
 * This strategy is valid for the week of June 8-12, 2026 ONLY.
 */
public class NewsWeek8Jun_CpiMomentumSellUsd extends NewsWeeklyStrategy {

    /** SELL AUD_USD — best pair (Chine + matières premières + -1.26% vendredi). Très haute confiance. */
    public static class AudUsd extends NewsWeek8Jun_CpiMomentumSellUsd {
        public AudUsd() { super("AUD_USD", 50, 80); }
    }

    /** SELL NZD_USD — -3% sur la semaine, bas 2 mois. Très haute confiance. */
    public static class NzdUsd extends NewsWeek8Jun_CpiMomentumSellUsd {
        public NzdUsd() { super("NZD_USD", 50, 80); }
    }

    /** SELL EUR_USD — bonne paire mais ECB jeudi ajoute de l'incertitude. Haute confiance. */
    public static class EurUsd extends NewsWeek8Jun_CpiMomentumSellUsd {
        public EurUsd() { super("EUR_USD", 50, 100); }
    }

    protected NewsWeek8Jun_CpiMomentumSellUsd(String symbol, int slPips, int tpPips) {
        super(
            "NewsWeek8Jun_CpiMomentumSellUsd_" + symbol,
            symbol,
            nyEvent(2026, 6, 10, 8, 30),   // Wed Jun 10, 08:30 ET — CPI release
            weekEndAfter(2026, 6, 12),       // End Sunday after Friday June 12
            slPips, tpPips,
            Order.Side.SELL
        );
    }
}
