package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟡 Wk 8-12 Jun — NZD Recovery Fade Sell NZD/USD
 *
 * Thesis: NZD/USD -3% on week, two-month low at 0.5790.
 * USD momentum post-NFP + China weakness + RBNZ dovish = more downside.
 * Enters SELL on bounce at Monday open.
 *
 * SL/TP rationale:
 *   SL 60 — bounce technique post -3% peut être violent (40-60 pips).
 *   TP 80 — slide continue, China Trade Balance comme catalyseur supp.
 *
 * This strategy is valid for the week of June 8-12, 2026 ONLY.
 */
public class NewsWeek8Jun_NzdRecoveryFadeSell extends NewsWeeklyStrategy {

    public NewsWeek8Jun_NzdRecoveryFadeSell() {
        this("NZD_USD");
    }

    public NewsWeek8Jun_NzdRecoveryFadeSell(String symbol) {
        super(
            "NewsWeek8Jun_NzdRecoveryFadeSell",
            symbol,
            nyEvent(2026, 6, 8, 0, 0),      // Mon Jun 8, 00:00 ET — start of week
            weekEndAfter(2026, 6, 12),        // End Sunday after Friday June 12
            60,    // SL 60 — bounce technique possible de 40-60 pips
            80,    // TP 80 — slide jusqu'à 0.5650
            Order.Side.SELL
        );
    }
}
