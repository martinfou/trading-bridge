package com.martinfou.trading.strategies.newsweekly;

/**
 * China CPI strategy — week of October 12-16, 2026 (playbook Setup 3).
 *
 * <p>Source: {@code docs/news-playbooks/week-2026-10-12.md}.
 *
 * <p>The Chinese CPI prints Tuesday Oct 13 at 21:30 ET (Wednesday morning in Beijing) and AUD is
 * the currency most exposed to it, so the trade is taken on AUD/JPY to isolate the Aussie from a
 * dollar that is already mobilised by the US CPI. Direction is read from the first bar after the
 * release (bidirectional), and the exit is TEMPORAL, not a target: Wednesday Oct 14 at 16:00 ET,
 * because Thursday is a US PPI day and the position is not carried into it.
 *
 * <p>Playbook Setup 3 also requires a confirmation against the pre-release high/low; the strategy
 * API exposes bars only, so that filter is not reproduced.
 */
public class NewsWeek12Oct_ChinaCpi {

    /**
     * Chinese CPI (Tue Oct 13, 21:30 ET) — bidirectional momentum on AUD_JPY, flat Wednesday
     * 16:00 ET.
     *
     * <p>SL/TP rationale:
     * <ul>
     *   <li>SL 50 — 50 pips beyond the reaction extreme (playbook Setup 3); on a JPY cross this is
     *       50 x 0.01 = 0.50 yen, which is what {@code pipValue} now returns for any *_JPY
     * pair.</li>
     *   <li>TP 75 — 1.5 R, the playbook's target.</li>
     *   <li>Risk 0.3% — $150 on the playbook's $50,000 account.</li>
     * </ul>
     *
     * <p>Sizing note: 0.3% of the engine's $1,000 nominal capital over a 50-pip stop on a JPY cross
     * gives 6 units, so the 100-unit floor sets the size, not the risk budget.
     */
    public static class ChinaCpiAudJpy extends NewsWeeklyStrategy {
        public ChinaCpiAudJpy() {
            super("ChinaCpiAudJpy", "AUD_JPY",
                nyEvent(2026, 10, 13, 21, 30),      // Chinese CPI, Tuesday night ET
                nyEvent(2026, 10, 14, 16, 0),       // mandatory flat Wednesday 16:00 ET
                50,   // SL 50 — beyond the reaction extreme (playbook Setup 3)
                75,   // TP 75 — 1.5 R
                0.003);
        }
    }

    private NewsWeek12Oct_ChinaCpi() {}
}
