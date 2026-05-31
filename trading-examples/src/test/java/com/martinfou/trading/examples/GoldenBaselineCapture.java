package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.GoldenBacktestBaseline;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.nio.file.Path;
import java.util.List;

/**
 * Dev utility — run main to print full-precision metrics and compare to {@link GoldenBacktestBaseline}.
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
        capture("CI subset", Path.of("data/ci/EUR_USD_H1_subset.csv"), GoldenBacktestBaseline.CI_SUBSET);
        try {
            List<Bar> year = HistoricalDataLoader.loadYear(
                GoldenBacktestBaseline.SYMBOL,
                GoldenBacktestBaseline.FULL_YEAR,
                HistoricalDataLoader.DEFAULT_BARS_DIR);
            if (!year.isEmpty()) {
                captureBars("Full 2012", year, GoldenBacktestBaseline.EUR_USD_2012);
            }
        } catch (Exception ignored) {
            System.err.println("Skipping full year — no historical data");
        }
    }

    private static void capture(String label, Path path, GoldenBacktestBaseline.Profile golden) throws Exception {
        List<Bar> bars = HistoricalDataLoader.loadPath(path, GoldenBacktestBaseline.SYMBOL);
        captureBars(label, bars, golden);
    }

    private static void captureBars(String label, List<Bar> bars, GoldenBacktestBaseline.Profile golden) {
        BacktestResult r = RunContexts.backtest(
            GoldenBacktestBaseline.STRATEGY_ID,
            GoldenBacktestBaseline.SYMBOL,
            bars,
            GoldenBacktestBaseline.INITIAL_CAPITAL).run();
        System.out.printf("%s:%n  bars=%d trades=%d returnPct=%.16f pnl=%.16f maxDdPct=%.16f%n",
            label, bars.size(), r.totalTrades(), r.totalReturnPct(), r.totalPnl(), r.maxDrawdownPct());
        if (bars.size() != golden.bars() || r.totalTrades() != golden.trades()) {
            System.out.println("  WARNING: differs from GoldenBacktestBaseline — update trading-core constants");
        }
    }
}
