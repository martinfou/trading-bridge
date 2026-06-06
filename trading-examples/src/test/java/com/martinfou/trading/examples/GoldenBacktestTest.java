package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.GoldenBacktestBaseline;
import com.martinfou.trading.data.HistoricalDataCatalog;
import com.martinfou.trading.data.HistoricalDataLoader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static com.martinfou.trading.core.GoldenBacktestBaseline.CI_SUBSET;
import static com.martinfou.trading.core.GoldenBacktestBaseline.EUR_USD_2012;
import static com.martinfou.trading.core.GoldenBacktestBaseline.FULL_YEAR;
import static com.martinfou.trading.core.GoldenBacktestBaseline.INITIAL_CAPITAL;
import static com.martinfou.trading.core.GoldenBacktestBaseline.STRATEGY_ID;
import static com.martinfou.trading.core.GoldenBacktestBaseline.SYMBOL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Golden integration test — data load, {@code StrategyCatalog} sizing wrapper, engine, LORB prop strategy.
 *
 * <ul>
 *   <li>CI subset — always runs (committed under {@code data/ci/})</li>
 *   <li>Full EUR/USD 2012 — runs when {@code data/historical/} is present locally</li>
 * </ul>
 */
class GoldenBacktestTest {

    static final Path CI_SUBSET_PATH = Path.of("data/ci/EUR_USD_H1_subset.csv");
    private static final Instant CI_SUBSET_START = Instant.parse("2012-01-01T00:00:00Z");
    private static final Instant CI_SUBSET_END = Instant.parse("2012-01-31T23:00:00Z");

    @Test
    void ciSubsetFile_existsAndCoversJanuary2012() throws IOException {
        assertTrue(Files.isRegularFile(CI_SUBSET_PATH),
            "CI subset required at " + CI_SUBSET_PATH + " — run scripts/generate-ci-golden-subset.sh");

        List<Bar> bars = HistoricalDataLoader.loadPath(CI_SUBSET_PATH, SYMBOL);
        assertEquals(CI_SUBSET.bars(), bars.size(), "CI CSV row count");
        assertEquals(SYMBOL, bars.getFirst().symbol(), "bar symbol");
        assertEquals(CI_SUBSET_START, bars.getFirst().timestamp(), "first bar");
        assertEquals(CI_SUBSET_END, bars.getLast().timestamp(), "last bar (January H1)");
    }

    @Test
    void londonOpenRangeBreakout_ciSubset_matchesMiniGoldenBaseline() throws IOException {
        List<Bar> bars = HistoricalDataLoader.loadPath(CI_SUBSET_PATH, SYMBOL);
        BacktestResult result = runGolden(bars);
        assertGolden(result, bars.size(), CI_SUBSET, "CI subset");
    }

    @Test
    @EnabledIf("fullYearDataAvailable")
    void londonOpenRangeBreakout_eurUsd2012_matchesGoldenBaseline() throws IOException {
        var availability = HistoricalDataCatalog.availability(
            SYMBOL, HistoricalDataLoader.DEFAULT_BARS_DIR, HistoricalDataLoader.DEFAULT_CSV_DIR);
        assertTrue(availability.years().contains(FULL_YEAR),
            "Expected " + FULL_YEAR + " in catalog, got years " + availability.years());

        List<Bar> bars = HistoricalDataLoader.loadYear(
            SYMBOL, FULL_YEAR, HistoricalDataLoader.DEFAULT_BARS_DIR);
        assertEquals(EUR_USD_2012.bars(), bars.size(), "bar count for " + FULL_YEAR);

        BacktestResult result = runGolden(bars);
        assertGolden(result, bars.size(), EUR_USD_2012, "EUR_USD H1 " + FULL_YEAR);
    }

    @Test
    void runContexts_usesSameStrategyPathAsGolden() {
        List<Bar> bars = List.of(new Bar(SYMBOL, CI_SUBSET_START, 1.3, 1.31, 1.29, 1.305, 0));
        BacktestResult direct = RunContexts.backtest(STRATEGY_ID, SYMBOL, bars, INITIAL_CAPITAL).run();
        assertEquals(0, direct.totalTrades(), "single warmup bar should not trade");
    }

    static boolean fullYearDataAvailable() {
        try {
            return !HistoricalDataCatalog.availability(
                SYMBOL, HistoricalDataLoader.DEFAULT_BARS_DIR, HistoricalDataLoader.DEFAULT_CSV_DIR)
                .years().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private static BacktestResult runGolden(List<Bar> bars) {
        assertFalse(bars.isEmpty(), "bars must not be empty");
        assertEquals(SYMBOL, bars.getFirst().symbol(),
            "bar symbol must match strategy symbol or every onBar is skipped");
        return RunContexts.backtest(STRATEGY_ID, SYMBOL, bars, INITIAL_CAPITAL).run();
    }

    private static void assertGolden(
        BacktestResult result,
        int barCount,
        GoldenBacktestBaseline.Profile expected,
        String label
    ) {
        var snapshot = new GoldenBacktestBaseline.MetricSnapshot(
            barCount,
            result.totalTrades(),
            result.totalReturnPct(),
            result.totalPnl(),
            result.maxDrawdownPct(),
            result.finalEquity());
        List<String> mismatches = GoldenBacktestBaseline.metricMismatches(snapshot, expected, INITIAL_CAPITAL);
        if (!mismatches.isEmpty()) {
            fail(label + " golden mismatch:\n  - " + String.join("\n  - ", mismatches)
                + "\nRe-capture: mvn -q test-compile exec:java -pl trading-examples"
                + " -Dexec.classpathScope=test"
                + " -Dexec.mainClass=com.martinfou.trading.examples.GoldenBaselineCapture");
        }
        assertTrue(result.totalTrades() > 0, label + " should produce at least one trade");
    }
}
