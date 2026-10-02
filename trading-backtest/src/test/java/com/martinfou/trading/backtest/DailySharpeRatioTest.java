package com.martinfou.trading.backtest;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves that the gate Sharpe is now a <em>daily</em> Sharpe (equity curve resampled to
 * daily closes, annualised by √252) and no longer a per-bar H1 annualisation.
 *
 * <p>The central property is <b>granularity invariance</b>: the same underlying equity
 * curve sampled at H1 and at H4 must yield the <em>same</em> daily Sharpe, whereas the
 * legacy per-bar calculation differs between the two granularities (that is the bug).</p>
 */
class DailySharpeRatioTest {

    private static final Instant DAY0 = Instant.parse("2010-01-04T00:00:00Z"); // a Monday

    // ------------------------------------------------------------------ sampling

    /** Expands a daily series into hourly equity points (24 bars/day, same value within a day). */
    static List<PerformanceMetrics.EquityPoint> toHourly(List<Double> daily) {
        List<PerformanceMetrics.EquityPoint> pts = new ArrayList<>();
        for (int d = 0; d < daily.size(); d++) {
            Instant dayStart = DAY0.plus(d, ChronoUnit.DAYS);
            for (int h = 0; h < 24; h++) {
                pts.add(new PerformanceMetrics.EquityPoint(dayStart.plus(h, ChronoUnit.HOURS), daily.get(d)));
            }
        }
        return pts;
    }

    /** Expands a daily series into 4-hourly equity points (6 bars/day, same value within a day). */
    static List<PerformanceMetrics.EquityPoint> toFourHourly(List<Double> daily) {
        List<PerformanceMetrics.EquityPoint> pts = new ArrayList<>();
        for (int d = 0; d < daily.size(); d++) {
            Instant dayStart = DAY0.plus(d, ChronoUnit.DAYS);
            for (int h = 0; h < 24; h += 4) {
                pts.add(new PerformanceMetrics.EquityPoint(dayStart.plus(h, ChronoUnit.HOURS), daily.get(d)));
            }
        }
        return pts;
    }

    // ------------------------------------------------------------------ fixtures

    /** Deterministic daily series with drift + sinusoidal variation (std dev > 0). */
    static List<Double> realisticDailySeries(int n) {
        List<Double> d = new ArrayList<>();
        double v = 10_000.0;
        for (int i = 0; i < n; i++) {
            d.add(v);
            double r = 0.0004 + Math.sin(i * 1.7) * 0.0015;
            v *= (1.0 + r);
        }
        return d;
    }

    /** Strictly increasing daily equity with varying positive increments. */
    static List<Double> increasingSeries(int n) {
        List<Double> d = new ArrayList<>();
        double v = 10_000.0;
        for (int i = 0; i < n; i++) {
            d.add(v);
            v += 10.0 + (i % 7) * 3.0;
        }
        return d;
    }

    /** Flat daily equity (no movement at all). */
    static List<Double> flatSeries(int n) {
        List<Double> d = new ArrayList<>();
        for (int i = 0; i < n; i++) d.add(10_000.0);
        return d;
    }

    /** Rises +0.5 %/day for {@code rise} days, then −1.5 %/day for {@code fall} days. */
    static List<Double> riseThenCollapse(int rise, int fall) {
        List<Double> d = new ArrayList<>();
        double v = 10_000.0;
        for (int i = 0; i < rise; i++) { d.add(v); v *= 1.005; }
        for (int i = 0; i < fall; i++) { d.add(v); v *= 0.985; }
        return d;
    }

    /** Per-bar floating equity: varies at every hour (like the engine's floating P&L curve). */
    static List<PerformanceMetrics.EquityPoint> floatingHourlyEquity(int days) {
        List<PerformanceMetrics.EquityPoint> pts = new ArrayList<>();
        double v = 10_000.0;
        long i = 0;
        for (int d = 0; d < days; d++) {
            Instant dayStart = DAY0.plus(d, ChronoUnit.DAYS);
            for (int h = 0; h < 24; h++) {
                // iid-ish per-bar noise (golden-ratio conjugate kills autocorrelation)
                double r = 0.0003 + 0.002 * Math.sin(i++ * 2.399963229728653);
                v *= (1.0 + r);
                pts.add(new PerformanceMetrics.EquityPoint(dayStart.plus(h, ChronoUnit.HOURS), v));
            }
        }
        return pts;
    }

