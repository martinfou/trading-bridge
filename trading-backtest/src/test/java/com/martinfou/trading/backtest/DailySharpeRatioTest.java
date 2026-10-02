package com.martinfou.trading.backtest;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * Expands a daily series into hourly equity points (21 bars/day, 00:00–20:00 UTC,
     * same value within a day). The last bar is at 20:00 UTC (= 15:00 New York) so every
     * "day" stays inside a single 17:00-NY market day — a 23:00 UTC bar would spill into
     * the next market day and give H1 a trailing partial day that H4 lacks, breaking the
     * H1/H4 invariance property the test relies on.
     */
    static List<PerformanceMetrics.EquityPoint> toHourly(List<Double> daily) {
        List<PerformanceMetrics.EquityPoint> pts = new ArrayList<>();
        for (int d = 0; d < daily.size(); d++) {
            Instant dayStart = DAY0.plus(d, ChronoUnit.DAYS);
            for (int h = 0; h <= 20; h++) {
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

    /**
     * One point per day at 20:00 UTC (15:00 New York) — a single, boundary-safe bar so the
     * market-day close equals the daily value exactly (no intra-day expansion, no trailing
     * partial day). Used by tests that need a precise hand-computed expectation.
     */
    static List<PerformanceMetrics.EquityPoint> dailyPoints(List<Double> daily) {
        List<PerformanceMetrics.EquityPoint> pts = new ArrayList<>();
        for (int d = 0; d < daily.size(); d++) {
            pts.add(new PerformanceMetrics.EquityPoint(
                DAY0.plus(d, ChronoUnit.DAYS).plus(20, ChronoUnit.HOURS), daily.get(d)));
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

    /** Doubles every day → every daily return is exactly +100 % (std dev == 0). */
    static List<Double> doublingSeries(int n) {
        List<Double> d = new ArrayList<>();
        double v = 100.0;
        for (int i = 0; i < n; i++) {
            d.add(v);
            v *= 2.0;
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

    // ------------------------------------------------------------------ constat fixes

    @Test
    void marketDayBoundaryIsNewYork17AndDstSafe() {
        // January: EST = UTC-5, so 17:00 New York = 22:00 UTC. 16:59 → same day, 17:00 → next.
        assertEquals(LocalDate.of(2010, 1, 4),
            PerformanceMetrics.marketDay(Instant.parse("2010-01-04T21:59:00Z")));
        assertEquals(LocalDate.of(2010, 1, 5),
            PerformanceMetrics.marketDay(Instant.parse("2010-01-04T22:00:00Z")));

        // DST (EDT = UTC-4): 2021-03-14 17:00 New York = 21:00 UTC — boundary must still be 17:00 NY.
        assertEquals(LocalDate.of(2021, 3, 14),
            PerformanceMetrics.marketDay(Instant.parse("2021-03-14T20:59:00Z")));
        assertEquals(LocalDate.of(2021, 3, 15),
            PerformanceMetrics.marketDay(Instant.parse("2021-03-14T21:00:00Z")));
    }

    @Test
    void firstDayGainIsCapturedWhenInitialBalanceIsSeeded() {
        // All the P&L is earned on day 0 (10k → 11k); every later day is flat at 11k.
        List<Double> daily = new ArrayList<>();
        daily.add(11_000.0);
        for (int i = 1; i < 30; i++) daily.add(11_000.0);

        // Without a starting-balance point the first day has no prior close, its +10 % is
        // dropped, and the remaining returns are all 0 → Sharpe 0.0 (the bug).
        double withoutSeed = PerformanceMetrics.dailySharpeRatio(dailyPoints(daily));
        System.out.printf("[FIRST-DAY-NO-SEED]  daily Sharpe=%.6f%n", withoutSeed);
        assertEquals(0.0, withoutSeed, 1e-12);

        // With the starting balance seeded one day earlier (as BacktestEngine now does),
        // the first return is +10 % → a clearly positive Sharpe.
        List<PerformanceMetrics.EquityPoint> pts = new ArrayList<>();
        pts.add(new PerformanceMetrics.EquityPoint(
            DAY0.minus(1, ChronoUnit.DAYS).plus(20, ChronoUnit.HOURS), 10_000.0));
        pts.addAll(dailyPoints(daily));
        double withSeed = PerformanceMetrics.dailySharpeRatio(pts);
        System.out.printf("[FIRST-DAY-SEEDED]   daily Sharpe=%.6f%n", withSeed);
        assertTrue(withSeed > 0.0, "the first day's gain must be captured when the balance is seeded, got " + withSeed);
    }

    @Test
    void constantPositiveDailyReturnYieldsInfiniteSharpe() {
        // Doubling every day → every daily return is exactly +100 %, std dev == 0, mean > 0.
        // A zero-variance positive return IS an infinite Sharpe (the ≥0.3 gate must not reject it).
        double s = PerformanceMetrics.dailySharpeRatio(dailyPoints(doublingSeries(8)));
        System.out.printf("[CONSTANT-POSITIVE] daily Sharpe=%s%n", s);
        assertEquals(Double.POSITIVE_INFINITY, s);
        // (The mean <= 0 → 0.0 branch — including mean == 0 — is covered by flatEquityReturnsZero.)
    }

    @Test
    void riskFreeRateIsSubtractedFromDailyReturns() {
        List<Double> daily = increasingSeries(60);
        double sharpe = PerformanceMetrics.dailySharpeRatio(dailyPoints(daily));

        // Hand-recompute the same way: raw close-to-close returns, then mean − rfDaily over
        // the raw std dev (std is unchanged by subtracting a constant).
        List<Double> raw = new ArrayList<>();
        for (int i = 1; i < daily.size(); i++) {
            raw.add((daily.get(i) - daily.get(i - 1)) / daily.get(i - 1));
        }
        double rfDaily = PerformanceMetrics.DEFAULT_RISK_FREE_RATE / PerformanceMetrics.PERIODS_PER_YEAR;
        double expected = ((PerformanceMetrics.mean(raw) - rfDaily)
            / PerformanceMetrics.standardDeviation(raw)) * Math.sqrt(PerformanceMetrics.PERIODS_PER_YEAR);
        System.out.printf("[RF-SUBTRACTED] daily Sharpe=%.9f  hand-computed=%.9f%n", sharpe, expected);
        assertEquals(expected, sharpe, 1e-9);

        // Without the risk-free rate the mean (and thus the Sharpe) would be strictly higher.
        double noRf = (PerformanceMetrics.mean(raw)
            / PerformanceMetrics.standardDeviation(raw)) * Math.sqrt(PerformanceMetrics.PERIODS_PER_YEAR);
        assertTrue(noRf > sharpe, "subtracting the risk-free rate must lower the Sharpe: " + noRf + " vs " + sharpe);
    }
}
