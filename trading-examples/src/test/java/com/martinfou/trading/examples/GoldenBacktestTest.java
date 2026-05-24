package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.StrategyCatalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Golden integration test — detects regressions in data loading, backtest engine,
 * and prop strategy behaviour. Requires local {@code data/historical/} files.
 * Baseline documented in {@code docs/testing.md}.
 */
class GoldenBacktestTest {

    /** Baseline captured 2026-05-23; commit {@value #BASELINE_COMMIT}. */
    static final String BASELINE_COMMIT = "ec6dc72";

    static final String SYMBOL = "EUR_USD";
    static final int YEAR = 2012;
    static final double INITIAL_CAPITAL = 100_000.0;

    static final int BASELINE_BARS = 8760;
    static final int BASELINE_TRADES = 63;
    static final double BASELINE_RETURN_PCT = 16.44;
    static final double BASELINE_TOTAL_PNL = 16_439.51;
    static final double BASELINE_MAX_DRAWDOWN_PCT = 0.12;
    static final double RETURN_TOLERANCE_PCT = 0.01; // ±1% of baseline return

    @Test
    void londonOpenRangeBreakout_eurUsd2012_matchesGoldenBaseline() throws IOException {
        assumeHistoricalDataPresent();

        List<Bar> bars = HistoricalDataLoader.loadYear(
            SYMBOL, YEAR, HistoricalDataLoader.DEFAULT_BARS_DIR);
        assertFalse(bars.isEmpty(), "Expected bars for " + SYMBOL + " " + YEAR);

        BacktestResult result = RunContexts.backtest("LondonOpenRangeBreakout", SYMBOL, bars, INITIAL_CAPITAL).run();

        assertEquals(BASELINE_BARS, bars.size(), "bar count");
        assertEquals(BASELINE_TRADES, result.totalTrades(), "trade count");

        double minReturn = BASELINE_RETURN_PCT * (1.0 - RETURN_TOLERANCE_PCT);
        double maxReturn = BASELINE_RETURN_PCT * (1.0 + RETURN_TOLERANCE_PCT);
        assertTrue(result.totalReturnPct() >= minReturn && result.totalReturnPct() <= maxReturn,
            () -> String.format("return %.4f%% outside [%.4f%%, %.4f%%]",
                result.totalReturnPct(), minReturn, maxReturn));

        double pnlTolerance = BASELINE_TOTAL_PNL * RETURN_TOLERANCE_PCT;
        assertEquals(BASELINE_TOTAL_PNL, result.totalPnl(), pnlTolerance, "total PnL");

        assertEquals(BASELINE_MAX_DRAWDOWN_PCT, result.maxDrawdownPct(), 0.01, "max drawdown %");
    }

    private static void assumeHistoricalDataPresent() {
        Path barsFile = HistoricalDataLoader.DEFAULT_BARS_DIR.resolve(SYMBOL + "_H1_" + YEAR + ".bars");
        Path csvDir = HistoricalDataLoader.DEFAULT_CSV_DIR;
        boolean hasBars = Files.isRegularFile(barsFile);
        boolean hasCsv = Files.isDirectory(csvDir) && csvDirHasYear(csvDir, YEAR);
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
