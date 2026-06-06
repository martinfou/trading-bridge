package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🔴 Wk 8-12 Jun — CPI Momentum Sell USD
 *
 * Enters SELL on EUR/USD, AUD/USD, NZD/USD at CPI release (Wed Jun 10, 08:30 ET).
 * Thesis: NFP beat (172K vs 85K) + Core CPI expected 0.5% = USD continues rally.
 *
 * This strategy is valid for the week of June 8-12, 2026 ONLY.
 */
public class NewsWeek8Jun_CpiMomentumSellUsd extends NewsWeeklyStrategy {

    /** SELL EUR_USD variant. */
    public static class EurUsd extends NewsWeek8Jun_CpiMomentumSellUsd {
        public EurUsd() { super("EUR_USD"); }
    }

    /** SELL AUD_USD variant (best pair — most sensitive to USD + China exposure). */
    public static class AudUsd extends NewsWeek8Jun_CpiMomentumSellUsd {
        public AudUsd() { super("AUD_USD"); }
    }

    /** SELL NZD_USD variant. */
    public static class NzdUsd extends NewsWeek8Jun_CpiMomentumSellUsd {
        public NzdUsd() { super("NZD_USD"); }
    }

    public NewsWeek8Jun_CpiMomentumSellUsd(String symbol) {
        super(
            "NewsWeek8Jun_CpiMomentumSellUsd_" + symbol,
            symbol,
            nyEvent(2026, 6, 10, 8, 30),   // Wed Jun 10, 08:30 ET — CPI release
            weekEndAfter(2026, 6, 12),       // End Sunday after Friday June 12
            30,  // 30 pip stop
            50,  // 50 pip target
            Order.Side.SELL
        );
    }
}
