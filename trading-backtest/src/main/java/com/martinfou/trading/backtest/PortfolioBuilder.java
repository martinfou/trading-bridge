package com.martinfou.trading.backtest;

import java.util.*;
import java.util.stream.IntStream;

/**
 * Portfolio optimisation and allocation builder using Mean-Variance (Markowitz)
 * framework.
 *
 * <p>Given a set of backtest results (one per strategy), the builder computes:
 * <ul>
 *   <li>Equal-weight allocation (baseline)</li>
 *   <li>Minimum Variance portfolio</li>
 *   <li>Maximum Sharpe Ratio (tangency) portfolio</li>
 *   <li>Efficient frontier points</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * Map<String, BacktestResult> results = Map.of(
 *     "SMA",  smaResult,
 *     "RSI",  rsiResult,
 *     "MACD", macdResult
 * );
 * PortfolioBuilder pb = new PortfolioBuilder(results);
 * PortfolioBuilder.Portfolio maxSharpe = pb.maxSharpePortfolio();
 * maxSharpe.printSummary();
 * }</pre>
 */
public class PortfolioBuilder {

    /** A set of portfolio weights and their resulting metrics. */
    public record Portfolio(
        Map<String, Double> weights,
        double expectedReturn,
        double expectedVariance,
        double expectedStdDev,
        double sharpeRatio
    ) {
        /** Prints a formatted summary of this portfolio. */
        public void printSummary() {
            System.out.println("\n─── Portfolio Summary ──────────────────────");
            System.out.printf("Expected Return: %.4f%%%n", expectedReturn * 100);
            System.out.printf("Expected StdDev: %.4f%%%n", expectedStdDev * 100);
            System.out.printf("Sharpe Ratio:    %.4f%n", sharpeRatio);
            System.out.println("Allocations:");
            weights.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %s: %.2f%%%n",
                    e.getKey(), e.getValue() * 100));
            System.out.println();
        }
    }

    /** A point on the efficient frontier. */
    public record FrontierPoint(double returnPct, double riskPct, double sharpe, Map<String, Double> weights) {}

    private final List<String> names;
    private final double[] meanReturns;       // daily mean return for each strategy
    private final double[][] covarianceMatrix; // n×n covariance matrix
    private final int n;                       // number of strategies
    private final int periods;

    /**
     * Creates a portfolio builder from named backtest results.
     * The returns are extracted from each result's equity curve.
     *
     * @param results map of strategy name → backtest result
     */
    public PortfolioBuilder(Map<String, BacktestResult> results) {
        this.names = List.copyOf(results.keySet().stream().sorted().toList());
        this.n = names.size();

        // Collect period returns for each strategy
        List<List<Double>> periodReturnsList = new ArrayList<>(n);
        int minPeriods = Integer.MAX_VALUE;
        for (String name : names) {
            List<Double> pr = results.get(name).periodReturns();
            periodReturnsList.add(pr);
            if (pr.size() < minPeriods) minPeriods = pr.size();
        }
        this.periods = minPeriods;

        // Truncate all series to the same length
        meanReturns = new double[n];
        double[][] alignedReturns = new double[n][periods];
        for (int i = 0; i < n; i++) {
            List<Double> series = periodReturnsList.get(i);
            for (int t = 0; t < periods; t++) {
                alignedReturns[i][t] = series.get(t);
            }
            meanReturns[i] = Arrays.stream(alignedReturns[i]).average().orElse(0.0);
        }

        // Compute covariance matrix
        covarianceMatrix = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i; j < n; j++) {
                double cov = covariance(alignedReturns[i], alignedReturns[j]);
                covarianceMatrix[i][j] = cov;
                covarianceMatrix[j][i] = cov;
            }
        }
    }

    // ---------------------------------------------------------------
    //  Equal weight
    // ---------------------------------------------------------------

    /**
     * Equal-weight (1/n) portfolio.
     */
    public Portfolio equalWeightPortfolio() {
        double[] w = new double[n];
        Arrays.fill(w, 1.0 / n);
        return buildPortfolio(w);
    }

    // ---------------------------------------------------------------
    //  Minimum Variance
    // ---------------------------------------------------------------

    /**
     * Minimum Variance portfolio — the point on the efficient frontier
     * with the lowest possible variance. Solved via quadratic programming
     * (Lagrange multiplier approach for the unconstrained case).
     *
     * <p>We use a simple numerical search over the feasible set when n ≤ 5.
     * For larger n, we fall back to the inverse-variance heuristic.</p>
     */
    public Portfolio minVariancePortfolio() {
        if (n <= 10) {
            return numericalOptimise(this::portfolioVariance);
        }
        return inverseVariancePortfolio();
    }

    // ---------------------------------------------------------------
    //  Maximum Sharpe / Tangency
    // ---------------------------------------------------------------

    /**
     * Maximum Sharpe Ratio (tangency) portfolio.
     *
     * <p>Maximises (portfolio return - risk-free rate) / portfolio stddev.</p>
     */
    public Portfolio maxSharpePortfolio() {
        if (n <= 10) {
            return numericalOptimise(w -> -portfolioSharpe(w));
        }
        return equalWeightPortfolio(); // fallback
    }

    // ---------------------------------------------------------------
    //  Efficient Frontier
    // ---------------------------------------------------------------

    /**
     * Computes points along the efficient frontier by sweeping target returns.
     *
     * @param points number of points to generate (default 20)
     * @return list of frontier points, from low return to high return
     */
    public List<FrontierPoint> efficientFrontier(int points) {
        if (n < 2 || points < 2) return List.of();

        double minRet = Arrays.stream(meanReturns).min().orElse(0);
        double maxRet = Arrays.stream(meanReturns).max().orElse(0);
        List<FrontierPoint> frontier = new ArrayList<>(points);

        for (int i = 0; i < points; i++) {
            double targetRet = minRet + (maxRet - minRet) * i / (points - 1);
            double[] w = optimiseForTargetReturn(targetRet);
            if (w != null) {
                Map<String, Double> wMap = new LinkedHashMap<>();
                for (int j = 0; j < n; j++) wMap.put(names.get(j), w[j]);
                double var = portfolioVariance(w);
                double std = Math.sqrt(var);
                double sharpe = (targetRet - 0.0) / std; // risk-free ≈ 0 in daily
                frontier.add(new FrontierPoint(targetRet * 100, std * 100, sharpe, wMap));
            }
        }
        return frontier;
    }

    // ---------------------------------------------------------------
    //  Internal optimisation helpers
    // ---------------------------------------------------------------

    /**
     * Numerical optimisation using a simple grid search over the simplex.
     * This works for small n (≤ 10) and yields a good approximation.
     */
    private Portfolio numericalOptimise(java.util.function.ToDoubleFunction<double[]> objective) {
        // Use iterative random search with convex constraints
        double[] bestW = null;
        double bestVal = Double.MAX_VALUE;
        Random rng = new Random(42);

        // Random weights + gradient descent style refinement
        for (int iter = 0; iter < 50000; iter++) {
            double[] w = randomWeights(rng);
            double val = objective.applyAsDouble(w);
            if (iter == 0 || val < bestVal) {
                bestVal = val;
                bestW = w.clone();
            }
        }

        // Local refinement around best weights
        for (int refine = 0; refine < 10; refine++) {
            double[] perturbed = perturbWeights(bestW, rng);
            double val = objective.applyAsDouble(perturbed);
            if (val < bestVal) {
                bestVal = val;
                bestW = perturbed;
            }
        }

        // Clamp small negative weights to zero and renormalise
        if (bestW != null) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                if (bestW[i] < 0.005) bestW[i] = 0;
                sum += bestW[i];
            }
            if (sum > 0) {
                for (int i = 0; i < n; i++) bestW[i] /= sum;
            }
        }

        if (bestW == null) return equalWeightPortfolio();
        return buildPortfolio(bestW);
    }

    private double[] randomWeights(Random rng) {
        double[] w = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            w[i] = rng.nextDouble();
            sum += w[i];
        }
        for (int i = 0; i < n; i++) w[i] /= sum;
        return w;
    }

    private double[] perturbWeights(double[] base, Random rng) {
        double[] w = base.clone();
        double perturbation = 0.1;
        for (int i = 0; i < n; i++) {
            w[i] += (rng.nextDouble() - 0.5) * perturbation;
            if (w[i] < 0) w[i] = 0;
        }
        double sum = Arrays.stream(w).sum();
        if (sum > 0) for (int i = 0; i < n; i++) w[i] /= sum;
        return w;
    }

    /**
     * Simple inverse-variance heuristic for min variance when n is large.
     */
    private Portfolio inverseVariancePortfolio() {
        double[] invVar = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            invVar[i] = 1.0 / (covarianceMatrix[i][i] + 1e-10);
            sum += invVar[i];
        }
        for (int i = 0; i < n; i++) invVar[i] /= sum;
        return buildPortfolio(invVar);
    }

    /**
     * Simple target-return optimisation: find weights that achieve the target
     * return while minimising variance. Works for n=2 analytically;
     * for n>2 uses a heuristic search.
     */
    private double[] optimiseForTargetReturn(double targetReturn) {
        if (n == 2) {
            // Analytical two-asset solution
            double r1 = meanReturns[0], r2 = meanReturns[1];
            double v1 = covarianceMatrix[0][0], v2 = covarianceMatrix[1][1];
            double cov = covarianceMatrix[0][1];
            if (Math.abs(r1 - r2) < 1e-12) return new double[]{0.5, 0.5};

            // Minimise variance subject to return constraint: w = (target - r2) / (r1 - r2)
            double w1 = (targetReturn - r2) / (r1 - r2);
            double w2 = 1.0 - w1;
            if (w1 < 0 || w2 < 0) return null; // not attainable
            return new double[]{w1, w2};
        }

        // For n > 2, use numerical search
        Random rng = new Random((long) (targetReturn * 1e6));
        double[] bestW = null;
        double bestVar = Double.MAX_VALUE;

        for (int iter = 0; iter < 20000; iter++) {
            double[] w = randomWeights(rng);
            double ret = portfolioReturn(w);
            if (Math.abs(ret - targetReturn) > 1e-6) continue;
            double var = portfolioVariance(w);
            if (var < bestVar) {
                bestVar = var;
                bestW = w.clone();
            }
        }
        return bestW;
    }

    // ---------------------------------------------------------------
    //  Portfolio maths
    // ---------------------------------------------------------------

    private double portfolioReturn(double[] weights) {
        double r = 0;
        for (int i = 0; i < n; i++) r += weights[i] * meanReturns[i];
        return r;
    }

    private double portfolioVariance(double[] weights) {
        double var = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                var += weights[i] * weights[j] * covarianceMatrix[i][j];
            }
        }
        return var;
    }

    private double portfolioSharpe(double[] weights) {
        double ret = portfolioReturn(weights);
        double std = Math.sqrt(portfolioVariance(weights));
        if (std == 0) return 0;
        return ret / std;
    }

    private Portfolio buildPortfolio(double[] weights) {
        double ret = portfolioReturn(weights);
        double var = portfolioVariance(weights);
        double std = Math.sqrt(var);
        double sharpe = (std == 0) ? 0 : ret / std;

        Map<String, Double> wMap = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            wMap.put(names.get(i), weights[i]);
        }
        return new Portfolio(wMap, ret, var, std, sharpe);
    }

    // ---------------------------------------------------------------
    //  Static helpers
    // ---------------------------------------------------------------

    /**
     * Computes the sample covariance between two arrays.
     */
    static double covariance(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n < 2) return 0;
        double mx = Arrays.stream(x).average().orElse(0);
        double my = Arrays.stream(y).average().orElse(0);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (x[i] - mx) * (y[i] - my);
        }
        return sum / (n - 1);
    }

    /** Prints the efficient frontier as a table. */
    public void printEfficientFrontier(int points) {
        var frontier = efficientFrontier(points);
        System.out.println("\n─── Efficient Frontier ──────────────────────");
        System.out.printf("%-10s %-10s %-10s%n", "Return %", "Risk %", "Sharpe");
        for (FrontierPoint fp : frontier) {
            System.out.printf("%-10.2f %-10.2f %-10.4f%n",
                fp.returnPct(), fp.riskPct(), fp.sharpe());
        }
        System.out.println();
    }
}
