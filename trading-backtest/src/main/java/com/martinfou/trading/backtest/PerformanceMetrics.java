package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Bar;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Static utility methods for computing advanced performance metrics
 * from equity curves and trade lists.
 *
 * <p>All methods return {@code double} values. Where a metric is undefined
 * (e.g. zero trades, zero variance), the result is 0.0 or {@code Double.NaN}
 * as documented in each method.</p>
 *
 * <h3>Metrics computed:</h3>
 * <ul>
 *   <li>{@link #sharpeRatio(List, double) Sharpe Ratio} — risk-adjusted return</li>
 *   <li>{@link #sortinoRatio(List, double) Sortino Ratio} — downside risk only</li>
 *   <li>{@link #profitFactor(List) Profit Factor} — gross profit / gross loss</li>
 *   <li>{@link #calmarRatio(double, double) Calmar Ratio} — annualised return / max drawdown</li>
 *   <li>{@link #averageTrade(List) Average Trade} — mean P&amp;L per trade</li>
 *   <li>{@link #annualisedReturn(List) Annualised Return} — compounded yearly return</li>
 * </ul>
 */
public final class PerformanceMetrics {

    /** Risk-free rate used unless explicitly overridden (2.5 % p.a.). */
    public static final double DEFAULT_RISK_FREE_RATE = 0.025;

    /**
     * Number of trading periods assumed per year (daily bars for forex).
     *
     * <p><b>Limitation:</b> 252 is the standard for 5-day FX / metal markets. It is
     * <em>not</em> correct for 24/7 markets (crypto, where 365 applies). This codebase
     * backtests forex and gold only, so 252 is right; a crypto backtest would need a
     * per-market annualisation factor, which is deliberately out of scope here.</p>
     */
    public static final double PERIODS_PER_YEAR = 252.0;

    /** Seconds in one calendar day (used for timeframe detection). */
    static final long SECONDS_PER_DAY = 86_400L;

    /** Default gap for H1 forex bars during weekdays (1 hour in seconds). */
    static final long H1_GAP_SEC = 3_600L;

    /** Time zone whose 17:00 close defines the FX market-day boundary. */
    public static final ZoneId MARKET_DAY_ZONE = ZoneId.of("America/New_York");

    /** New York wall-clock hour at which the FX trading day rolls over. */
    public static final LocalTime MARKET_DAY_CLOSE = LocalTime.of(17, 0);

    private PerformanceMetrics() {}

    // ---------------------------------------------------------------
    //  Sharpe Ratio
    // ---------------------------------------------------------------

    /**
     * Annualised Sharpe Ratio with explicit periods-per-year.
     *
     * @param periodReturns   list of period-to-period returns (decimal, e.g. 0.01 = 1 %)
     * @param riskFreeRate    annual risk-free rate as decimal (e.g. 0.025)
     * @param periodsPerYear  number of periods per year (e.g. 252 for daily, 1638 for H1 forex)
     * @return Sharpe Ratio, or 0.0 if fewer than 2 returns or zero standard deviation
     */
    public static double sharpeRatio(List<Double> periodReturns, double riskFreeRate, double periodsPerYear) {
        if (periodReturns == null || periodReturns.size() < 2) return 0.0;
        double rfPeriod = riskFreeRate / periodsPerYear;
        double mean = mean(periodReturns) - rfPeriod;
        double std = standardDeviation(periodReturns);
        if (std == 0.0) return 0.0;
        return (mean / std) * Math.sqrt(periodsPerYear);
    }

    /**
     * Annualised Sharpe Ratio.
     *
     * <p>\\[ Sharpe = \\frac{E[R_p - R_f]}{\\sigma_p} \\times \\sqrt{periodsPerYear} \\]</p>
     *
     * @param periodReturns  list of period-to-period returns (decimal, e.g. 0.01 = 1 %)
     * @param riskFreeRate   annual risk-free rate as decimal (e.g. 0.025)
     * @return Sharpe Ratio, or 0.0 if fewer than 2 returns or zero standard deviation
     */
    public static double sharpeRatio(List<Double> periodReturns, double riskFreeRate) {
        if (periodReturns == null || periodReturns.size() < 2) return 0.0;
        double rfPeriod = riskFreeRate / PERIODS_PER_YEAR;
        double mean = mean(periodReturns) - rfPeriod;
        double std = standardDeviation(periodReturns);
        if (std == 0.0) return 0.0;
        return (mean / std) * Math.sqrt(PERIODS_PER_YEAR);
    }

    /**
     * Sharpe Ratio using the {@link #DEFAULT_RISK_FREE_RATE default risk-free rate}.
     */
    public static double sharpeRatio(List<Double> periodReturns) {
        return sharpeRatio(periodReturns, DEFAULT_RISK_FREE_RATE);
    }

    /**
     * A single point of a per-bar equity curve: the equity value at a bar's
     * timestamp. Used by {@link #dailySharpeRatio(List)} to resample to daily closes.
     *
     * @param timestamp the bar's timestamp (UTC instants are expected)
     * @param equity    the equity value after that bar
     */
    public record EquityPoint(Instant timestamp, double equity) {
        public EquityPoint {
            if (timestamp == null) throw new NullPointerException("timestamp");
        }
    }

    /**
     * Daily Sharpe Ratio computed from a per-bar equity curve.
     *
     * <p>This is the gate-facing Sharpe (docs/lt-strategy-playbook.md §4.3): the
     * equity curve is resampled to a <em>daily</em> close (the last equity value of
     * each FX market day), consecutive day-over-day returns are computed net of the
     * daily risk-free rate, and the result is {@code mean / stddev × √252}. The
     * annualisation factor is always {@value #PERIODS_PER_YEAR} — independent of the
     * bar granularity — so an H1 and an H4 sampling of the same underlying equity yield
     * the same value.</p>
     *
     * <h3>Market-day boundary</h3>
     * <p>FX convention closes the trading day at {@value #MARKET_DAY_CLOSE} New York
     * time ({@value #MARKET_DAY_ZONE}), <em>not</em> midnight UTC. A UTC-midnight
     * boundary cuts a trading session in two and manufactures a ~2-hour Sunday
     * "day", which distorts the standard deviation itself. Each instant is therefore
     * mapped to the market day dated by its 17:00-New-York close via
     * {@link #marketDay(Instant)}; the mapping is DST-aware.</p>
     *
     * <h3>Risk-free rate</h3>
     * <p>The daily risk-free rate ({@link #DEFAULT_RISK_FREE_RATE} ÷
     * {@value #PERIODS_PER_YEAR}) is subtracted from the daily return series before the
     * mean/stddev (implemented as {@code mean(returns) − rfDaily}; the standard deviation
     * is unchanged by a constant shift), matching the legacy {@link #sharpeRatio(List, double)}
     * behaviour.</p>
     *
     * <p>Edge cases:</p>
     * <ul>
     *   <li>Fewer than 2 distinct market days → {@code 0.0};</li>
     *   <li>Fewer than 2 returns → {@code 0.0};</li>
     *   <li>Zero standard deviation with a <em>positive</em> excess mean →
     *       {@code Double.POSITIVE_INFINITY} (a zero-variance positive return IS an
     *       infinite Sharpe); zero or negative excess mean → {@code 0.0} (degenerate);</li>
     *   <li>A day with bars but no trades still counts as a day whose return is 0;</li>
     *   <li>Bars whose previous close is zero are skipped to avoid a division by zero.</li>
     * </ul>
     *
     * <p>The caller is expected to include the starting balance as the first point
     * (see {@link #marketDay(Instant)} and {@code BacktestEngine}, which prepends it one
     * market day before the first bar) so the first day's P&amp;L is captured as a daily
     * return rather than silently dropped.</p>
     *
     * @param equityCurve per-bar equity points (one value per bar, with the bar timestamp),
     *                    with the starting balance as the first point
     * @return daily Sharpe Ratio, or 0.0 when undefined
     */
    public static double dailySharpeRatio(List<EquityPoint> equityCurve) {
        if (equityCurve == null) return 0.0;

        // Defensive chronological sort; the engine emits points in order already.
        List<EquityPoint> sorted = new ArrayList<>(equityCurve.size());
        for (EquityPoint p : equityCurve) {
            if (p != null) sorted.add(p);
        }
        sorted.sort(Comparator.comparing(EquityPoint::timestamp));
        if (sorted.size() < 2) return 0.0;

        // Resample to market-day closes: the last equity value of each FX market day.
        LinkedHashMap<LocalDate, Double> dailyCloses = new LinkedHashMap<>();
        for (EquityPoint p : sorted) {
            dailyCloses.put(marketDay(p.timestamp()), p.equity());
        }
        if (dailyCloses.size() < 2) return 0.0;

        // Day-over-day returns (raw). The risk-free rate is a constant subtracted from the
        // mean only: mean(r - rf) == mean(r) - rf, and subtracting a constant leaves the
        // standard deviation unchanged — computing std on the raw returns keeps the
        // zero-variance detection exact (a flat or perfectly-regular series has std == 0.0).
        double rfDaily = DEFAULT_RISK_FREE_RATE / PERIODS_PER_YEAR;
        List<Double> closes = new ArrayList<>(dailyCloses.values());
        List<Double> rawReturns = new ArrayList<>(closes.size() - 1);
        for (int i = 1; i < closes.size(); i++) {
            double prev = closes.get(i - 1);
            if (prev == 0.0) continue; // guard against division by zero
            rawReturns.add((closes.get(i) - prev) / prev);
        }
        if (rawReturns.size() < 2) return 0.0;

        double mean = mean(rawReturns) - rfDaily;   // excess mean
        double std = standardDeviation(rawReturns);
        if (std == 0.0 || Double.isNaN(std)) {
            // Perfectly regular series: positive excess return is an infinite Sharpe;
            // zero or negative excess return is degenerate → 0.0 (the ≥0.3 gate rejects it either way).
            return mean > 0.0 ? Double.POSITIVE_INFINITY : 0.0;
        }

        double sharpe = (mean / std) * Math.sqrt(PERIODS_PER_YEAR);
        return Double.isFinite(sharpe) ? sharpe : 0.0;
    }

    /**
     * The FX market day an instant belongs to.
     *
     * <p>The trading day rolls over at {@value #MARKET_DAY_CLOSE} {@value #MARKET_DAY_ZONE}
     * time: an instant on or after 17:00 New York belongs to the <em>next</em> calendar
     * day's market day (the daily bar is dated by its close). DST-safe — the local
     * wall-clock time, not a fixed UTC offset, decides the boundary.</p>
     *
     * @param timestamp the instant to classify
     * @return the market day's close date (e.g. a Sunday 18:00 NY bar belongs to Monday's day)
     */
    static LocalDate marketDay(Instant timestamp) {
        ZonedDateTime ny = timestamp.atZone(MARKET_DAY_ZONE);
        return ny.toLocalTime().isBefore(MARKET_DAY_CLOSE)
            ? ny.toLocalDate()
            : ny.toLocalDate().plusDays(1);
    }

    /**
     * Legacy per-bar Sharpe Ratio, kept for diagnostics/comparison only.
     *
     * <p>This is the pre-fix engine Sharpe: period returns are annualised by the
     * detected {@code periodsPerYear} (e.g. √6240 for H1 forex), which makes the number
     * granularity-dependent and unfit for the gate. It is <em>not</em> the gate Sharpe;
     * use {@link #dailySharpeRatio(List)} for the gate (§4.3).</p>
     *
     * @param periodReturns   per-bar returns (decimal)
     * @param riskFreeRate    annual risk-free rate as decimal
     * @param periodsPerYear  bars-per-year annualisation factor
     * @return legacy per-bar Sharpe, or 0.0 if undefined
     */
    public static double perBarSharpeRatioLegacy(List<Double> periodReturns, double riskFreeRate, double periodsPerYear) {
        return sharpeRatio(periodReturns, riskFreeRate, periodsPerYear);
    }

    // ---------------------------------------------------------------
    //  Sortino Ratio
    // ---------------------------------------------------------------

    /**
     * Annualised Sortino Ratio (downside deviation only) with explicit periods-per-year.
     *
     * <p><b>Per-bar measure.</b> This operates on per-bar returns annualised by the given
     * {@code periodsPerYear}; it is <em>not</em> resampled to a daily step like
     * {@link #dailySharpeRatio(List)}. Do not compare a per-bar Sortino against the daily
     * Sharpe — they have different frequencies and annualisation. The gate (§4.3) uses the
     * daily Sharpe; this Sortino is reported for information only.</p>
     */
    public static double sortinoRatio(List<Double> periodReturns, double riskFreeRate, double periodsPerYear) {
        if (periodReturns == null || periodReturns.size() < 2) return 0.0;
        double rfPeriod = riskFreeRate / periodsPerYear;
        double mean = mean(periodReturns) - rfPeriod;
        double downsideDev = downsideDeviation(periodReturns);
        if (downsideDev == 0.0) return 0.0;
        return (mean / downsideDev) * Math.sqrt(periodsPerYear);
    }

    /**
     * Annualised Sortino Ratio (downside deviation only).
     *
     * <p>\[ Sortino = \frac{E[R_p - R_f]}{\sigma_d} \times \sqrt{periodsPerYear} \]</p>
     *
     * @param periodReturns  list of period-to-period returns (decimal)
     * @param riskFreeRate   annual risk-free rate as decimal
     * @return Sortino Ratio, or 0.0 if fewer than 2 returns or zero downside deviation
     */
    public static double sortinoRatio(List<Double> periodReturns, double riskFreeRate) {
        if (periodReturns == null || periodReturns.size() < 2) return 0.0;
        double rfPeriod = riskFreeRate / PERIODS_PER_YEAR;
        double mean = mean(periodReturns) - rfPeriod;
        double downsideDev = downsideDeviation(periodReturns);
        if (downsideDev == 0.0) return 0.0;
        return (mean / downsideDev) * Math.sqrt(PERIODS_PER_YEAR);
    }

    /**
     * Sortino Ratio using the {@link #DEFAULT_RISK_FREE_RATE default risk-free rate}.
     */
    public static double sortinoRatio(List<Double> periodReturns) {
        return sortinoRatio(periodReturns, DEFAULT_RISK_FREE_RATE);
    }

    // ---------------------------------------------------------------
    //  Profit Factor
    // ---------------------------------------------------------------

    /**
     * Profit Factor = Gross Profit / |Gross Loss|.
     *
     * <p>Returns 0.0 if there are no trades with P&amp;L data.
     * Returns the gross profit value itself if there are no losing trades (divide-by-zero guard).</p>
     *
     * @param tradePnlList list of per-trade P&amp;L values
     * @return Profit Factor (≥ 0)
     */
    public static double profitFactor(List<Double> tradePnlList) {
        if (tradePnlList == null || tradePnlList.isEmpty()) return 0.0;
        double grossProfit = 0.0, grossLoss = 0.0;
        for (double pnl : tradePnlList) {
            if (pnl > 0) grossProfit += pnl;
            else grossLoss += Math.abs(pnl);
        }
        if (grossLoss == 0.0) return grossProfit > 0 ? grossProfit : 0.0;
        return grossProfit / grossLoss;
    }

    // ---------------------------------------------------------------
    //  Calmar Ratio
    // ---------------------------------------------------------------

    /**
     * Calmar Ratio = Annualised Return / Max Drawdown.
     *
     * <p>Annualised return is derived from {@link #annualisedReturn(List)}.
     * Max drawdown is the maximum percentage peak-to-trough decline as a decimal (e.g. 0.15 = 15 %).</p>
     *
     * @param equityCurve  equity values over time (first = initial capital)
     * @return Calmar Ratio, or 0.0 if drawdown is zero or equityCurve is too small
     */
    public static double calmarRatio(List<Double> equityCurve) {
        if (equityCurve == null || equityCurve.size() < 2) return 0.0;
        double annReturn = annualisedReturn(equityCurve);
        double maxDd = maxDrawdownDecimal(equityCurve);
        if (maxDd == 0.0) return 0.0;
        double ratio = annReturn / maxDd;
        // Guard against NaN/Infinity when a strategy loses everything
        // (equity goes negative → annReturn from Math.pow() is NaN)
        return Double.isFinite(ratio) ? ratio : 0.0;
    }

    // ---------------------------------------------------------------
    //  Average Trade
    // ---------------------------------------------------------------

    /**
     * Mean P&amp;L per trade.
     *
     * @param tradePnlList list of per-trade P&amp;L values
     * @return arithmetic mean, or 0.0 if the list is empty
     */
    public static double averageTrade(List<Double> tradePnlList) {
        if (tradePnlList == null || tradePnlList.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double p : tradePnlList) sum += p;
        return sum / tradePnlList.size();
    }

    // ---------------------------------------------------------------
    //  Annualised Return
    // ---------------------------------------------------------------

    /**
     * Annualised return from an equity curve with explicit periods-per-year.
     */
    public static double annualisedReturn(List<Double> equityCurve, double periodsPerYear) {
        if (equityCurve == null || equityCurve.size() < 2) return 0.0;
        double start = equityCurve.getFirst();
        double end = equityCurve.getLast();
        if (start <= 0) return 0.0;
        int n = equityCurve.size() - 1;
        return Math.pow(end / start, periodsPerYear / n) - 1.0;
    }

    /**
     * Annualised return from an equity curve.
     *
     * <p>\[ AnnRet = \left(\frac{equity_{last}}{equity_{first}}\right)^{\frac{periodsPerYear}{n}} - 1 \]</p>
     *
     * @param equityCurve  equity values; length is used as period count
     * @return annualised return as decimal, or 0.0 if inputs are invalid
     */
    public static double annualisedReturn(List<Double> equityCurve) {
        if (equityCurve == null || equityCurve.size() < 2) return 0.0;
        double start = equityCurve.getFirst();
        double end = equityCurve.getLast();
        if (start <= 0) return 0.0;
        int n = equityCurve.size() - 1; // number of periods
        return Math.pow(end / start, PERIODS_PER_YEAR / n) - 1.0;
    }

    /**
     * Detects the number of trading periods per year from bar timestamps.
     *
     * <p>Calculates the median gap between consecutive bars during weekdays,
     * then derives bars-per-week and annualises. Falls back to {@link #PERIODS_PER_YEAR}
     * if there are fewer than 10 bars or data spans less than 2 days.</p>
     *
     * @param bars  list of bars with timestamps
     * @return detected periods per year, or {@link #PERIODS_PER_YEAR} as fallback
     */
    public static double detectPeriodsPerYear(List<Bar> bars) {
        if (bars == null || bars.size() < 10) return PERIODS_PER_YEAR;

        // Collect gaps between consecutive bars during weekdays (Mon 00:00 UTC — Fri 24:00 UTC)
        List<Long> weekdayGaps = new ArrayList<>();
        for (int i = 1; i < bars.size(); i++) {
            long gapSec = Duration.between(bars.get(i - 1).timestamp(), bars.get(i).timestamp()).getSeconds();
            if (gapSec <= 0) continue;
            // Include gaps up to 2x the expected max (cover H4 bars with 4h gaps)
            // Filter out weekend gaps (48h+/64h+ for 5-day trading week)
            if (gapSec <= 8 * H1_GAP_SEC) {
                weekdayGaps.add(gapSec);
            }
        }

        if (weekdayGaps.size() < 5) return PERIODS_PER_YEAR;

        // Median gap
        weekdayGaps.sort(Comparator.naturalOrder());
        long medianGapSec = weekdayGaps.get(weekdayGaps.size() / 2);
        if (medianGapSec <= 0) return PERIODS_PER_YEAR;

        // Bars per calendar day
        double barsPerDay = (double) SECONDS_PER_DAY / medianGapSec;
        // Trading days per week (5 for forex)
        double barsPerWeek = barsPerDay * 5.0;
        double periodsPerYear = Math.round(barsPerWeek * 52.0);

        return Math.max(periodsPerYear, PERIODS_PER_YEAR);
    }

    public static double maxDrawdownPct(List<Double> equityCurve) {
        return maxDrawdownDecimal(equityCurve) * 100.0;
    }

    // ---------------------------------------------------------------
    //  Internal helpers
    // ---------------------------------------------------------------

    /**
     * Max drawdown as a decimal (e.g. 0.15 = 15 %).
     */
    static double maxDrawdownDecimal(List<Double> equityCurve) {
        double peak = Double.NEGATIVE_INFINITY;
        double maxDd = 0.0;
        for (double e : equityCurve) {
            if (e > peak) peak = e;
            double dd = (peak - e) / peak;
            if (dd > maxDd) maxDd = dd;
        }
        return maxDd;
    }

    /**
     * Arithmetic mean of a list of doubles.
     */
    static double mean(List<Double> values) {
        double sum = 0.0;
        for (double v : values) sum += v;
        return sum / values.size();
    }

    /**
     * Sample standard deviation (Bessel's correction).
     */
    static double standardDeviation(List<Double> values) {
        double m = mean(values);
        double sumSq = 0.0;
        for (double v : values) sumSq += (v - m) * (v - m);
        return Math.sqrt(sumSq / (values.size() - 1));
    }

    /**
     * Downside deviation — standard deviation of returns below the target (risk-free rate per period).
     */
    static double downsideDeviation(List<Double> returns) {
        double target = 0.0;
        double sumSq = 0.0;
        int count = 0;
        for (double r : returns) {
            if (r < target) {
                double diff = r - target;
                sumSq += diff * diff;
                count++;
            }
        }
        if (count < 2) return 0.0;
        return Math.sqrt(sumSq / (count - 1));
    }
}
