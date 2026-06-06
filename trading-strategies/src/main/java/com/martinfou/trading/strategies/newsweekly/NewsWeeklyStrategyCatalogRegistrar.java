package com.martinfou.trading.strategies.newsweekly;

/** Registers all news/weekly strategies for the current week. */
public final class NewsWeeklyStrategyCatalogRegistrar {

    private NewsWeeklyStrategyCatalogRegistrar() {}

    public static void registerAll() {
        // === Week 8-12 June 2026 ===

        // 1a — CPI Momentum SELL EUR/USD (Wed Jun 10, 08:30 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek8Jun_CpiMomentumSellUsd_EUR_USD",
            sym -> new NewsWeek8Jun_CpiMomentumSellUsd.EurUsd()
        );

        // 1b — CPI Momentum SELL AUD/USD (best pair)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek8Jun_CpiMomentumSellUsd_AUD_USD",
            sym -> new NewsWeek8Jun_CpiMomentumSellUsd.AudUsd()
        );

        // 1c — CPI Momentum SELL NZD/USD
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek8Jun_CpiMomentumSellUsd_NZD_USD",
            sym -> new NewsWeek8Jun_CpiMomentumSellUsd.NzdUsd()
        );

        // 2 — ECB Dovish SELL EUR/USD (Thu Jun 11, 08:15 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek8Jun_EcbDovishSellEur",
            sym -> new NewsWeek8Jun_EcbDovishSellEur()
        );

        // 3 — BoJ Intervention SHORT USD/JPY (all week)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek8Jun_BojInterventionShortJpy",
            sym -> new NewsWeek8Jun_BojInterventionShortJpy()
        );

        // 4 — NZD Recovery Fade SELL NZD/USD (all week)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek8Jun_NzdRecoveryFadeSell",
            sym -> new NewsWeek8Jun_NzdRecoveryFadeSell()
        );
    }
}
