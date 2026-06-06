package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟢 Wk 8-12 Jun — UK GDP Bounce Fade Sell GBP/USD
 *
 * Thesis: UK GDP expected 0.3% (Fri Jun 12, 08:30 ET).
 * Even if GDP beats, USD strength from NFP + CPI likely dominates.
 * Enters SELL on bounce after release.
 *
 * This strategy is valid for the week of June 8-12, 2026 ONLY.
 */
public class NewsWeek8Jun_UkGdpFadeSellGbp extends NewsWeeklyStrategy {

    public NewsWeek8Jun_UkGdpFadeSellGbp() {
        this("GBP_USD");
    }

    public NewsWeek8Jun_UkGdpFadeSellGbp(String symbol) {
        super(
            "NewsWeek8Jun_UkGdpFadeSellGbp",
            symbol,
            nyEvent(2026, 6, 12, 8, 30),    // Fri Jun 12, 08:30 ET — UK GDP
            weekEndAfter(2026, 6, 12),        // End Sunday after Friday June 12
            30,    // 30 pip stop
            40,    // 40 pip target
            Order.Side.SELL
        );
    }
}
