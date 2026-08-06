package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * CPI + PPI + Retail Sales news trading strategies for week August 10-14, 2026.
 *
 * Three major economic catalysts this week:
 * - CPI Inflation Wed Aug 12 08:30 ET — bidirectional news momentum, EUR_USD & GBP_USD
 * - PPI Inflation Thu Aug 13 08:30 ET — producer inflation lead indicator, EUR_USD & USD_JPY
 * - Retail Sales Fri Aug 14 08:30 ET — consumer demand pulse, EUR_USD & USD_CAD
 *
 * Each inner class represents a strategy trade on a specific currency pair.
 */
public class NewsWeek10Aug_CpiRetailSales {

    /** CPI Wed Aug 12 08:30 ET — Bidirectional Momentum on EUR_USD */
    public static class CpiEurUsd extends NewsWeeklyStrategy {
        public CpiEurUsd() {
            super("CpiEurUsd", "EUR_USD",
                nyEvent(2026, 8, 12, 8, 30), weekEndAfter(2026, 8, 14),
                45, 80, 0.007);
        }
    }

    /** CPI Wed Aug 12 08:30 ET — Bidirectional Momentum on GBP_USD */
    public static class CpiGbpUsd extends NewsWeeklyStrategy {
        public CpiGbpUsd() {
            super("CpiGbpUsd", "GBP_USD",
                nyEvent(2026, 8, 12, 8, 30), weekEndAfter(2026, 8, 14),
                50, 90, 0.008);
        }
    }

    /** PPI Thu Aug 13 08:30 ET — Bidirectional on USD_JPY */
    public static class PpiUsdJpy extends NewsWeeklyStrategy {
        public PpiUsdJpy() {
            super("PpiUsdJpy", "USD_JPY",
                nyEvent(2026, 8, 13, 8, 30), weekEndAfter(2026, 8, 14),
                40, 75, 0.007);
        }
    }

    /** Retail Sales Fri Aug 14 08:30 ET — Bidirectional on EUR_USD */
    public static class RetailSalesEurUsd extends NewsWeeklyStrategy {
        public RetailSalesEurUsd() {
            super("RetailSalesEurUsd", "EUR_USD",
                nyEvent(2026, 8, 14, 8, 30), weekEndAfter(2026, 8, 14),
                40, 70, 0.007);
        }
    }

    /** Retail Sales Fri Aug 14 08:30 ET — Bidirectional on USD_CAD */
    public static class RetailSalesUsdCad extends NewsWeeklyStrategy {
        public RetailSalesUsdCad() {
            super("RetailSalesUsdCad", "USD_CAD",
                nyEvent(2026, 8, 14, 8, 30), weekEndAfter(2026, 8, 14),
                45, 80, 0.007);
        }
    }
}
