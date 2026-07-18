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

        // 4 — NZD Recovery Fade SELL NZD/USD (Haute, 0.7%)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek8Jun_NzdRecoveryFadeSell",
            sym -> new NewsWeek8Jun_NzdRecoveryFadeSell()
        );

        // === Week 13-17 July 2026 — News-driven strategies ===

        // 1 — CPI/PPI Momentum Fade (Wed Jul 15, 08:30 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek13Jul_CpiFade_EUR_USD",
            sym -> new NewsWeek13Jul_CpiFade.EurUsd()
        );
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek13Jul_CpiFade_GBP_USD",
            sym -> new NewsWeek13Jul_CpiFade.GbpUsd()
        );

        // 2 — Fed Beige Book Pre-Trend (Wed Jul 15, 13:30 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek13Jul_BeigeBook_EUR_USD",
            sym -> new NewsWeek13Jul_BeigeBookTrend.EurUsd()
        );

        // 3 — UK CPI Momentum (Wed Jul 15, 02:00 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek13Jul_UkCpi_GBP_USD",
            sym -> new NewsWeek13Jul_UkCpiMomentum.GbpUsd()
        );

        // === Week 20-24 July 2026 — News-driven strategies ===

        // 1 — ECB Rate Decision (Thu Jul 23, 08:15 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek20Jul_Ecb_EUR_USD",
            sym -> new NewsWeek20Jul_EcbRateDecision.EurUsd()
        );

        // 2 — US GDP Advance Q2 (Thu Jul 23, 08:30 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek20Jul_UsGdp_EUR_USD",
            sym -> new NewsWeek20Jul_UsGdpAdvance.EurUsd()
        );
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek20Jul_UsGdp_GBP_USD",
            sym -> new NewsWeek20Jul_UsGdpAdvance.GbpUsd()
        );

        // 3 — US Durable Goods (Fri Jul 24, 08:30 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek20Jul_UsDurable_EUR_USD",
            sym -> new NewsWeek20Jul_UsDurableGoods.EurUsd()
        );
        // === Week 20-24 July 2026 — News-driven strategies ===

        // 1 — UK Unemployment Rate (Tue Jul 21, 02:00 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek20Jul_UK_Unemployment_GBP_USD",
            sym -> new NewsWeek20Jul_UkUnemployment.GbpUsd());
        // 2 — ECB Rate Decision (Thu Jul 23, 08:15 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek20Jul_ECB_Decision_EUR_USD",
            sym -> new NewsWeek20Jul_EcbDecision.EurUsd());
        // 3 — ECB Press Conference (Thu Jul 23, 08:45 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek20Jul_ECB_Presser_EUR_USD",
            sym -> new NewsWeek20Jul_EcbPresser.EurUsd());
    }
}
