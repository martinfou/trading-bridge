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

    /**
     * Hand-computed daily Sharpe anchoring the first market-day close on {@code capital}:
     * returns are {@code (closes[0] − capital) / capital} then consecutive close-to-close.
     */
    static double handDailySharpe(double capital, List<Double> closes) {
        List<Double> returns = new ArrayList<>(closes.size());
        double prev = capital;
        for (double c : closes) {
            if (prev != 0.0) returns.add((c - prev) / prev);
            prev = c;
        }
        double rfDaily = PerformanceMetrics.DEFAULT_RISK_FREE_RATE / PerformanceMetrics.PERIODS_PER_YEAR;
        double mean = PerformanceMetrics.mean(returns) - rfDaily;
        double std = PerformanceMetrics.standardDeviation(returns);
        if (std == 0.0) return 0.0;
        return (mean / std) * Math.sqrt(PerformanceMetrics.PERIODS_PER_YEAR);
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
    void barsAreStampedAtPeriodStartSo16BarClosesDayAnd17BarOpensNext() {
        // Dukascopy bi5 / OANDA bars are stamped at the START of their period: the bar stamped
        // 16:00 NY spans 16:00–17:00 and is the LAST bar of the market day (its close is the
        // 17:00 rollover); the bar stamped 17:00 NY spans 17:00–18:00 and OPENS the next market
        // day. A close-stamped convention would be the exact opposite, which is why the review's
        // "Friday 17:00 → Saturday" concern does not apply to this data's timestamping.
        // Winter (EST = UTC-5): 16:00 NY = 21:00 UTC, 17:00 NY = 22:00 UTC. 2010-01-08 is Friday.
        assertEquals(LocalDate.of(2010, 1, 8),
            PerformanceMetrics.marketDay(Instant.parse("2010-01-08T21:00:00Z")), "Friday 16:00 NY closes Friday's market day");
        assertEquals(LocalDate.of(2010, 1, 9),
            PerformanceMetrics.marketDay(Instant.parse("2010-01-08T22:00:00Z")), "Friday 17:00 NY opens the next (Saturday) market day");
    }

    @Test
    void fridayReportBarDoesNotCreatePhantomSaturdayMarketDay() {
        // A Friday 17:00 NY report bar (stamped 22:00 UTC in winter EST) is a flat carry bar:
        // vol == 0 (open == close), equity unchanged. Without the weekend-window filter,
        // marketDay() files it under SATURDAY, injecting a phantom zero-return market day every
        // week. The filter must drop it so the series keeps only real market days.
        Instant mon  = Instant.parse("2024-12-30T21:00:00Z"); // Monday 16:00 NY
        Instant tue  = Instant.parse("2024-12-31T21:00:00Z");
        Instant wed  = Instant.parse("2025-01-01T21:00:00Z");
        Instant thu  = Instant.parse("2025-01-02T21:00:00Z");
        Instant fri  = Instant.parse("2025-01-03T21:00:00Z"); // Friday 16:00 NY — real close
        Instant friReport = Instant.parse("2025-01-03T22:00:00Z"); // Friday 17:00 NY — flat report bar
        Instant mon2 = Instant.parse("2025-01-06T21:00:00Z"); // next Monday

        // The report bar WOULD be filed under Saturday — this is the bug the filter removes.
        assertEquals(LocalDate.of(2025, 1, 4), PerformanceMetrics.marketDay(friReport),
            "Friday 17:00 NY must map to Saturday's market day (the phantom the filter drops)");

        List<PerformanceMetrics.EquityPoint> withReport = List.of(
            new PerformanceMetrics.EquityPoint(mon, 10_100.0),
            new PerformanceMetrics.EquityPoint(tue, 10_200.0),
            new PerformanceMetrics.EquityPoint(wed, 10_150.0),
            new PerformanceMetrics.EquityPoint(thu, 10_250.0),
            new PerformanceMetrics.EquityPoint(fri, 10_300.0),
            new PerformanceMetrics.EquityPoint(friReport, 10_300.0), // vol=0: flat close, no return
            new PerformanceMetrics.EquityPoint(mon2, 10_400.0));

        List<PerformanceMetrics.EquityPoint> withoutReport = List.of(
            new PerformanceMetrics.EquityPoint(mon, 10_100.0),
            new PerformanceMetrics.EquityPoint(tue, 10_200.0),
            new PerformanceMetrics.EquityPoint(wed, 10_150.0),
            new PerformanceMetrics.EquityPoint(thu, 10_250.0),
            new PerformanceMetrics.EquityPoint(fri, 10_300.0),
            new PerformanceMetrics.EquityPoint(mon2, 10_400.0));

        // Six retained market days (Mon..Fri + next Mon) — never a phantom Saturday.
        assertEquals(6, PerformanceMetrics.resampleDailyCloses(withReport).size(),
            "the Friday report bar must not create a 7th (Saturday) market day");
        assertEquals(List.of(10_100.0, 10_200.0, 10_150.0, 10_250.0, 10_300.0, 10_400.0),
            PerformanceMetrics.resampleDailyCloses(withReport));

        // The report bar is fully neutral: the daily Sharpe with and without it is identical.
        double withSharpe = PerformanceMetrics.dailySharpeRatio(withReport, 10_000.0);
        double withoutSharpe = PerformanceMetrics.dailySharpeRatio(withoutReport, 10_000.0);
        System.out.printf("[PHANTOM-SATURDAY] with report bar=%.9f  without=%.9f%n", withSharpe, withoutSharpe);
        assertEquals(withoutSharpe, withSharpe, 1e-12,
            "a flat Friday report bar must not change the daily Sharpe at all");
    }

    @Test
    void firstDayGainIsCapturedFromInitialCapital() {
        // All the P&L is earned on day 0 (10k → 11k); every later day is flat at 11k.
        List<Double> daily = new ArrayList<>();
        daily.add(11_000.0);
        for (int i = 1; i < 30; i++) daily.add(11_000.0);

        // The single-arg overload treats the first point as the starting balance, so a curve
        // that already begins at 11k has no prior close and the +10 % is dropped → 0.0.
        double withoutAnchor = PerformanceMetrics.dailySharpeRatio(dailyPoints(daily));
        System.out.printf("[FIRST-DAY-NO-ANCHOR] daily Sharpe=%.6f%n", withoutAnchor);
        assertEquals(0.0, withoutAnchor, 1e-12);

        // The capital-anchored overload computes the first return directly from 10k capital
        // → +10 %, giving a clearly positive Sharpe. This replaces the old synthetic seed.
        double viaCapital = PerformanceMetrics.dailySharpeRatio(dailyPoints(daily), 10_000.0);
        System.out.printf("[FIRST-DAY-VIA-CAPITAL] daily Sharpe=%.6f%n", viaCapital);
        assertTrue(viaCapital > 0.0, "the first day's gain must be captured via initialCapital, got " + viaCapital);
    }

    @Test
    void constantPositiveDailyReturnIsUnmeasurableAndReturnsZero() {
        // Doubling every (business) day → every daily return is exactly +100 %, std dev == 0,
        // mean > 0. A zero-variance series is unmeasurable: return 0.0 (which fails the ≥0.3
        // gate), never +Infinity — a non-finite value would survive to JSON/SQLite persistence
        // and pass any numeric gate. (flatEquityReturnsZero covers the mean == 0 case.)
        // Five points (Mon–Fri) span no weekend, so the weekend-drop does not collapse the
        // Fri→Mon span into one +700 % return and the series stays constant at +100 %/day.
        double s = PerformanceMetrics.dailySharpeRatio(dailyPoints(doublingSeries(5)));
        System.out.printf("[CONSTANT-POSITIVE] daily Sharpe=%.6f%n", s);
        assertEquals(0.0, s, 1e-12);
        assertTrue(Double.isFinite(s), "a zero-variance series must not return a non-finite Sharpe");
    }

    @Test
    void riskFreeRateIsSubtractedFromDailyReturns() {
        List<Double> daily = increasingSeries(60);
        List<PerformanceMetrics.EquityPoint> pts = dailyPoints(daily);
        double sharpe = PerformanceMetrics.dailySharpeRatio(pts);

        // Hand-recompute from the SAME resampled daily closes the metric uses (weekend bars are
        // now dropped, so the raw `daily` list — which spans 60 calendar days — is no longer the
        // resampled series). Raw close-to-close returns, then mean − rfDaily over the raw std dev
        // (std is unchanged by subtracting a constant).
        List<Double> closes = PerformanceMetrics.resampleDailyCloses(pts);
        List<Double> raw = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            raw.add((closes.get(i) - closes.get(i - 1)) / closes.get(i - 1));
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

    // ------------------------------------------------------------------ bootstrap (initialCapital anchor)

    @Test
    void capitalAnchorCreatesNoWeekendDayWhenFirstBarIsMonday() {
        // DAY0 is a Monday. The removed engine seed was firstBar − 1 day = Sunday, which
        // marketDay() would have filed as a phantom Sunday close. The initialCapital overload
        // does no timestamp arithmetic, so the daily series is exactly Mon..Fri anchored on the
        // capital — no weekend market day is ever fabricated.
        List<Double> closes = List.of(10_100.0, 10_200.0, 10_150.0, 10_250.0, 10_300.0); // Mon..Fri
        double actual = PerformanceMetrics.dailySharpeRatio(dailyPoints(closes), 10_000.0);
        System.out.printf("[MONDAY-FIRST] daily Sharpe=%.6f%n", actual);
        assertEquals(handDailySharpe(10_000.0, closes), actual, 1e-9);
    }

    @Test
    void dstAutumnFallBackDoesNotDropFirstDayGain() {
        // US fall-back 2021-11-07 is a 25-hour Sunday. The removed seed (firstBar − 24h) could
        // land inside that same 25-hour day and be overwritten, dropping the first day's P&L.
        // The initialCapital overload has no timestamp arithmetic, so the first day's gain is
        // always (firstClose − capital)/capital regardless of DST.
        Instant mon = Instant.parse("2021-11-08T15:00:00Z"); // Monday, just after the fall-back
        List<PerformanceMetrics.EquityPoint> pts = List.of(
            new PerformanceMetrics.EquityPoint(mon, 10_500.0),                         // Monday close: +5 %
            new PerformanceMetrics.EquityPoint(mon.plus(1, ChronoUnit.DAYS), 10_600.0), // Tuesday
            new PerformanceMetrics.EquityPoint(mon.plus(2, ChronoUnit.DAYS), 10_400.0)); // Wednesday
        double actual = PerformanceMetrics.dailySharpeRatio(pts, 10_000.0);
        System.out.printf("[DST-AUTUMN] daily Sharpe=%.6f%n", actual);
        assertEquals(handDailySharpe(10_000.0, List.of(10_500.0, 10_600.0, 10_400.0)), actual, 1e-9);
    }

    @Test
    void firstDayWithoutTransactionYieldsGenuineZeroReturn() {
        // Day 0 closes flat at the capital (no transaction). The capital anchor yields exactly
        // (10000 − 10000)/10000 = 0 for the first day — a genuine flat return, neither dropped
        // nor padded with an invented extra day.
        List<Double> closes = List.of(10_000.0, 10_500.0, 11_000.0);
        double actual = PerformanceMetrics.dailySharpeRatio(dailyPoints(closes), 10_000.0);
        System.out.printf("[FLAT-FIRST-DAY] daily Sharpe=%.6f%n", actual);
        assertEquals(handDailySharpe(10_000.0, closes), actual, 1e-9);
    }

    @Test
    void capitalOverloadRejectsDegenerateInputsAndNeverReturnsNonFinite() {
        assertEquals(0.0, PerformanceMetrics.dailySharpeRatio(null, 10_000.0), 1e-12);
        assertEquals(0.0, PerformanceMetrics.dailySharpeRatio(dailyPoints(List.of(10_500.0)), 0.0), 1e-12);
        assertEquals(0.0, PerformanceMetrics.dailySharpeRatio(dailyPoints(List.of(10_500.0)), -1.0), 1e-12);
        // A single flat day at the capital has one return (0) — fewer than 2 → 0.0, still finite.
        assertEquals(0.0, PerformanceMetrics.dailySharpeRatio(dailyPoints(List.of(10_000.0)), 10_000.0), 1e-12);
    }
}
