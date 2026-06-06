package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.GoldenBacktestBaseline;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.nio.file.Path;
import java.util.List;

/**
 * Dev utility — run main to print metrics and fail if they diverge from {@link GoldenBacktestBaseline}.
 *
 * <pre>{@code
 * mvn -q test-compile exec:java -pl trading-examples \
 *   -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.martinfou.trading.examples.GoldenBaselineCapture
 * }</pre>
 */
public final class GoldenBaselineCapture {

    private GoldenBaselineCapture() {}

    public static void main(String[] args) throws Exception {
        int failures = 0;
        failures += capture("CI subset", Path.of("data/ci/EUR_USD_H1_subset.csv"), GoldenBacktestBaseline.CI_SUBSET);
        try {
            List<Bar> year = HistoricalDataLoader.loadYear(
                GoldenBacktestBaseline.SYMBOL,
                GoldenBacktestBaseline.FULL_YEAR,
                HistoricalDataLoader.DEFAULT_BARS_DIR);
            if (!year.isEmpty()) {
                failures += captureBars("Full " + GoldenBacktestBaseline.FULL_YEAR, year,
                    GoldenBacktestBaseline.EUR_USD_2012);
            }
        } catch (Exception ex) {
            System.err.println("Skipping full year — " + ex.getMessage());
        }
        if (failures > 0) {
            System.err.println(failures + " profile(s) differ — update GoldenBacktestBaseline.java and docs/testing.md");
            System.exit(1);
        }
        System.out.println("All profiles match GoldenBacktestBaseline.");
    }

    private static int capture(String label, Path path, GoldenBacktestBaseline.Profile golden) throws Exception {
        List<Bar> bars = HistoricalDataLoader.loadPath(path, GoldenBacktestBaseline.SYMBOL);
        return captureBars(label, bars, golden);
    }

    private static int captureBars(String label, List<Bar> bars, GoldenBacktestBaseline.Profile golden) {
        BacktestResult r = RunContexts.backtest(
            GoldenBacktestBaseline.STRATEGY_ID,
            GoldenBacktestBaseline.SYMBOL,
            bars,
            GoldenBacktestBaseline.INITIAL_CAPITAL).run();
        System.out.printf("%s:%n  bars=%d trades=%d returnPct=%.16f pnl=%.16f maxDdPct=%.16f finalEquity=%.16f%n",
            label, bars.size(), r.totalTrades(), r.totalReturnPct(), r.totalPnl(), r.maxDrawdownPct(),
            r.finalEquity());

        var snapshot = new GoldenBacktestBaseline.MetricSnapshot(
            bars.size(), r.totalTrades(), r.totalReturnPct(), r.totalPnl(), r.maxDrawdownPct(), r.finalEquity());
        List<String> mismatches = GoldenBacktestBaseline.metricMismatches(
            snapshot, golden, GoldenBacktestBaseline.INITIAL_CAPITAL);
        if (!mismatches.isEmpty()) {
            System.out.println("  DRIFT:");
            mismatches.forEach(m -> System.out.println("    - " + m));
            System.out.printf(
                "  Java constants to paste if intentional:%n    new Profile(%d, %d, %.16f, %.16f, %.16f)%n",
                bars.size(), r.totalTrades(), r.totalReturnPct(), r.totalPnl(), r.maxDrawdownPct());
            return 1;
        }
        return 0;
    }
}
