package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.sqimported.Strategy_2_38_112_Converted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression: converted SQ strategy must trade on the run symbol with correct pip sizing. */
class Strategy_2_38_112_ConvertedBacktestTest {

    static boolean hasGbpUsd2012() {
        return Files.isRegularFile(Path.of("data/historical/bars/GBP_USD_H1_2012.bars"));
    }

    @Test
    @EnabledIf("hasGbpUsd2012")
    void producesMultipleTradesOnGbpUsd() throws IOException {
        List<Bar> bars = HistoricalDataLoader.loadYearSpec(
            "GBP_USD", "2012", HistoricalDataLoader.DEFAULT_BARS_DIR);
        BacktestResult result = new BacktestEngine(
            new Strategy_2_38_112_Converted(), bars, 100_000.0).run();
        assertTrue(
            result.totalTrades() > 1,
            "expected more than one round-trip; got " + result.totalTrades());
    }
}
