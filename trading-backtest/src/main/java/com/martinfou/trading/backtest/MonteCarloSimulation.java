package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.Trade;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Monte Carlo simulation that randomises the order of trades in a backtest
 * to estimate the distribution of possible outcomes.
 *
 * <p>Each run shuffles the sequence of completed trades (preserving their
 * P&amp;L and drawdown characteristics) and recomputes the equity curve.
 * Over 1000+ runs this gives a probability distribution of performance
 * metrics — P&amp;L, max drawdown, Sharpe ratio — that quantifies
 * strategy robustness.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * BacktestResult baseline = engine.run();
 * MonteCarloSimulation mc = new MonteCarloSimulation(baseline, 1000);
 * MonteCarloSimulation.Result result = mc.run();
 *
 * System.out.println("Median P&L: " + result.medianPnl());
 * System.out.println("95% VaR:    " + result.var95());
 * }</pre>
 *
 * <p>By default runs are executed in parallel via {@link ForkJoinPool}.</p>
 */
public class MonteCarloSimulation {

    private final List<Trade> trades;
    private final double initialCapital;
    private final int runs;
    private final double periodsPerYear;
    private final int blockSize;
    private final Random random;
    private final int maxThreads;

    /**
     * @param baselineResult the original backtest result whose trades are reshuffled
     * @param runs           number of Monte Carlo simulation runs (e.g. 1000)
     */
    public MonteCarloSimulation(BacktestResult baselineResult, int runs) {
        this(baselineResult, runs, 3, new Random(), Runtime.getRuntime().availableProcessors());
    }

    /**
     * Full constructor.
     *
     * @param baselineResult the original backtest result
     * @param runs           number of simulation runs
     * @param blockSize      number of consecutive trades to keep together (1 = individual shuffle, 3+ = block bootstrap)
     * @param random         random source for shuffling
     * @param maxThreads     maximum concurrent threads (1 for deterministic)
     */
    public MonteCarloSimulation(BacktestResult baselineResult, int runs, int blockSize,
                                Random random, int maxThreads) {
        this.trades = List.copyOf(baselineResult.trades());
        this.initialCapital = baselineResult.initialCapital();
        this.periodsPerYear = baselineResult.periodsPerYear();
        this.runs = runs;
        this.blockSize = Math.max(1, blockSize);
        this.random = random;
        this.maxThreads = Math.max(1, maxThreads);
    }

    /**
     * @param baselineResult the original backtest result
     * @param runs           number of Monte Carlo simulation runs
     * @param blockSize      consecutive trades to keep together (3 = default recommendation)
     */
    public MonteCarloSimulation(BacktestResult baselineResult, int runs, int blockSize) {
        this(baselineResult, runs, blockSize, new Random(), Runtime.getRuntime().availableProcessors());
    }

    // ---------------------------------------------------------------
    //  Run
    // ---------------------------------------------------------------

    /**
     * Executes all Monte Carlo runs and collects distribution statistics.
     *
     * @return aggregated result with percentiles and confidence intervals
     */
    public Result run() {
        if (trades.isEmpty()) {
            return new Result(runs, List.of(), List.of(), List.of(), initialCapital);
        }

        ForkJoinPool pool = new ForkJoinPool(maxThreads);
        try {
            List<RunOutcome> outcomes = pool.submit(this::computeOutcomes).join();

            List<Double> pnlValues = outcomes.stream()
                .map(RunOutcome::pnl).sorted().toList();
            List<Double> ddValues = outcomes.stream()
                .map(RunOutcome::maxDrawdownPct).sorted().toList();
            List<Double> sharpeValues = outcomes.stream()
                .map(RunOutcome::sharpe).sorted().toList();

            return new Result(runs, pnlValues, ddValues, sharpeValues, initialCapital);
        } finally {
            pool.shutdown();
        }
    }

    private List<RunOutcome> computeOutcomes() {
        int batchSize = Math.max(1, runs / maxThreads);
        List<Callable<List<RunOutcome>>> tasks = new ArrayList<>(maxThreads);

        for (int t = 0; t < maxThreads; t++) {
            int start = t * batchSize;
            int end = (t == maxThreads - 1) ? runs : start + batchSize;
            tasks.add(() -> {
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                List<RunOutcome> batch = new ArrayList<>(end - start);
                double[] working = new double[trades.size()];
                for (int i = start; i < end; i++) {
                    batch.add(singleRun(rng, working));
                }
                return batch;
            });
        }

        try {
            return ForkJoinPool.commonPool()
                .invokeAll(tasks).stream()
                .flatMap(f -> {
                    try { return f.get().stream(); }
                    catch (Exception e) { return Stream.<RunOutcome>empty(); }
                })
                .toList();
        } catch (Exception e) {
            throw new RuntimeException("Monte Carlo simulation failed", e);
        }
    }

