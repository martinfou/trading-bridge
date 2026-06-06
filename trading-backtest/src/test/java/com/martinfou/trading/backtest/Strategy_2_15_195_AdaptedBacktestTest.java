package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.StrategyCatalog;
import com.martinfou.trading.strategies.sqimported.Strategy_2_15_195_Adapted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression: trailing logic must not pyramid position size via duplicate stop entries. */
class Strategy_2_15_195_AdaptedBacktestTest {

    static boolean hasGbpJpy2012() {
        return Files.isRegularFile(Path.of("data/historical/bars/GBP_JPY_H1_2012.bars"))
            || Files.isRegularFile(Path.of("data/historical/dukascopy/gbpjpy-h1-bid-2012-01-01-2012-12-31.csv"));
    }

    @Test
    @EnabledIf("hasGbpJpy2012")
    void singleYearReturnIsNotPyramided() throws IOException {
        List<Bar> bars = HistoricalDataLoader.loadYearSpec(
            "GBP_JPY", "2012", HistoricalDataLoader.DEFAULT_BARS_DIR);
        BacktestResult raw = new BacktestEngine(
            new Strategy_2_15_195_Adapted(), bars, 100_000.0).run();
        assertTrue(
            raw.totalReturnPct() < 200.0,
            "return looks pyramided: " + raw.totalReturnPct() + "% with "
                + raw.totalTrades() + " trades");

        BacktestResult wrapped = new BacktestEngine(
            StrategyCatalog.create("Strategy_2_15_195_Adapted", "GBP_JPY"), bars, 2_000.0).run();
        assertTrue(
            wrapped.totalReturnPct() < 500.0,
            "wrapped return looks pyramided: " + wrapped.totalReturnPct() + "% with "
                + wrapped.totalTrades() + " trades");
        assertTrue(wrapped.totalTrades() > 1, "expected multiple round trips, got " + wrapped.totalTrades());
    }
}
