package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * Friday risk-premium fade — week of October 12-16, 2026 (playbook Setup 4).
 *
 * <p>Source: {@code docs/news-playbooks/week-2026-10-12.md}. This is the one setup of the week that
 * is backtested rather than discretionary: the weekend risk premium in FX, ordered by cross, with
 * the refuge leg carrying the edge.
 *
 * <p><b>One deliberate deviation from the playbook.</b> Setup 4 says "Stop: aucun — la sortie est
 * temporelle, la protection est la taille." That cannot ship on the live path: the entry guard
 * refuses any non-close-only entry whose order carries no protective stop, so a real stop is
 * attached and kept WIDE (150 pips) so it acts as an emergency backstop rather than as the exit.
 * The exit that matters is still the temporal one at the Monday grid.
 *
 * <p>Sizing deviation: the playbook sizes by fixed units (10,000 units, doubled on a high
 * volatility regime). The engine sizes from the risk budget, so 0.5% over a 150-pip backstop
 * produces a few hundred units on a $1,000 nominal capital. The 2x high-volatility rule is not
 * implemented: it depends on a 21-day volatility rank the strategy cannot see.
 */
public class NewsWeek12Oct_FridayFade {

    /**
     * Friday fade (Fri Oct 16, 16:00 ET close grid) — fixed SELL GBP_CHF held over the weekend,
     * flat at the Monday Oct 19 open grid.
     *
     * <p>SL/TP rationale:
     * <ul>
     *   <li>SL 150 — NOT the playbook's thesis (it has no stop): an emergency backstop, wide enough
     *       to leave the temporal exit in charge, narrow enough that the venue holds a real
     *       protection behind the position.</li>
     *   <li>TP 0 — no target: the exit is the Monday grid, never a level.</li>
     *   <li>Risk 0.5% — $250 on the playbook's $50,000 account.</li>
     * </ul>
     */
    public static class FridayFadeGbpChf extends NewsWeeklyStrategy {
        public FridayFadeGbpChf() {
            super("FridayFadeGbpChf", "GBP_CHF",
                nyEvent(2026, 10, 16, 16, 0),       // Friday close grid, 20:00 UTC
                weekEndAfter(2026, 10, 16),         // flat at the Monday grid
                150,  // SL 150 — emergency backstop only, the exit is temporal
                0,    // TP 0 — no target
                Order.Side.SELL,
                0.005);
        }
    }

    private NewsWeek12Oct_FridayFade() {}
}