    /**
     * A single Monte Carlo run: shuffle trades (individual or block bootstrap),
     * reconstruct equity curve, compute P&amp;L, drawdown, and Sharpe ratio.
     */
    private RunOutcome singleRun(ThreadLocalRandom rng, double[] working) {
        // Copy trade P&Ls into a mutable array and shuffle
        for (int i = 0; i < trades.size(); i++) {
            working[i] = trades.get(i).pnl();
        }
        if (blockSize <= 1) {
            shuffle(working, rng);
        } else {
            blockShuffle(working, rng);
        }

        // Reconstruct equity curve: start with initial capital, add shuffled trades
        double equity = initialCapital;
        double peak = equity;
        double maxDd = 0;
        double totalPnl = 0;

        // For Sharpe, collect per-trade returns
        List<Double> returns = new ArrayList<>(trades.size());

        for (int i = 0; i < working.length; i++) {
            double tradePnl = working[i];
            equity += tradePnl;
            totalPnl += tradePnl;

            // Track drawdown
            if (equity > peak) peak = equity;
            double dd = (peak - equity) / peak * 100;
            if (dd > maxDd) maxDd = dd;

            // Period return
            double prevEquity = equity - tradePnl;
            if (prevEquity != 0) {
                returns.add((equity - prevEquity) / prevEquity);
            }
        }

        double sharpe = PerformanceMetrics.sharpeRatio(returns, PerformanceMetrics.DEFAULT_RISK_FREE_RATE, periodsPerYear);

        return new RunOutcome(totalPnl, maxDd, sharpe);
    }

    /**
     * In-place Fisher-Yates shuffle.
     */
    private static void shuffle(double[] array, ThreadLocalRandom rng) {
        for (int i = array.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            double tmp = array[i];
            array[i] = array[j];
            array[j] = tmp;
        }
    }

    /**
     * Block bootstrap shuffle: divides the array into blocks of {@link #blockSize}
     * consecutive trades, shuffles the blocks, then writes them back in block order.
     * Preserves intra-block sequential order.
     */
    private void blockShuffle(double[] array, ThreadLocalRandom rng) {
        int n = array.length;
        if (n < blockSize + 1) {
            // Not enough trades for meaningful blocks — fall back to individual shuffle
            shuffle(array, rng);
            return;
        }

        // Build block boundaries
        int numBlocks = (n + blockSize - 1) / blockSize; // ceil division
        int[] blockStarts = new int[numBlocks];
        for (int b = 0; b < numBlocks; b++) {
            blockStarts[b] = b * blockSize;
        }

        // Fisher-Yates shuffle on blocks
        for (int i = numBlocks - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            // Swap block i and block j
            swapBlocks(array, blockStarts, i, j, n);
        }

        // Swap the block start indices too to reflect the new ordering
        for (int i = numBlocks - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmpIdx = blockStarts[i];
            blockStarts[i] = blockStarts[j];
            blockStarts[j] = tmpIdx;
        }
    }

    /**
     * Swaps the data between two blocks in-place.
     */
    private static void swapBlocks(double[] array, int[] blockStarts, int bi, int bj, int n) {
        int si = blockStarts[bi];
        int sj = blockStarts[bj];
        int ei = Math.min(si + Math.min(blockSize(bi, blockStarts, n), blockSize(bj, blockStarts, n)), n);
        int len = Math.min(ei - si, Math.min(blockSize(bi, blockStarts, n), blockSize(bj, blockStarts, n)));
        for (int k = 0; k < len; k++) {
            double tmp = array[si + k];
            array[si + k] = array[sj + k];
            array[sj + k] = tmp;
        }
    }

    /**
     * Returns the size of block {@code b} based on start indices and total array length.
     */
    private static int blockSize(int b, int[] blockStarts, int n) {
        if (b >= blockStarts.length - 1) return n - blockStarts[b];
        return blockStarts[b + 1] - blockStarts[b];
    }

    // ---------------------------------------------------------------
    //  Internal outcome record
    // ---------------------------------------------------------------

    record RunOutcome(double pnl, double maxDrawdownPct, double sharpe) {}

    // ---------------------------------------------------------------
    //  Aggregated Result
    // ---------------------------------------------------------------

