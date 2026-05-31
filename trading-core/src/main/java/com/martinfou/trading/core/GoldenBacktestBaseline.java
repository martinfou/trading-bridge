package com.martinfou.trading.core;

/**
 * Canonical golden backtest metrics for {@code LondonOpenRangeBreakout} on EUR/USD H1.
 * Re-captured via {@code GoldenBaselineCapture} — see {@code docs/testing.md}.
 */
public final class GoldenBacktestBaseline {

    public static final String STRATEGY_ID = "LondonOpenRangeBreakout";
    public static final String SYMBOL = "EUR_USD";
    public static final int FULL_YEAR = 2012;
    public static final double INITIAL_CAPITAL = 100_000.0;

    /** Relative tolerance on return and PnL (±1%). */
    public static final double RETURN_TOLERANCE_PCT = 0.01;

    /** Absolute tolerance on max drawdown percentage points. */
    public static final double MAX_DRAWDOWN_TOLERANCE_PCT = 0.01;

    /** When baselines were last re-captured (UTC). */
    public static final String CAPTURED_AT = "2026-05-31";

    public record Profile(
        int bars,
        int trades,
        double returnPct,
        double totalPnl,
        double maxDrawdownPct
    ) {}

    /** {@code data/ci/EUR_USD_H1_subset.csv} — January 2012 H1 (744 bars). */
    public static final Profile CI_SUBSET = new Profile(
        744,
        3,
        0.0137585714285704,
        13.758571428570399,
        0.0266378078515083);

    /** Full Dukascopy EUR/USD H1 2012 (8760 bars). */
    public static final Profile EUR_USD_2012 = new Profile(
        8760,
        61,
        0.1396741071428578,
        139.67410714285776,
        0.0475867243182637);

    private GoldenBacktestBaseline() {}

    static {
        verifyPnlConsistency(CI_SUBSET);
        verifyPnlConsistency(EUR_USD_2012);
    }

    private static void verifyPnlConsistency(Profile profile) {
        double implied = profile.returnPct() / 100.0 * INITIAL_CAPITAL;
        if (Math.abs(implied - profile.totalPnl()) > 0.01) {
            throw new ExceptionInInitializerError(
                "Golden profile inconsistent: return % implies PnL " + implied
                    + " but totalPnl is " + profile.totalPnl());
        }
    }
}
