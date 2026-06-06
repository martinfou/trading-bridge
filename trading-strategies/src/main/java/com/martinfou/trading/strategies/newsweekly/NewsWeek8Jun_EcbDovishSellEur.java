package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🔴 Wk 8-12 Jun — ECB Dovish Sell EUR/USD
 *
 * Enters SELL EUR/USD after ECB rate decision (Thu Jun 11, 08:15 ET).
 * Expects: 25bp hike BUT Lagarde signals pause/fin de cycle.
 * Combined with hot CPI from Wednesday = USD strength dominates.
 *
 * This strategy is valid for the week of June 8-12, 2026 ONLY.
 */
public class NewsWeek8Jun_EcbDovishSellEur extends NewsWeeklyStrategy {

    public NewsWeek8Jun_EcbDovishSellEur() {
        this("EUR_USD");
    }

    public NewsWeek8Jun_EcbDovishSellEur(String symbol) {
        super(
            "NewsWeek8Jun_EcbDovishSellEur",
            symbol,
            nyEvent(2026, 6, 11, 8, 15),   // Thu Jun 11, 08:15 ET — ECB rate decision
            weekEndAfter(2026, 6, 12),       // End Sunday after Friday June 12
            50,   // 50 pip stop (wider: range pre-NFP swing high at 1.1700)
            80,   // 80 pip target (1.1400 → 1.1300)
            Order.Side.SELL
        );
    }
}
