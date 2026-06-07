package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.HistoricalDataCatalog;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public class RunAllBatchBacktests {

    private static final double CAPITAL = 1_000.0;

    static {
        // Register SmaCrossover example strategy so it is included in the runs
        try {
            StrategyCatalog.register("SmaCrossover", StrategyCatalog.Family.EXAMPLE,
                sym -> new SmaCrossoverStrategy("SMA 20/50", sym, 20, 50),
                "EUR_USD");
        } catch (Exception e) {
            // ignore if already registered
        }
    }

    public static void main(String[] args) {
        System.out.println("======================================================================");
        System.out.println("  RUNNING BATCH BACKTESTS ON ALL STRATEGIES & ALL PAIRS");
        System.out.println("======================================================================");

        Path barsDir = Path.of("data/historical/bars");
        Path csvDir = Path.of("data/historical/dukascopy");
        if (!barsDir.toFile().exists()) {
            barsDir = Path.of(System.getProperty("user.dir"), "data/historical/bars");
        }
        if (!csvDir.toFile().exists()) {
            csvDir = Path.of(System.getProperty("user.dir"), "data/historical/dukascopy");
        }

        List<String> symbols;
        try {
            symbols = HistoricalDataCatalog.listSymbols(barsDir, csvDir);
        } catch (IOException e) {
            System.err.println("Failed to list symbols from data directory: " + e.getMessage());
            System.exit(1);
            return;
        }

        if (symbols.isEmpty()) {
            System.err.println("No symbols found in " + barsDir + " or " + csvDir);
            System.exit(1);
            return;
        }

        List<StrategyCatalog.Entry> strategyEntries = StrategyCatalog.entries();
        if (strategyEntries.isEmpty()) {
            System.err.println("No strategies found in StrategyCatalog!");
            System.exit(1);
            return;
        }

        System.out.println("Available Pairs: " + String.join(", ", symbols));
        System.out.println("Available Strategies (" + strategyEntries.size() + "):");
        for (var entry : strategyEntries) {
            System.out.println("  - " + entry.id() + " (" + entry.family() + ")");
        }
        System.out.println("======================================================================\n");

        record ProfitResult(String strategyId, String symbol, BacktestResult result) {}
        List<ProfitResult> profitableRuns = new ArrayList<>();

        int totalRuns = 0;
        int successfulRuns = 0;
        int failedRuns = 0;

        for (String symbol : symbols) {
            System.out.println("-------------------------------------------------- Portfolio: " + symbol);
            List<Bar> bars;
            try {
                var loaded = HistoricalDataLoader.loadAllAvailable(symbol, barsDir, csvDir);
                bars = loaded.bars();
                System.out.printf("Loaded %,d bars from: %s%n", bars.size(), loaded.source());
            } catch (Exception e) {
                System.out.println("  ⚠️ Skipping " + symbol + " — failed to load: " + e.getMessage());
                continue;
            }

            if (bars.isEmpty()) {
                System.out.println("  ⚠️ Skipping " + symbol + " — loaded 0 bars.");
                continue;
            }

            for (StrategyCatalog.Entry entry : strategyEntries) {
                totalRuns++;
                String strategyId = entry.id();
                
                try {
                    Strategy strategy = StrategyCatalog.create(strategyId, symbol);
                    RunContext context = RunContext.forStrategy(strategyId, strategy, symbol, RunMode.BACKTEST, bars, CAPITAL, null);
                    BacktestResult result = context.run();
                    
                    successfulRuns++;
                    double returnPct = result.totalReturnPct();
                    double pnl = result.totalPnl();
                    int trades = result.totalTrades();
                    
                    if (pnl > 0) {
                        profitableRuns.add(new ProfitResult(strategyId, symbol, result));
                        System.out.printf("  ✅ %-32s -> Profit: +$%,.2f (%+.2f%%) | Trades: %d | Sharpe: %.2f | DD: %.2f%%%n",
                            strategyId, symbol, pnl, returnPct, trades, result.sharpeRatio(), result.maxDrawdownPct());
                    } else {
                        System.out.printf("  ❌ %-32s -> Loss  : -$%,.2f (%+.2f%%) | Trades: %d | Sharpe: %.2f | DD: %.2f%%%n",
                            strategyId, symbol, Math.abs(pnl), returnPct, trades, result.sharpeRatio(), result.maxDrawdownPct());
                    }
                } catch (Exception | LinkageError e) {
                    failedRuns++;
                    System.out.printf("  ⚠️  %-32s -> FAILED to run: %s%n", strategyId, e.getMessage());
                }
            }
            System.out.println();
        }

        System.out.println("======================================================================");
        System.out.println("  FINAL SUMMARY: ALL PROFITABLE STRATEGIES & PAIRS");
        System.out.println("======================================================================");
        System.out.printf("Total backtests executed: %d (Successful: %d, Failed: %d)%n", totalRuns, successfulRuns, failedRuns);
        System.out.printf("Initial Capital per backtest: $%,.2f%n", CAPITAL);
        System.out.println("Position Sizing: 0.01 lots (1,000 units) fixed");
        System.out.println("Backtest Periods:");
        System.out.println("  - EUR_USD: 2023 - 2026 (4 years)");
        System.out.println("  - GBP_USD: 2010 - 2026 (17 years)");
        System.out.println("  - GBP_JPY: 2010 - 2026 (17 years)");
        System.out.println("  - USD_JPY: 2025 - 2026 (2 years)");
        System.out.println("  - EURJPY:  2026 (1 year)");
        System.out.printf("Profitable combinations found: %d%n%n", profitableRuns.size());

        if (profitableRuns.isEmpty()) {
            System.out.println("No profitable strategies were found on any pair.");
        } else {
            // Sort by absolute return percentage descending
            profitableRuns.sort((a, b) -> Double.compare(b.result().totalReturnPct(), a.result().totalReturnPct()));

            System.out.printf("%-32s %-12s %12s %10s %8s %8s %8s%n", 
                "STRATEGY", "PAIR", "NET PROFIT", "RETURN %", "TRADES", "SHARPE", "MAX DD%");
            System.out.println("-".repeat(95));
            for (ProfitResult pr : profitableRuns) {
                BacktestResult res = pr.result();
                System.out.printf("%-32s %-12s $%-11.2f %+.2f%% %8d %8.2f %7.2f%%%n",
                    pr.strategyId(),
                    pr.symbol(),
                    res.totalPnl(),
                    res.totalReturnPct(),
                    res.totalTrades(),
                    res.sharpeRatio(),
                    res.maxDrawdownPct()
                );
            }
            System.out.println("-".repeat(95));
            
            // Also write output to a file in batch-results/profitable_report.txt
            try {
                Path reportDir = Path.of("batch-results");
                if (!Files.exists(reportDir)) {
                    Files.createDirectories(reportDir);
                }
                Path reportPath = reportDir.resolve("profitable_report.txt");
                List<String> lines = new ArrayList<>();
                lines.add("======================================================================");
                lines.add("  BATCH BACKTEST REPORT: PROFITABLE STRATEGIES");
                lines.add("  Generated on: " + Instant.now());
                lines.add("  Initial Capital: " + String.format("$%,.2f", CAPITAL));
                lines.add("  Position Sizing: 0.01 lots (1,000 units) fixed");
                lines.add("  Backtest Periods:");
                lines.add("    - EUR_USD: 2023 - 2026 (4 years)");
                lines.add("    - GBP_USD: 2010 - 2026 (17 years)");
                lines.add("    - GBP_JPY: 2010 - 2026 (17 years)");
                lines.add("    - USD_JPY: 2025 - 2026 (2 years)");
                lines.add("    - EURJPY:  2026 (1 year)");
                lines.add("======================================================================");
                lines.add(String.format("Total backtests executed: %d (Successful: %d, Failed: %d)", totalRuns, successfulRuns, failedRuns));
                lines.add(String.format("Profitable combinations: %d", profitableRuns.size()));
                lines.add("");
                lines.add(String.format("%-32s %-12s %12s %10s %8s %8s %8s", 
                    "STRATEGY", "PAIR", "NET PROFIT", "RETURN %", "TRADES", "SHARPE", "MAX DD%"));
                lines.add("-".repeat(95));
                for (ProfitResult pr : profitableRuns) {
                    BacktestResult res = pr.result();
                    lines.add(String.format("%-32s %-12s $%-11.2f %+.2f%% %8d %8.2f %7.2f%%",
                        pr.strategyId(),
                        pr.symbol(),
                        res.totalPnl(),
                        res.totalReturnPct(),
                        res.totalTrades(),
                        res.sharpeRatio(),
                        res.maxDrawdownPct()
                    ));
                }
                lines.add("-".repeat(95));
                Files.write(reportPath, lines);
                System.out.println("\nReport saved successfully to " + reportPath.toAbsolutePath());
            } catch (IOException e) {
                System.err.println("Failed to write report to file: " + e.getMessage());
            }
        }
    }
}
