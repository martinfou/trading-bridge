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

        // === Week 27-31 July 2026 — Macro News Strategies ===

        // 1 — FOMC Rate Decision (Wed Jul 29, 14:00 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek27Jul_FOMC_EUR_USD",
            sym -> new NewsWeek27Jul_FomcBoePce.FomcEurUsd());
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek27Jul_FOMC_USD_CAD",
            sym -> new NewsWeek27Jul_FomcBoePce.FomcUsdCad());
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek27Jul_FOMC_AUD_USD",
            sym -> new NewsWeek27Jul_FomcBoePce.FomcAudUsd());

        // 2 — BOE Rate Decision (Thu Jul 30, 07:00 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek27Jul_BOE_GBP_USD",
            sym -> new NewsWeek27Jul_FomcBoePce.BoeGbpUsd());
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek27Jul_BOE_GBP_JPY",
            sym -> new NewsWeek27Jul_FomcBoePce.BoeGbpJpy());

        // 3 — US Core PCE Inflation (Thu Jul 30, 08:30 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek27Jul_PCE_EUR_USD",
            sym -> new NewsWeek27Jul_FomcBoePce.PceEurUsd());
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek27Jul_PCE_GBP_USD",
            sym -> new NewsWeek27Jul_FomcBoePce.PceGbpUsd());

        // 4 — BOJ Policy Decision (Thu Jul 30, 23:00 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek27Jul_BOJ_USD_JPY",
            sym -> new NewsWeek27Jul_FomcBoePce.BojUsdJpy());

        // === Week 10-14 August 2026 — CPI, PPI & Retail Sales Strategies ===

        // 1 — CPI Inflation Rate (Wed Aug 12, 08:30 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek10Aug_CPI_EUR_USD",
            sym -> new NewsWeek10Aug_CpiRetailSales.CpiEurUsd());
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek10Aug_CPI_GBP_USD",
            sym -> new NewsWeek10Aug_CpiRetailSales.CpiGbpUsd());

        // 2 — PPI Producer Price Index (Thu Aug 13, 08:30 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek10Aug_PPI_USD_JPY",
            sym -> new NewsWeek10Aug_CpiRetailSales.PpiUsdJpy());

        // 3 — Retail Sales (Fri Aug 14, 08:30 ET)
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek10Aug_RetailSales_EUR_USD",
            sym -> new NewsWeek10Aug_CpiRetailSales.RetailSalesEurUsd());
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek10Aug_RetailSales_USD_CAD",
            sym -> new NewsWeek10Aug_CpiRetailSales.RetailSalesUsdCad());

        // === Week 12-16 October 2026 — US inflation week + Chinese CPI + Friday fade ===
        // Source: docs/news-playbooks/week-2026-10-12.md (Setups 1 to 4).
        // Setup 5 (Monday long-only reversion) is NOT coded: its activation needs the previous
        // Friday close AND the week close to be negative, a condition the strategy API cannot
        // evaluate, and it plays the following Monday.

        // 1 — US CPI (Wed Oct 14, 08:30 ET), bidirectional momentum
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek12Oct_Cpi_EUR_USD",
            sym -> new NewsWeek12Oct_CpiPpi.CpiEurUsd());

        // 2 — US PPI + Retail Sales (Thu Oct 15, 08:30 ET), bidirectional momentum
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek12Oct_PpiRetail_EUR_USD",
            sym -> new NewsWeek12Oct_CpiPpi.PpiRetailSalesEurUsd());

        // 3 — Chinese CPI (Tue Oct 13, 21:30 ET), bidirectional on AUD_JPY, flat Wed 16:00 ET
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek12Oct_ChinaCpi_AUD_JPY",
            sym -> new NewsWeek12Oct_ChinaCpi.ChinaCpiAudJpy());

        // 4 — Friday fade (Fri Oct 16, 16:00 ET close grid), fixed SELL GBP_CHF held over the weekend
        NewsWeeklyStrategyCatalog.register(
            "NewsWeek12Oct_FridayFade_GBP_CHF",
            sym -> new NewsWeek12Oct_FridayFade.FridayFadeGbpChf());
    }
}