    /** Consecutive % returns of a per-bar equity point list (for the legacy calc). */
    static List<Double> periodReturns(List<PerformanceMetrics.EquityPoint> pts) {
        List<Double> r = new ArrayList<>(pts.size() - 1);
        for (int i = 1; i < pts.size(); i++) {
            double prev = pts.get(i - 1).equity();
            if (prev != 0.0) r.add((pts.get(i).equity() - prev) / prev);
        }
        return r;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void h1AndH4SamplingYieldIdenticalDailySharpe() {
        List<Double> daily = realisticDailySeries(90);
        double s1 = PerformanceMetrics.dailySharpeRatio(toHourly(daily));
        double s4 = PerformanceMetrics.dailySharpeRatio(toFourHourly(daily));
        System.out.printf("[INVARIANCE] daily Sharpe H1=%.6f  H4=%.6f  (bars: %d vs %d)%n",
            s1, s4, toHourly(daily).size(), toFourHourly(daily).size());
        assertEquals(s1, s4, 1e-9, "same equity at two granularities must give the same daily Sharpe");
    }

    @Test
    void legacyPerBarSharpeIsGranularityDependent() {
        // A floating per-bar curve (intraday noise), sampled H1 vs H4 (every 4th bar).
        // The legacy calc annualises by √(bars/year), so H1 (√6240) ≠ H4 (√1560) by ~√4 = 2×.
        List<PerformanceMetrics.EquityPoint> h1 = floatingHourlyEquity(120);
        List<PerformanceMetrics.EquityPoint> h4 = new ArrayList<>();
        for (int i = 0; i < h1.size(); i += 4) h4.add(h1.get(i));
        double legacyH1 = PerformanceMetrics.perBarSharpeRatioLegacy(
            periodReturns(h1), PerformanceMetrics.DEFAULT_RISK_FREE_RATE, 6240.0);
        double legacyH4 = PerformanceMetrics.perBarSharpeRatioLegacy(
            periodReturns(h4), PerformanceMetrics.DEFAULT_RISK_FREE_RATE, 1560.0);
        System.out.printf("[LEGACY-IS-BROKEN] per-bar Sharpe H1(√6240)=%.4f  H4(√1560)=%.4f  (ratio %.2f)%n",
            legacyH1, legacyH4, legacyH4 == 0 ? Double.NaN : legacyH1 / legacyH4);
        assertTrue(Math.abs(legacyH1 - legacyH4) > 1.0,
            "legacy per-bar Sharpe must be granularity-dependent (this is the bug): "
                + legacyH1 + " vs " + legacyH4);
    }

    @Test
    void strictlyIncreasingEquityHasStronglyPositiveSharpe() {
        double s = PerformanceMetrics.dailySharpeRatio(toHourly(increasingSeries(90)));
        System.out.printf("[INCREASING] daily Sharpe=%.6f%n", s);
        assertTrue(s > 0.3, "strictly increasing equity should give daily Sharpe > 0.3, got " + s);
    }

    @Test
    void flatEquityReturnsZero() {
        double s = PerformanceMetrics.dailySharpeRatio(toHourly(flatSeries(30)));
        System.out.printf("[FLAT] daily Sharpe=%.6f%n", s);
        assertEquals(0.0, s, 1e-12);
    }

    @Test
    void riseThenCollapseIsNegative() {
        double s = PerformanceMetrics.dailySharpeRatio(toHourly(riseThenCollapse(30, 30)));
        System.out.printf("[COLLAPSE] daily Sharpe=%.6f%n", s);
        assertTrue(s < 0.0, "rise-then-collapse equity should give negative daily Sharpe, got " + s);
    }

    @Test
    void fewerThanTwoDaysReturnsZero() {
        assertEquals(0.0, PerformanceMetrics.dailySharpeRatio(toHourly(List.of(10_000.0))), 1e-12);
        assertEquals(0.0, PerformanceMetrics.dailySharpeRatio(null), 1e-12);
    }

    @Test
    void flatDayStillCountsAsZeroReturnDay() {
        // 3 days: day0 10000, day1 10000 (flat, no trade), day2 11000.
        // Daily returns = [0.0, +0.10] → a non-zero Sharpe; the flat day is NOT dropped.
        double s = PerformanceMetrics.dailySharpeRatio(toHourly(List.of(10_000.0, 10_000.0, 11_000.0)));
        System.out.printf("[FLAT-DAY-COUNTS] daily Sharpe=%.6f (flat day is a 0%%-return day, not skipped)%n", s);
        assertTrue(s > 0.0, "a flat day must remain a 0%-return day, producing a positive Sharpe here");
    }
}
