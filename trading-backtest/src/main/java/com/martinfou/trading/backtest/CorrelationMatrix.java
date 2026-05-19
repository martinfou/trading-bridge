package com.martinfou.trading.backtest;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes pairwise correlation matrices between multiple strategy backtest results.
 *
 * <p>Two correlation types are supported:</p>
 * <ul>
 *   <li><strong>P&amp;L correlation</strong> — Pearson correlation of daily P&amp;L series</li>
 *   <li><strong>Drawdown correlation</strong> — how often drawdowns overlap between strategies</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * Map<String, BacktestResult> results = Map.of(
 *     "SMA Crossover", result1,
 *     "RSI MeanRev",   result2,
 *     "MACD Trend",    result3
 * );
 * CorrelationMatrix cm = new CorrelationMatrix(results);
 * cm.printPnLMatrix();
 * }</pre>
 */
public class CorrelationMatrix {

    /** Pairwise correlation data. */
    public record Pair(String strategyA, String strategyB, double correlation, int overlappingPeriods) {}

    private final Map<String, BacktestResult> results;
    private final boolean usePeriodReturns;

    /**
     * Creates a correlation matrix from named backtest results.
     *
     * @param results           map of strategy name → backtest result
     * @param usePeriodReturns  if true, use equity-curve period returns;
     *                          if false, use per-trade P&amp;L vectors
     */
    public CorrelationMatrix(Map<String, BacktestResult> results, boolean usePeriodReturns) {
        this.results = Map.copyOf(results);
        this.usePeriodReturns = usePeriodReturns;
    }

    /**
     * Convenience: uses period returns from the equity curve.
     */
    public CorrelationMatrix(Map<String, BacktestResult> results) {
        this(results, true);
    }

    // ---------------------------------------------------------------
    //  P&L Correlation
    // ---------------------------------------------------------------

    /**
     * Computes pairwise Pearson correlation of the return/P&amp;L series.
     *
     * @return list of unique pairs (lower triangular) with correlation values
     */
    public List<Pair> computePnLCorrelation() {
        List<String> names = names();
        List<Pair> pairs = new ArrayList<>();

        Map<String, List<Double>> series = new HashMap<>();
        for (var entry : results.entrySet()) {
            series.put(entry.getKey(),
                usePeriodReturns
                    ? entry.getValue().periodReturns()
                    : entry.getValue().tradePnlList());
        }

        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                String a = names.get(i);
                String b = names.get(j);
                pairs.add(computeCorrelation(a, b, series.get(a), series.get(b)));
            }
        }
        return pairs;
    }

    private Pair computeCorrelation(String a, String b, List<Double> xList, List<Double> yList) {
        // Align by index (minimum length)
        int n = Math.min(xList.size(), yList.size());
        if (n < 2) return new Pair(a, b, Double.NaN, n);

        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = xList.get(i);
            y[i] = yList.get(i);
        }

        double r = pearson(x, y);
        return new Pair(a, b, r, n);
    }

    /**
     * Computes Pearson correlation coefficient between two arrays.
     */
    static double pearson(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n < 2) return Double.NaN;

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
            sumY2 += y[i] * y[i];
        }

        double num = n * sumXY - sumX * sumY;
        double den = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        if (den == 0) return Double.NaN;
        return num / den;
    }

    // ---------------------------------------------------------------
    //  Drawdown Correlation
    // ---------------------------------------------------------------

    /**
     * Computes pairwise drawdown correlation: the fraction of periods
     * where both strategies are simultaneously in a drawdown.
     *
     * <p>A value of 1.0 means both strategies always draw down together.
     * A value of 0.0 means they never draw down at the same time.</p>
     *
     * @return list of unique pairs with drawdown overlap ratios
     */
    public List<Pair> computeDrawdownCorrelation() {
        List<String> names = names();
        List<Pair> pairs = new ArrayList<>();

        Map<String, boolean[]> ddFlags = new HashMap<>();
        for (var entry : results.entrySet()) {
            ddFlags.put(entry.getKey(), computeDrawdownFlags(entry.getValue().equityCurve()));
        }

        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                String a = names.get(i);
                String b = names.get(j);
                boolean[] aFlags = ddFlags.get(a);
                boolean[] bFlags = ddFlags.get(b);
                int n = Math.min(aFlags.length, bFlags.length);
                int bothInDD = 0;
                int atLeastOne = 0;
                for (int k = 0; k < n; k++) {
                    if (aFlags[k] && bFlags[k]) bothInDD++;
                    if (aFlags[k] || bFlags[k]) atLeastOne++;
                }
                double ratio = atLeastOne > 0 ? (double) bothInDD / atLeastOne : 0;
                pairs.add(new Pair(a, b, ratio, n));
            }
        }
        return pairs;
    }

    /**
     * Returns a boolean array of the same length as the equity curve,
     * where {@code true} means the strategy is below its peak (in drawdown).
     */
    static boolean[] computeDrawdownFlags(List<Double> equityCurve) {
        if (equityCurve == null || equityCurve.isEmpty()) return new boolean[0];
        boolean[] flags = new boolean[equityCurve.size()];
        double peak = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < equityCurve.size(); i++) {
            double e = equityCurve.get(i);
            if (e > peak) peak = e;
            flags[i] = e < peak;
        }
        return flags;
    }

    // ---------------------------------------------------------------
    //  Matrix output
    // ---------------------------------------------------------------

    /**
     * Returns the correlation values as a 2D double matrix[lowerTriangular].
     * Rows/columns follow the same order as {@link #names()}.
     */
    public double[][] toMatrix() {
        List<Pair> pairs = computePnLCorrelation();
        int n = names().size();
        double[][] matrix = new double[n][n];
        for (int i = 0; i < n; i++) matrix[i][i] = 1.0;

        for (Pair p : pairs) {
            int i = names().indexOf(p.strategyA());
            int j = names().indexOf(p.strategyB());
            matrix[i][j] = p.correlation();
            matrix[j][i] = p.correlation();
        }
        return matrix;
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private List<String> names() {
        return results.keySet().stream().sorted().toList();
    }

    /** Prints the P&amp;L correlation matrix as a formatted table. */
    public void printPnLMatrix() {
        List<String> nms = names();
        double[][] matrix = toMatrix();

        System.out.println("\n─── P&L Correlation Matrix ─────────────────");
        int colWidth = 22;
        System.out.printf("%-" + colWidth + "s", "");
        for (String name : nms) {
            System.out.printf("%-12s", truncate(name, 12));
        }
        System.out.println();

        for (int i = 0; i < nms.size(); i++) {
            System.out.printf("%-" + colWidth + "s", truncate(nms.get(i), colWidth - 1));
            for (int j = 0; j < nms.size(); j++) {
                System.out.printf("%-12s", String.format("%.3f", matrix[i][j]));
            }
            System.out.println();
        }
        System.out.println();
    }

    /** Prints drawdown overlap matrix. */
    public void printDrawdownMatrix() {
        List<Pair> pairs = computeDrawdownCorrelation();
        System.out.println("\n─── Drawdown Overlap Matrix ────────────────");
        for (Pair p : pairs) {
            System.out.printf("  %s ↔ %s: %.2f (overlap ratio)%n",
                p.strategyA(), p.strategyB(), p.correlation());
        }
        System.out.println();
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }
}
