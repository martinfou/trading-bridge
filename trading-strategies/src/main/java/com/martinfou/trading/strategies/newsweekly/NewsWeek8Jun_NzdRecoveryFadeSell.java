package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟡 Wk 8-12 Jun — NZD Recovery Fade Sell NZD/USD
 *
 * Thesis: NZD/USD -3% on week, two-month low at 0.5790.
 * USD momentum + China weak + RBNZ dovish = more downside.
 * Enters SELL Monday open, trailing stop 40 pips from lowest low.
 *
 * Sizing: 0.5% risque ($5 sur $1,000).
 * SL 60 (initial), trail 40 pips, no fixed TP.
 *
 * This strategy is valid for the week of June 8-12, 2026 ONLY.
 */
public class NewsWeek8Jun_NzdRecoveryFadeSell extends NewsWeeklyStrategy {

    public NewsWeek8Jun_NzdRecoveryFadeSell() {
        super(
            "NewsWeek8Jun_NzdRecoveryFadeSell",
            "NZD_USD",
            nyEvent(2026, 6, 8, 0, 0),    // Mon Jun 8, 00:00 ET
            weekEndAfter(2026, 6, 12),
            60, 0,                         // SL 60, no TP
            Order.Side.SELL,
            0.005,                         // 0.5% risque
            40                             // trail 40 pips
        );
    }
}
