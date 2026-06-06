package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟢 Wk 8-12 Jun — ECB Dovish Sell EUR/USD
 *
 * Enters SELL EUR/USD after ECB rate decision (Thu Jun 11, 08:15 ET).
 * Expects: 25bp hike BUT Lagarde signals pause/fin de cycle.
 * Combined with hot CPI from Wednesday = USD strength dominates.
 *
 * SL/TP rationale:
 *   SL 60 — Lagarde hawkish moves EUR +50-80 pips against us.
 *   TP 100 — CPI beat + dovish Lagarde combo gives EUR/USD -80 to -150 pips.
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
            60,    // SL 60 — Lagarde hawkish fait monter EUR de 50-80 pips
            100,   // TP 100 — CPI beat + ECB dovish = EUR/USD sous 1.14
            Order.Side.SELL
        );
    }
}
