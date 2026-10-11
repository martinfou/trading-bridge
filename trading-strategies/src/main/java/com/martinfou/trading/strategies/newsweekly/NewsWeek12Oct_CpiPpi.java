package com.martinfou.trading.strategies.newsweekly;

/**
 * US inflation week strategies — week of October 12-16, 2026.
 *
 * <p>Source: {@code docs/news-playbooks/week-2026-10-12.md} (Setups 1 and 2).
 *
 * <p><b>What the code can and cannot reproduce from the playbook.</b> The playbook writes the CPI
 * trade as three branches keyed on the printed number (A = miss below 3.5% → long EUR/USD,
 * B = hawkish at 3.8% or more → short EUR/USD, C = in line → no trade). A bar-driven strategy
 * cannot read the release, so both setups are coded BIDIRECTIONAL: the first bar at or after the
 * event is read for direction and the position follows it. That is branches A and B decided by the
 * market's own read of the number instead of by the forecast. Branch C has no representation: an
 * in-line print produces a directionless bar and the trade enters anyway, which is the one place
 * this code is strictly looser than the playbook. Same caveat for Setup 2's rule that PPI and
 * retail sales must agree: the bidirectional bar is the market's aggregate read, not the two
 * prints compared field by field.
 *
 * <p>The playbook's portfolio rules (max 2 open positions, one inflation setup per week, $550
 * cumulative risk) are NOT enforceable here: each strategy is an independent order source.
 */
public class NewsWeek12Oct_CpiPpi {

    /**
     * CPI (Wed Oct 14, 08:30 ET) — bidirectional momentum on EUR_USD, flat the same day.
     *
     * <p>The playbook's rule is an entry between 08:45 and 09:30 ET, then "sortie obligatoire à
     * 16:00 ET le jour même": the exit is the same-day grid, NOT the week-end. A late start after
     * 16:00 ET is inert (the week-end ceiling in the waiting phase closes the door).
     *
     * <p>SL/TP rationale:
     * <ul>
     *   <li>SL 45 — a post-publication reversal against a mis-forecast print costs 45 pips before
     *       the thesis is falsified (playbook Setup 1).</li>
     *   <li>TP 68 — 1.5 R, the playbook's target for the CPI leg.</li>
     *   <li>Risk 0.5% — $250 on the playbook's $50,000 account, kept as a percentage so the same
     *       thesis sizes correctly on any capital.</li>
     * </ul>
     */
    public static class CpiEurUsd extends NewsWeeklyStrategy {
        public CpiEurUsd() {
            super("CpiEurUsd", "EUR_USD",
                nyEvent(2026, 10, 14, 8, 30),       // CPI release
                nyEvent(2026, 10, 14, 16, 0),       // mandatory flat, same day 16:00 ET
                45,   // SL 45 — reversal against a mis-forecast CPI print
                68,   // TP 68 — 1.5 R (playbook Setup 1)
                0.005);
        }
    }

    /**
     * PPI + Retail Sales (Thu Oct 15, 08:30 ET) — bidirectional momentum on EUR_USD, flat the same day.
     *
     * <p>Same same-day exit as the CPI leg: the playbook does not carry either inflation setup into
     * the following session.
     *
     * <p>SL/TP rationale:
     * <ul>
     *   <li>SL 40 — second inflation measure in 48h, smaller expected excursion than the CPI.</li>
     *   <li>TP 60 — 1.5 R.</li>
     *   <li>Risk 0.3% — $150 on the playbook's $50,000 account: the playbook deliberately halves
     *       this setup, and the reduced risk is the only part of that intent the engine can hold.</li>
     * </ul>
     */
    public static class PpiRetailSalesEurUsd extends NewsWeeklyStrategy {
        public PpiRetailSalesEurUsd() {
            super("PpiRetailSalesEurUsd", "EUR_USD",
                nyEvent(2026, 10, 15, 8, 30),       // PPI + retail sales release
                nyEvent(2026, 10, 15, 16, 0),       // mandatory flat, same day 16:00 ET
                40,   // SL 40 — smaller excursion expected on the second inflation print
                60,   // TP 60 — 1.5 R
                0.003);
        }
    }

    private NewsWeek12Oct_CpiPpi() {}
}
