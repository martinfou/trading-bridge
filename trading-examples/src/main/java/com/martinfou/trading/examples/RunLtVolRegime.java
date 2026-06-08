package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.LotSizing;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.StrategyCatalog;
import com.martinfou.trading.strategies.StrategyCatalog.Family;
import com.martinfou.trading.strategies.longterm.LtVolRegime;

import java.io.IOException;
import java.util.List;

/**
 * Backtest runner for LtVolRegime (Volatility Regime Trading, Strategy #7).
 *
 * Backtest periods:
 *   EUR_USD: Full (2006-2026), IS (2015-2019), OOS1 (2020-2022), OOS2 (2023-2025)
 *   GBP_USD: Full (2006-2026)
 */
public class RunLtVolRegime {

    static {
        StrategyCatalog.register("LtVolRegime", Family.EXAMPLE,
            sym -> new LtVolRegime("LtVolRegime", sym),
            "EUR_USD");
    }

    public static void main(String[] args) throws IOException {
        double capital = LotSizing.DEFAULT_STARTING_CAPITAL;
        System.out.println("=== LtVolRegime - Volatility Regime Trading (Strategy #7) ===");
        System.out.println("Capital: $" + String.format("%,.0f", capital));
        System.out.println();

        // EUR_USD Full history
        System.out.println("--- EUR_USD Full (2006-2026) ---");
        var eurFull = HistoricalDataLoader.loadAllAvailable("EUR_USD");
        runAndPrint(eurFull.bars(), eurFull.symbol(), capital);

        // EUR_USD IS (2015-2019)
        System.out.println("--- EUR_USD IS (2015-2019) ---");
        var eurIs = HistoricalDataLoader.loadYearRange("EUR_USD", 2015, 2019, "H1",
            HistoricalDataLoader.DEFAULT_BARS_DIR);
        runAndPrint(eurIs, "EUR_USD", capital);

        // EUR_USD OOS1 (2020-2022)
        System.out.println("--- EUR_USD OOS1 (2020-2022) ---");
        var eurOos1 = HistoricalDataLoader.loadYearRange("EUR_USD", 2020, 2022, "H1",
            HistoricalDataLoader.DEFAULT_BARS_DIR);
        runAndPrint(eurOos1, "EUR_USD", capital);

        // EUR_USD OOS2 (2023-2025)
        System.out.println("--- EUR_USD OOS2 (2023-2025) ---");
        var eurOos2 = HistoricalDataLoader.loadYearRange("EUR_USD", 2023, 2025, "H1",
            HistoricalDataLoader.DEFAULT_BARS_DIR);
        runAndPrint(eurOos2, "EUR_USD", capital);

        // GBP_USD Full history
        System.out.println("--- GBP_USD Full (2006-2026) ---");
        var gbpFull = HistoricalDataLoader.loadAllAvailable("GBP_USD");
        runAndPrint(gbpFull.bars(), gbpFull.symbol(), capital);
    }

    private static void runAndPrint(List<Bar> bars, String symbol, double capital) {
        if (bars.isEmpty()) {
            System.out.println("  No bars loaded for " + symbol);
            return;
        }
        var strategy = new LtVolRegime("LtVolRegime", symbol);
        var result = RunContext.forStrategy(strategy, symbol, RunMode.BACKTEST, bars, capital).run();
        result.printSummary();
        System.out.println();
    }
}