    /**
     * Aggregated results of a Monte Carlo simulation, with sorted value
     * lists for easy percentile computation.
     */
    public record Result(
        int totalRuns,
        List<Double> pnlValuesSorted,
        List<Double> drawdownValuesSorted,
        List<Double> sharpeValuesSorted,
        double initialCapital
    ) {

        // ---------- P&L ----------

        /** Arithmetic mean P&amp;L across all runs. */
        public double meanPnl() {
            return pnlValuesSorted.isEmpty() ? 0.0
                : pnlValuesSorted.stream().mapToDouble(d -> d).average().orElse(0.0);
        }

        /** Median (50th percentile) P&amp;L. */
        public double medianPnl() {
            return percentile(pnlValuesSorted, 0.50);
        }

        /** Best P&amp;L (maximum). */
        public double bestPnl() {
            return pnlValuesSorted.isEmpty() ? 0.0 : pnlValuesSorted.getLast();
        }

        /** Worst P&amp;L (minimum). */
        public double worstPnl() {
            return pnlValuesSorted.isEmpty() ? 0.0 : pnlValuesSorted.getFirst();
        }

        /** 5th percentile P&amp;L (95 % of runs are better). */
        public double var95() {
            return percentile(pnlValuesSorted, 0.05);
        }

        /** Probability of a negative outcome (P&amp;L &lt; 0). */
        public double probabilityOfLoss() {
            if (pnlValuesSorted.isEmpty()) return 0.0;
            long losses = pnlValuesSorted.stream().filter(p -> p < 0).count();
            return (double) losses / pnlValuesSorted.size() * 100;
        }

        // ---------- Drawdown ----------

        /** Mean max drawdown across all runs (as percentage). */
        public double meanDrawdown() {
            return drawdownValuesSorted.isEmpty() ? 0.0
                : drawdownValuesSorted.stream().mapToDouble(d -> d).average().orElse(0.0);
        }

        /** Median max drawdown. */
        public double medianDrawdown() {
            return percentile(drawdownValuesSorted, 0.50);
        }

        /** 95th percentile drawdown (only 5 % of runs exceed this DD). */
        public double drawdown95() {
            return percentile(drawdownValuesSorted, 0.95);
        }

        // ---------- Sharpe ----------

        /** Mean Sharpe ratio across all runs. */
        public double meanSharpe() {
            return sharpeValuesSorted.isEmpty() ? 0.0
                : sharpeValuesSorted.stream().mapToDouble(d -> d).average().orElse(0.0);
        }

        /** Median Sharpe ratio. */
        public double medianSharpe() {
            return percentile(sharpeValuesSorted, 0.50);
        }

        // ---------- Utility ----------

        /**
         * Returns the value at the given quantile from a sorted list.
         *
         * @param sorted  sorted (ascending) list of values
         * @param quantile 0.0…1.0
         * @return interpolated quantile value, or 0.0 if the list is empty
         */
        public static double percentile(List<Double> sorted, double quantile) {
            if (sorted == null || sorted.isEmpty()) return 0.0;
            int n = sorted.size();
            double pos = quantile * (n - 1);
            int idx = (int) pos;
            double frac = pos - idx;
            if (idx >= n - 1) return sorted.get(n - 1);
            return sorted.get(idx) + frac * (sorted.get(idx + 1) - sorted.get(idx));
        }

        /** Prints a formatted summary of the simulation results. */
        public void printSummary() {
            System.out.println("\n================================================");
            System.out.println("  MONTE CARLO SIMULATION (" + totalRuns + " runs)");
            System.out.println("================================================");
            System.out.println("Initial Capital: $" + String.format("%,.2f", initialCapital));
            System.out.println();
            System.out.println("─── P&L Distribution ─────────────────────");
            System.out.println("Mean:        $" + String.format("%,.2f", meanPnl()));
            System.out.println("Median:      $" + String.format("%,.2f", medianPnl()));
            System.out.println("Best:        $" + String.format("%,.2f", bestPnl()));
            System.out.println("Worst:       $" + String.format("%,.2f", worstPnl()));
            System.out.println("VaR (95%):   $" + String.format("%,.2f", var95()));
            System.out.println("Loss Prob:   " + String.format("%.1f", probabilityOfLoss()) + "%");
            System.out.println();
            System.out.println("─── Drawdown Distribution ────────────────");
            System.out.println("Mean DD:     " + String.format("%.2f", meanDrawdown()) + "%");
            System.out.println("Median DD:   " + String.format("%.2f", medianDrawdown()) + "%");
            System.out.println("DD 95th:     " + String.format("%.2f", drawdown95()) + "%");
            System.out.println();
            System.out.println("─── Sharpe Distribution ──────────────────");
            System.out.println("Mean Sharpe: " + String.format("%.2f", meanSharpe()));
            System.out.println("Median Sharpe: " + String.format("%.2f", medianSharpe()));
            System.out.println("===============================================\n");
        }
    }
}
