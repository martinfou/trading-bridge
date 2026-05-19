package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;

import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Walk-Forward Optimisation (WFO) for evaluating strategy robustness
 * over multiple in-sample / out-of-sample windows.
 *
 * <p>The price data is divided into a sequence of overlapping or sliding
 * windows. Each window consists of an <em>in-sample</em> (IS) segment
 * used for parameter optimisation and an <em>out-of-sample</em> (OOS)
 * segment used for validation. Metrics are averaged across all windows
 * to assess whether a strategy generalises or overfits.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Define how to build and optimise a strategy for given IS bars
 * WalkForwardOptimizer wfo = new WalkForwardOptimizer(
 *     (isBars, oosBars) -> {
 *         // e.g. find best SMA period on IS, then test on OOS
 *         SmaCrossoverStrategy opt = new SmaCrossoverStrategy(bestPeriod);
 *         return new BacktestEngine(opt, oosBars, 10000).run();
 *     },
 *     bars, 180, 60    // 180-day IS, 60-day OOS
 * );
 *
 * WalkForwardOptimizer.Result result = wfo.run();
 * result.printSummary();
 * }</pre>
 */
public class WalkForwardOptimizer {

    private final BiFunction<List<Bar>, List<Bar>, BacktestResult> strategyBuilder;
    private final List<Bar> bars;
    private final int inSampleDays;
    private final int outOfSampleDays;
    private final int slideDays;

    /**
     * Creates a walk-forward optimizer.
     *
     * @param strategyBuilder  function that takes (inSampleBars, outOfSampleBars)
     *                         and returns a BacktestResult for the OOS period.
     *                         The strategy is re-optimised on IS bars each window.
     * @param bars             all bars in chronological order
     * @param inSampleDays     length of the in-sample training window (calendar days)
     * @param outOfSampleDays  length of the out-of-sample testing window
     * @param slideDays        number of days to slide each window forward
     *                         (typically = OOS days for non-overlapping windows)
     */
    public WalkForwardOptimizer(
            BiFunction<List<Bar>, List<Bar>, BacktestResult> strategyBuilder,
            List<Bar> bars,
            int inSampleDays,
            int outOfSampleDays,
            int slideDays) {
        this.strategyBuilder = strategyBuilder;
        this.bars = List.copyOf(bars);
        this.inSampleDays = inSampleDays;
        this.outOfSampleDays = outOfSampleDays;
        this.slideDays = slideDays;
    }

    /**
     * Convenience constructor with {@code slideDays = outOfSampleDays}
     * (non-overlapping windows).
     */
    public WalkForwardOptimizer(
            BiFunction<List<Bar>, List<Bar>, BacktestResult> strategyBuilder,
            List<Bar> bars,
            int inSampleDays,
            int outOfSampleDays) {
        this(strategyBuilder, bars, inSampleDays, outOfSampleDays, outOfSampleDays);
    }

    // ---------------------------------------------------------------
    //  Run
    // ---------------------------------------------------------------

    /**
     * Executes the walk-forward optimisation over all windows.
     *
     * @return aggregated result with IS/OOS metrics per window and averages
     */
    public Result run() {
        if (bars.size() < 2) {
            return new Result(List.of(), 0, 0);
        }

        // Build windows by sliding forward
        Instant first = bars.getFirst().timestamp();
        Instant last = bars.getLast().timestamp();
        long totalDays = java.time.Duration.between(first, last).toDays();
        int minBars = inSampleDays + outOfSampleDays;

        // We need enough data for at least one window
        if (bars.size() < 20 || totalDays < minBars) {
            return new Result(List.of(), 0, 0);
        }

        List<WindowResult> windows = new ArrayList<>();

        // Slide: find bars that fall into each window
        long windowStartEpoch = first.getEpochSecond();
        long windowEndEpoch = last.getEpochSecond();
        long inSampleSecs = (long) inSampleDays * 86400L;
        long outOfSampleSecs = (long) outOfSampleDays * 86400L;
        long slideSecs = (long) slideDays * 86400L;

        long currentStart = windowStartEpoch;
        int windowIdx = 0;

        while (currentStart + inSampleSecs + outOfSampleSecs <= windowEndEpoch) {
            long isStart = currentStart;
            long isEnd = currentStart + inSampleSecs;
            long oosStart = isEnd;
            long oosEnd = oosStart + outOfSampleSecs;

            List<Bar> isBars = filterBars(isStart, isEnd);
            List<Bar> oosBars = filterBars(oosStart, oosEnd);

            if (isBars.size() < 10 || oosBars.size() < 3) {
                currentStart += slideSecs;
                continue;
            }

            try {
                BacktestResult isResult = strategyBuilder.apply(isBars, isBars);
                BacktestResult oosResult = strategyBuilder.apply(isBars, oosBars);

                windows.add(new WindowResult(
                    windowIdx,
                    isResult.totalReturnPct(),
                    oosResult.totalReturnPct(),
                    isResult.sharpeRatio(),
                    oosResult.sharpeRatio(),
                    isResult.maxDrawdownPct(),
                    oosResult.maxDrawdownPct(),
                    isResult.totalTrades(),
                    oosResult.totalTrades(),
                    isBars.size(),
                    oosBars.size()
                ));
            } catch (Exception e) {
                // Skip problematic windows gracefully
            }

            windowIdx++;
            currentStart += slideSecs;
        }

        return new Result(windows, inSampleDays, outOfSampleDays);
    }

