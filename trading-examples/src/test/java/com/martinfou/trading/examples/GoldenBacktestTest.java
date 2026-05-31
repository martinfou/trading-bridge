package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.GoldenBacktestBaseline;
import com.martinfou.trading.data.HistoricalDataLoader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.martinfou.trading.core.GoldenBacktestBaseline.CI_SUBSET;
import static com.martinfou.trading.core.GoldenBacktestBaseline.EUR_USD_2012;
import static com.martinfou.trading.core.GoldenBacktestBaseline.INITIAL_CAPITAL;
import static com.martinfou.trading.core.GoldenBacktestBaseline.MAX_DRAWDOWN_TOLERANCE_PCT;
import static com.martinfou.trading.core.GoldenBacktestBaseline.RETURN_TOLERANCE_PCT;
import static com.martinfou.trading.core.GoldenBacktestBaseline.SYMBOL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Golden integration test — detects regressions in data loading, backtest engine,
 * and prop strategy behaviour.
 * <ul>
 *   <li>CI subset ({@code data/ci/EUR_USD_H1_subset.csv}) — always runs in CI</li>
 *   <li>Full year 2012 — optional local validation when {@code data/historical/} present</li>
 * </ul>
 * Baselines: {@link GoldenBacktestBaseline} · documented in {@code docs/testing.md}.
 */
class GoldenBacktestTest {

    static final Path CI_SUBSET_PATH = Path.of("data/ci/EUR_USD_H1_subset.csv");

    @Test
    void londonOpenRangeBreakout_ciSubset_matchesMiniGoldenBaseline() throws IOException {
        assertTrue(Files.isRegularFile(CI_SUBSET_PATH),
            "CI subset required at " + CI_SUBSET_PATH + " — run scripts/generate-ci-golden-subset.sh");

        List<Bar> bars = HistoricalDataLoader.loadPath(CI_SUBSET_PATH, SYMBOL);
        assertFalse(bars.isEmpty(), "Expected bars in CI subset");

        BacktestResult result = RunContexts.backtest(
            GoldenBacktestBaseline.STRATEGY_ID, SYMBOL, bars, INITIAL_CAPITAL).run();

        assertProfile(result, bars.size(), CI_SUBSET);
    }

    @Test
    void londonOpenRangeBreakout_eurUsd2012_matchesGoldenBaseline() throws IOException {
        assumeHistoricalDataPresent();

        List<Bar> bars = HistoricalDataLoader.loadYear(
            SYMBOL, GoldenBacktestBaseline.FULL_YEAR, HistoricalDataLoader.DEFAULT_BARS_DIR);
        assertFalse(bars.isEmpty(), "Expected bars for " + SYMBOL + " " + GoldenBacktestBaseline.FULL_YEAR);

        BacktestResult result = RunContexts.backtest(
            GoldenBacktestBaseline.STRATEGY_ID, SYMBOL, bars, INITIAL_CAPITAL).run();

        assertProfile(result, bars.size(), EUR_USD_2012);
    }

    private static void assertProfile(BacktestResult result, int barCount, GoldenBacktestBaseline.Profile golden) {
        assertEquals(golden.bars(), barCount, "bar count");
        assertEquals(golden.trades(), result.totalTrades(), "trade count");

        double minReturn = golden.returnPct() * (1.0 - RETURN_TOLERANCE_PCT);
        double maxReturn = golden.returnPct() * (1.0 + RETURN_TOLERANCE_PCT);
        assertTrue(result.totalReturnPct() >= minReturn && result.totalReturnPct() <= maxReturn,
            () -> String.format("return %.4f%% outside [%.4f%%, %.4f%%]",
                result.totalReturnPct(), minReturn, maxReturn));

        double pnlTolerance = golden.totalPnl() * RETURN_TOLERANCE_PCT;
        assertEquals(golden.totalPnl(), result.totalPnl(), pnlTolerance, "total PnL");

        assertEquals(golden.maxDrawdownPct(), result.maxDrawdownPct(), MAX_DRAWDOWN_TOLERANCE_PCT,
            "max drawdown %");
    }

    private static void assumeHistoricalDataPresent() {
        Path barsFile = HistoricalDataLoader.DEFAULT_BARS_DIR.resolve(
            SYMBOL + "_H1_" + GoldenBacktestBaseline.FULL_YEAR + ".bars");
        Path csvDir = HistoricalDataLoader.DEFAULT_CSV_DIR;
        boolean hasBars = Files.isRegularFile(barsFile);
        boolean hasCsv = Files.isDirectory(csvDir) && csvDirHasYear(csvDir, GoldenBacktestBaseline.FULL_YEAR);
        assumeTrue(hasBars || hasCsv,
            "Skipping golden backtest: no EUR_USD H1 2012 data under data/historical/. "
                + "Run: ./scripts/download-data.sh --pair EUR_USD --tf h1 --years 2012");
    }

    private static boolean csvDirHasYear(Path csvDir, int year) {
        try (var stream = Files.list(csvDir)) {
            String yearStr = String.valueOf(year);
            return stream.anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".csv") && n.startsWith("eurusd-h1") && n.contains(yearStr);
            });
        } catch (IOException e) {
            return false;
        }
    }
}
