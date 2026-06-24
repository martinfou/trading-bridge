package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Bar;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** Number of trading periods assumed per year (daily bars for forex). */
    public static final double PERIODS_PER_YEAR = 252.0;

    /** Seconds in one calendar day (used for timeframe detection). */
    static final long SECONDS_PER_DAY = 86_400L;

    /** Default gap for H1 forex bars during weekdays (1 hour in seconds). */
    static final long H1_GAP_SEC = 3_600L;

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

    // ---------------------------------------------------------------
    //  Sortino Ratio
    // ---------------------------------------------------------------

    /**
     * Annualised Sortino Ratio (downside deviation only) with explicit periods-per-year.
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
        return annReturn / maxDd;
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