    private List<Bar> filterBars(long startEpoch, long endEpoch) {
        return bars.stream()
            .filter(b -> {
                long t = b.timestamp().getEpochSecond();
                return t >= startEpoch && t < endEpoch;
            })
            .toList();
    }

    // ---------------------------------------------------------------
    //  Window result
    // ---------------------------------------------------------------

    /**
     * Metrics for a single IS/OOS window.
     */
    public record WindowResult(
        int windowIndex,
        double isReturnPct,
        double oosReturnPct,
        double isSharpe,
        double oosSharpe,
        double isMaxDD,
        double oosMaxDD,
        int isTrades,
        int oosTrades,
        int isBarCount,
        int oosBarCount
    ) {
        /**
         * Return difference: positive means IS outperformed OOS
         * (possible overfitting).
         */
        public double overfitDelta() {
            return isReturnPct - oosReturnPct;
        }
    }

    // ---------------------------------------------------------------
    //  Aggregated result
    // ---------------------------------------------------------------

    /**
     * Aggregated walk-forward result with per-window details and summary statistics.
     */
    public record Result(
        List<WindowResult> windows,
        int inSampleDays,
        int outOfSampleDays
    ) {

        /** Number of windows that completed successfully. */
        public int windowCount() { return windows.size(); }

        /** Average OOS return across all windows. */
        public double avgOosReturnPct() {
            return windows.isEmpty() ? 0.0
                : windows.stream().mapToDouble(WindowResult::oosReturnPct).average().orElse(0.0);
        }

        /** Average IS return across all windows. */
        public double avgIsReturnPct() {
            return windows.isEmpty() ? 0.0
                : windows.stream().mapToDouble(WindowResult::isReturnPct).average().orElse(0.0);
        }

        /** Average OOS Sharpe ratio. */
        public double avgOosSharpe() {
            return windows.isEmpty() ? 0.0
                : windows.stream().mapToDouble(WindowResult::oosSharpe).average().orElse(0.0);
        }

        /** Average IS Sharpe ratio. */
        public double avgIsSharpe() {
            return windows.isEmpty() ? 0.0
                : windows.stream().mapToDouble(WindowResult::isSharpe).average().orElse(0.0);
        }

        /** Average OOS max drawdown. */
        public double avgOosMaxDD() {
            return windows.isEmpty() ? 0.0
                : windows.stream().mapToDouble(WindowResult::oosMaxDD).average().orElse(0.0);
        }

        /**
         * The Walk-Forward Efficiency — how much of the IS performance
         * is preserved in OOS. Values close to 1 indicate robustness.
         * Formula: avg(OOS Sharpe) / avg(IS Sharpe). Returns 0 if IS Sharpe is 0.
         */
        public double walkForwardEfficiency() {
            double avgIs = avgIsSharpe();
            if (avgIs == 0) return 0.0;
            return avgOosSharpe() / avgIs;
        }

        /** Number of windows where OOS return was positive. */
        public int profitableOosWindows() {
            return (int) windows.stream().filter(w -> w.oosReturnPct() > 0).count();
        }

        /** Percentage of windows with positive OOS return. */
        public double oosWinRate() {
            return windows.isEmpty() ? 0.0
                : (double) profitableOosWindows() / windows.size() * 100;
        }

        /** Returns true if the walk-forward suggests the strategy is robust (heuristic). */
        public boolean isRobust() {
            return windowCount() >= 3
                && avgOosSharpe() > 0.5
                && walkForwardEfficiency() > 0.5
                && oosWinRate() > 50.0;
        }

        /** Prints a formatted summary. */
        public void printSummary() {
            System.out.println("\n================================================");
            System.out.println("  WALK-FORWARD OPTIMISATION");
            System.out.println("================================================");
            System.out.println("Windows:       " + windowCount() + " of " + inSampleDays
                + "d IS / " + outOfSampleDays + "d OOS");
            System.out.println();
            System.out.println("─── IS Averages ────────────────────────────");
            System.out.println("Return:        " + String.format("%.2f", avgIsReturnPct()) + "%");
            System.out.println("Sharpe:        " + String.format("%.2f", avgIsSharpe()));
            System.out.println();
            System.out.println("─── OOS Averages ───────────────────────────");
            System.out.println("Return:        " + String.format("%.2f", avgOosReturnPct()) + "%");
            System.out.println("Sharpe:        " + String.format("%.2f", avgOosSharpe()));
            System.out.println("Max DD:        " + String.format("%.2f", avgOosMaxDD()) + "%");
            System.out.println("Win Rate:      " + String.format("%.1f", oosWinRate()) + "%");
            System.out.println();
            System.out.println("─── Robustness ─────────────────────────────");
            System.out.println("WF Efficiency: " + String.format("%.2f", walkForwardEfficiency()));
            System.out.println("Robust:        " + (isRobust() ? "YES ✅" : "NO ❌"));
            System.out.println("===============================================\n");

            if (!windows.isEmpty()) {
                System.out.println("─── Per-Window Detail ────────────────────");
                System.out.printf("%-8s %-8s %-8s %-8s %-8s %-8s %-8s%n",
                    "Window", "IS Ret%", "OOS Ret%", "IS Sharp", "OOS Sharp", "IS DD%", "OOS DD%");
                for (WindowResult w : windows) {
                    System.out.printf("%-8d %-8.2f %-8.2f %-8.2f %-8.2f %-8.2f %-8.2f%n",
                        w.windowIndex() + 1,
                        w.isReturnPct(), w.oosReturnPct(),
                        w.isSharpe(), w.oosSharpe(),
                        w.isMaxDD(), w.oosMaxDD());
                }
                System.out.println();
            }
        }
    }
}
