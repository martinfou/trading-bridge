package com.martinfou.trading.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical golden backtest metrics for {@code LondonOpenRangeBreakout} on EUR/USD H1.
 * Re-captured via {@code GoldenBaselineCapture} — see {@code docs/testing.md}.
 *
 * <p>Uses {@link #INITIAL_CAPITAL} ($100k), not {@link LotSizing#DEFAULT_STARTING_CAPITAL},
 * so regressions are comparable across machines regardless of product UI defaults.</p>
 */
public final class GoldenBacktestBaseline {

    public static final String STRATEGY_ID = "LondonOpenRangeBreakout";
    public static final String SYMBOL = "EUR_USD";
    public static final int FULL_YEAR = 2012;

    /** Fixed capital for golden / promote-baseline comparison (not the TUI default). */
    public static final double INITIAL_CAPITAL = 100_000.0;

    /** Relative tolerance on return %, PnL, and final equity (±1%). */
    public static final double RELATIVE_METRIC_TOLERANCE = 0.01;

    /** @deprecated use {@link #RELATIVE_METRIC_TOLERANCE} */
    public static final double RETURN_TOLERANCE_PCT = RELATIVE_METRIC_TOLERANCE;

    /** Absolute tolerance on max drawdown percentage points. */
    public static final double MAX_DRAWDOWN_TOLERANCE_PCT = 0.01;

    public static final String CAPTURED_AT = "2026-10-15";

    public record Profile(
        int bars,
        int trades,
        double returnPct,
        double totalPnl,
        double maxDrawdownPct
    ) {}

    /** Observed metrics from a backtest run (decoupled from {@code trading-backtest}). */
    public record MetricSnapshot(
        int barCount,
        int trades,
        double returnPct,
        double totalPnl,
        double maxDrawdownPct,
        double finalEquity
    ) {}

    /**
     * Re-based 2026-09-30 when the fees/swaps realism model was merged: the previous values were
     * captured with optimistic costs. The engine now charges real commission, spread and swap, so
     * these numbers are the honest ones (EUR_USD 2012 went from +4.72% to +0.61%).
     *
     * <p>Re-captured again 2026-10-02, after the 2026-10-01 swap correction ({@code RealCostModel},
     * swap accounting). The shift is measured, not assumed: {@code ProveGoldenCostShift} replays both
     * windows and shows the trade set is <b>identical</b> (4 and 68 trades) — only the cost accounting
     * moved. It is a swap-only shift: the legacy table over-charged the swap ~4×, and re-running with
     * the corrected swap but the <b>legacy</b> execution cost reproduces these exact numbers
     * (6.9921 / 30.2550). The measured half-spread of {@code RealCostModel} is therefore <b>not</b>
     * part of what the golden guards: the default {@code RunContexts.backtest} path still passes
     * {@code BacktestExecutionCost.DEFAULT}. Recorded via {@code GoldenBaselineCapture}.
     *
     * <p><b>Re-captured 2026-10-15</b> — weekend rollover fix in {@code SwapCalculator}: the FX market is
     * closed on Saturday and Sunday, so the weekend financing is carried by the <b>Wednesday</b> triple
     * rollover (spot T+2: Friday's value date rolls to Monday = 3 days). The old counter billed 9 nights
     * for a 7-night week. Shift measured with the A/B probe {@code ProveSwapWeekendShift} (the same source
     * run in the pre-fix tree and in the fixed tree) then re-read by {@code GoldenBaselineCapture}:
     * the trade set is <b>identical</b> (4 and 68 trades, byte-identical per-trade signatures) and the
     * entire move is the swap line, to the cent —
     * CI subset: PnL 6.9921 → 7.2961 (+0.3040), swap −0.6840 → −0.3800 (+0.3040), 4 nights removed;
     * EUR_USD 2012: PnL 30.2550 → 31.8550 (+1.6000), swap −8.0880 → −6.4880 (+1.6000), 32 nights removed.
     * The pre-fix tree reproduces the previous constants <b>exactly</b> (6.9921 / 30.2550), which is what
     * makes this a re-capture rather than a blessed regression.
     */
    public static final Profile CI_SUBSET = new Profile(
        744, 4, 0.0072960714285707, 7.2960714285706880, 0.0148429991244108);

    public static final Profile EUR_USD_2012 = new Profile(
        8760, 68, 0.0318550357142771, 31.8550357142770720, 0.0640064238935006);

    private GoldenBacktestBaseline() {}

    static {
        verifyPnlConsistency(CI_SUBSET);
        verifyPnlConsistency(EUR_USD_2012);
    }

    /**
     * Compares actual metrics to a golden profile. Returns human-readable mismatch lines (empty if OK).
     */
    public static List<String> metricMismatches(MetricSnapshot actual, Profile expected, double initialCapital) {
        List<String> errors = new ArrayList<>();
        if (actual.barCount() != expected.bars()) {
            errors.add("bar count: expected " + expected.bars() + " but was " + actual.barCount());
        }
        if (actual.trades() != expected.trades()) {
            errors.add("trade count: expected " + expected.trades() + " but was " + actual.trades());
        }
        if (!withinRelativeTolerance(actual.returnPct(), expected.returnPct(), RELATIVE_METRIC_TOLERANCE)) {
            errors.add(formatRelative("return %", actual.returnPct(), expected.returnPct()));
        }
        if (!amountWithinRelativeTolerance(actual.totalPnl(), expected.totalPnl(), RELATIVE_METRIC_TOLERANCE)) {
            errors.add(formatRelative("total PnL", actual.totalPnl(), expected.totalPnl()));
        }
        if (!amountWithinRelativeTolerance(
            actual.maxDrawdownPct(), expected.maxDrawdownPct(), MAX_DRAWDOWN_TOLERANCE_PCT)) {
            errors.add(formatAbsolute("max drawdown %", actual.maxDrawdownPct(), expected.maxDrawdownPct(),
                MAX_DRAWDOWN_TOLERANCE_PCT));
        }
        double expectedFinal = initialCapital + expected.totalPnl();
        if (!amountWithinRelativeTolerance(actual.finalEquity(), expectedFinal, RELATIVE_METRIC_TOLERANCE)) {
            errors.add(formatRelative("final equity", actual.finalEquity(), expectedFinal));
        }
        double impliedReturn = initialCapital > 0
            ? (actual.totalPnl() / initialCapital) * 100.0
            : 0.0;
        if (!amountWithinRelativeTolerance(actual.returnPct(), impliedReturn, RELATIVE_METRIC_TOLERANCE)) {
            errors.add("return % inconsistent with total PnL: implied " + impliedReturn + "% vs reported "
                + actual.returnPct() + "%");
        }
        return List.copyOf(errors);
    }

    public static boolean withinRelativeTolerance(double actual, double expected, double relativeTolerance) {
        if (expected == 0.0) {
            return Math.abs(actual) <= relativeTolerance;
        }
        double min = expected * (1.0 - relativeTolerance);
        double max = expected * (1.0 + relativeTolerance);
        return actual >= min && actual <= max;
    }

    public static boolean amountWithinRelativeTolerance(double actual, double expected, double relativeTolerance) {
        if (expected == 0.0) {
            return Math.abs(actual) <= relativeTolerance;
        }
        return Math.abs(actual - expected) <= Math.abs(expected) * relativeTolerance;
    }

    private static String formatRelative(String label, double actual, double expected) {
        double band = Math.abs(expected) * RELATIVE_METRIC_TOLERANCE;
        return label + ": expected " + expected + " (±" + band + ") but was " + actual;
    }

    private static String formatAbsolute(String label, double actual, double expected, double tolerance) {
        return label + ": expected " + expected + " (±" + tolerance + " pp) but was " + actual;
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
