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

    public static final String CAPTURED_AT = "2026-05-31";

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

    public static final Profile CI_SUBSET = new Profile(
        744,
        4,
        0.0230460714285712,
        23.0460714285711800,
        0.0146887421305459);

    public static final Profile EUR_USD_2012 = new Profile(
        8760,
        68,
        0.2000730357142875,
        200.0730357142874700,
        0.0321957380977332);

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
