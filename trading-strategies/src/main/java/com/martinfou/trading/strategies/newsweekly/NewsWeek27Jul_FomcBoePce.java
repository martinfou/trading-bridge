package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟢 Wk 27-31 Jul — FOMC + BOE + PCE + Month-End
 *
 * Trois événements majeurs cette semaine :
 *
 * Setup 1 — FOMC (Wed Jul 29, 14:00 ET)
 *   Directionnel SELL USD. CPI core sticky à 3.4%, NFP beat (+224K).
 *   FOMC hawkish probable → USD bullish. Vendre AUD, NZD, EUR.
 *
 * Setup 2 — BOE (Thu Jul 30, 07:00 ET)
 *   Directionnel BUY GBP. BOE maintient mais ton hawkish sur inflation UK.
 *
 * Setup 3 — PCE Core (Thu Jul 30, 08:30 ET)
 *   Bidirectionnel. Lire la 1ère barre après release pour direction.
 *
 * Capital: $1,000 par setup. Max 0.7-1.0% risque par trade.
 */
public class NewsWeek27Jul_FomcBoePce extends NewsWeeklyStrategy {

    // === SETUP 1: FOMC — SELL USD ===
    public static class FomcAudUsd extends NewsWeek27Jul_FomcBoePce {
        public FomcAudUsd() { super("Fomc_AUD_USD", "AUD_USD",
            nyEvent(2026, 7, 29, 14, 0), weekEndAfter(2026, 7, 31),
            60, 90, Order.Side.SELL, 0.01); }
    }
    public static class FomcNzdUsd extends NewsWeek27Jul_FomcBoePce {
        public FomcNzdUsd() { super("Fomc_NZD_USD", "NZD_USD",
            nyEvent(2026, 7, 29, 14, 0), weekEndAfter(2026, 7, 31),
            60, 90, Order.Side.SELL, 0.01); }
    }
    public static class FomcEurUsd extends NewsWeek27Jul_FomcBoePce {
        public FomcEurUsd() { super("Fomc_EUR_USD", "EUR_USD",
            nyEvent(2026, 7, 29, 14, 0), weekEndAfter(2026, 7, 31),
            50, 80, Order.Side.SELL, 0.007); }
    }
    public static class FomcUsdCad extends NewsWeek27Jul_FomcBoePce {
        public FomcUsdCad() { super("Fomc_USD_CAD", "USD_CAD",
            nyEvent(2026, 7, 29, 14, 0), weekEndAfter(2026, 7, 31),
            50, 80, Order.Side.BUY, 0.007); }
    }

    // === SETUP 2: BOE — BUY GBP ===
    public static class BoeGbpUsd extends NewsWeek27Jul_FomcBoePce {
        public BoeGbpUsd() { super("Boe_GBP_USD", "GBP_USD",
            nyEvent(2026, 7, 30, 7, 0), weekEndAfter(2026, 7, 31),
            60, 100, Order.Side.BUY, 0.01); }
    }
    public static class BoeGbpJpy extends NewsWeek27Jul_FomcBoePce {
        public BoeGbpJpy() { super("Boe_GBP_JPY", "GBP_JPY",
            nyEvent(2026, 7, 30, 7, 0), weekEndAfter(2026, 7, 31),
            60, 100, Order.Side.BUY, 0.007); }
    }

    // === SETUP 3: PCE Core — Bidirectionnel ===
    public static class PceEurUsd extends NewsWeek27Jul_FomcBoePce {
        public PceEurUsd() { super("Pce_EUR_USD", "EUR_USD",
            nyEvent(2026, 7, 30, 8, 30), weekEndAfter(2026, 7, 31),
            40, 70, 0.007); }
    }
    public static class PceGbpUsd extends NewsWeek27Jul_FomcBoePce {
        public PceGbpUsd() { super("Pce_GBP_USD", "GBP_USD",
            nyEvent(2026, 7, 30, 8, 30), weekEndAfter(2026, 7, 31),
            40, 70, 0.007); }
    }

    protected NewsWeek27Jul_FomcBoePce(String name, String symbol,
                                       java.time.Instant eventTime, java.time.Instant endTime,
                                       int slPips, int tpPips,
                                       Order.Side side, double riskPct) {
        super(name, symbol, eventTime, endTime, slPips, tpPips, side, riskPct);
    }

    protected NewsWeek27Jul_FomcBoePce(String name, String symbol,
                                       java.time.Instant eventTime, java.time.Instant endTime,
                                       int slPips, int tpPips, double riskPct) {
        super(name, symbol, eventTime, endTime, slPips, tpPips, riskPct);
    }
}
