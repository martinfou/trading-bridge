package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟡 Wk 8-12 Jun — BoJ Intervention Watch Short USD/JPY
 *
 * Thesis: USD/JPY at 160.10 — BoJ intervention zone.
 * Enters SHORT Monday open, holds all week.
 *
 * Sizing: 0.3% risque ($3 sur $1,000) — trade binaire à faible probabilité.
 * SL 150 / TP 350.
 *
 * This strategy is valid for the week of June 8-12, 2026 ONLY.
 */
public class NewsWeek8Jun_BojInterventionShortJpy extends NewsWeeklyStrategy {

    public NewsWeek8Jun_BojInterventionShortJpy() {
        super(
            "NewsWeek8Jun_BojInterventionShortJpy",
            "USD_JPY",
            nyEvent(2026, 6, 8, 0, 0),    // Mon Jun 8, 00:00 ET
            weekEndAfter(2026, 6, 12),
            150, 350,
            Order.Side.SELL,
            0.003   // 0.3% risque
        );
    }
}
