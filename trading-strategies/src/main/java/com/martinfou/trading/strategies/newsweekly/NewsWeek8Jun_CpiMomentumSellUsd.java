package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * 🟢 Wk 8-12 Jun — CPI Momentum (bidirectionnel)
 *
 * Lit la 1ère barre après CPI (Wed Jun 10, 08:30 ET) pour déterminer la direction :
 *   Barre baissière (close < open) → CPI beat → SELL USD (AUD, NZD, EUR)
 *   Barre haussière (close > open) → CPI miss → BUY USD (mean reversion NFP rally)
 *
 * Sizing par risque : très haute 1.0%, haute 0.7%.
 * Capital: $1,000.
 *
 * This strategy is valid for the week of June 8-12, 2026 ONLY.
 */
public class NewsWeek8Jun_CpiMomentumSellUsd extends NewsWeeklyStrategy {

    /** AUD/USD — Très haute confiance (1.0% risque, SL 50, TP 80). */
    public static class AudUsd extends NewsWeek8Jun_CpiMomentumSellUsd {
        public AudUsd() { super("AUD_USD", 50, 80, 0.01); }
    }

    /** NZD/USD — Très haute confiance (1.0% risque, SL 50, TP 80). */
    public static class NzdUsd extends NewsWeek8Jun_CpiMomentumSellUsd {
        public NzdUsd() { super("NZD_USD", 50, 80, 0.01); }
    }

    /** EUR/USD — Haute confiance (0.7% risque, SL 50, TP 100). */
    public static class EurUsd extends NewsWeek8Jun_CpiMomentumSellUsd {
        public EurUsd() { super("EUR_USD", 50, 100, 0.007); }
    }

    protected NewsWeek8Jun_CpiMomentumSellUsd(String symbol, int slPips, int tpPips, double riskPct) {
        super(
            "NewsWeek8Jun_CpiMomentum_" + symbol,
            symbol,
            nyEvent(2026, 6, 10, 8, 30),   // Wed Jun 10, 08:30 ET — CPI release
            weekEndAfter(2026, 6, 12),
            slPips, tpPips,
            riskPct    // bidirectionnel
        );
    }
}
