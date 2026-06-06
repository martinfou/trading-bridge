package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟢 Wk 8-12 Jun — ECB Dovish Sell EUR/USD
 *
 * Enters SELL EUR/USD after ECB rate decision (Thu Jun 11, 08:15 ET).
 * Thesis: 25bp hike priced in, Lagarde dovish = sell EUR, CPI beat Wed amplifies.
 *
 * Sizing: 0.7% risque ($7 sur $1,000).
 * SL 60 / TP 100.
 *
 * This strategy is valid for the week of June 8-12, 2026 ONLY.
 */
public class NewsWeek8Jun_EcbDovishSellEur extends NewsWeeklyStrategy {

    public NewsWeek8Jun_EcbDovishSellEur() {
        super(
            "NewsWeek8Jun_EcbDovishSellEur",
            "EUR_USD",
            nyEvent(2026, 6, 11, 8, 15),   // Thu Jun 11, 08:15 ET — ECB
            weekEndAfter(2026, 6, 12),
            60, 100,
            Order.Side.SELL,
            0.007   // 0.7% risque
        );
    }
}
