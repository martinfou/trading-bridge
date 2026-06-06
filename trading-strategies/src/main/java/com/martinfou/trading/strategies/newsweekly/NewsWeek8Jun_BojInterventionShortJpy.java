package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟡 Wk 8-12 Jun — BoJ Intervention Watch Short USD/JPY
 *
 * Thesis: USD/JPY at 160.10 — BoJ intervention zone (historical level).
 * Enters SHORT at the open of the first bar of the week (Mon Jun 8).
 * Risk: extreme volatility, possible multi-day BoJ intervention.
 *
 * This strategy is valid for the week of June 8-12, 2026 ONLY.
 */
public class NewsWeek8Jun_BojInterventionShortJpy extends NewsWeeklyStrategy {

    public NewsWeek8Jun_BojInterventionShortJpy() {
        this("USD_JPY");
    }

    public NewsWeek8Jun_BojInterventionShortJpy(String symbol) {
        super(
            "NewsWeek8Jun_BojInterventionShortJpy",
            symbol,
            nyEvent(2026, 6, 8, 0, 0),      // Mon Jun 8, 00:00 ET — start of week
            weekEndAfter(2026, 6, 12),        // End Sunday after Friday June 12
            50,    // 50 pip stop (above intervention high)
            100,   // 100 pip target (160.50 → 159.00)
            Order.Side.SELL
        );
    }
}
