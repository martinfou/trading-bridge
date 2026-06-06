package com.martinfou.trading.strategies.newsweekly;

/** Registers all news/weekly strategies for the current week. */
public final class NewsWeeklyStrategyCatalogRegistrar {

    private NewsWeeklyStrategyCatalogRegistrar() {}

    public static void registerAll() {
        // === Week 8-12 June 2026 — News-driven strategies ===

        // 1a — CPI Momentum bidirectionnel AUD/USD (Très haute, 1.0%)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek8Jun_CpiMomentum_AUD_USD",
            sym -> new NewsWeek8Jun_CpiMomentumSellUsd.AudUsd()
        );

        // 1b — CPI Momentum bidirectionnel NZD/USD (Très haute, 1.0%)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek8Jun_CpiMomentum_NZD_USD",
            sym -> new NewsWeek8Jun_CpiMomentumSellUsd.NzdUsd()
        );

        // 1c — CPI Momentum bidirectionnel EUR/USD (Haute, 0.7%)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek8Jun_CpiMomentum_EUR_USD",
            sym -> new NewsWeek8Jun_CpiMomentumSellUsd.EurUsd()
        );

        // 2 — ECB Dovish SELL EUR/USD (Haute, 0.7%)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek8Jun_EcbDovishSellEur",
            sym -> new NewsWeek8Jun_EcbDovishSellEur()
        );

        // 3 — BoJ Intervention SHORT USD/JPY (Risquée, 0.3%)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek8Jun_BojInterventionShortJpy",
            sym -> new NewsWeek8Jun_BojInterventionShortJpy()
        );

        // 4 — NZD Recovery Fade SELL NZD/USD (Moyenne, 0.5%, trail 40)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek8Jun_NzdRecoveryFadeSell",
            sym -> new NewsWeek8Jun_NzdRecoveryFadeSell()
        );
    }
}
