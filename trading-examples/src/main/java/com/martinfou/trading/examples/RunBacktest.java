package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.report.BacktestJsonExporter;
import com.martinfou.trading.backtest.report.HtmlReportGenerator;
import com.martinfou.trading.core.Bar;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.*;

public class RunBacktest {
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║   Trading Bridge - Backtest Runner   ║");
        System.out.println("╚══════════════════════════════════════╝");

        // Parse flags
        boolean jsonMode = false;
        boolean htmlMode = false;
        String outputDir = null;
        List<String> positional = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--sample" -> {
                    runSampleBacktest();
                    return;
                }
                case "--json" -> jsonMode = true;
                case "--html" -> htmlMode = true;
                case "--output-dir" -> {
                    if (i + 1 < args.length) outputDir = args[++i];
                }
                default -> positional.add(args[i]);
            }
        }

        // If no positional args but flags set, run sample with export
        if (positional.isEmpty()) {
            if (jsonMode || htmlMode) {
                runSampleBacktestWithExport(jsonMode, htmlMode, outputDir);
            } else {
                printUsage();
            }
            return;
        }

        String file = positional.get(0);
        String symbol = positional.size() > 1 ? positional.get(1) : "SYMBOL";

        System.out.println("\nLoading data: " + file);
        var bars = com.martinfou.trading.core.DataLoader.loadStrategyQuantCSV(Paths.get(file), symbol);
        System.out.println("Loaded " + bars.size() + " bars");

        if (bars.isEmpty()) {
            System.out.println("No data loaded. Check file format.");
            return;
        }

        var strategy = new SmaCrossoverStrategy("SMA_20_50", symbol, 20, 50);
        System.out.println("Strategy: " + strategy.name());

        var engine = new BacktestEngine(strategy, bars, 10000);
        var result = engine.run();
        result.printSummary();

        // Export
        if (jsonMode || htmlMode) {
            exportResult(result, jsonMode, htmlMode, outputDir);
        }
    }

    static void runSampleBacktest() {
        System.out.println("\n--- Running sample backtest with generated data ---\n");
        var bars = generateSampleData("EURUSD", 500);
        var strategy = new SmaCrossoverStrategy("SMA_20_50_EURUSD", "EURUSD", 20, 50);
        var engine = new BacktestEngine(strategy, bars, 10000);
        var result = engine.run();
        result.printSummary();
    }

    static void runSampleBacktestWithExport(boolean json, boolean html, String outputDir) {
        System.out.println("\n--- Running sample backtest with export ---\n");
        var bars = generateSampleData("EURUSD", 500);
        var strategy = new SmaCrossoverStrategy("SMA_20_50_EURUSD", "EURUSD", 20, 50);
        var engine = new BacktestEngine(strategy, bars, 10000);
        var result = engine.run();
        result.printSummary();
        exportResult(result, json, html, outputDir);
    }

    private static void exportResult(BacktestResult result, boolean json, boolean html, String outputDir) {
        Path outDir = outputDir != null
            ? Paths.get(outputDir)
            : Paths.get("_bmad-output/implementation-artifacts/backtest-reports");

        try {
            java.nio.file.Files.createDirectories(outDir);
            String strategyKey = result.strategyName().replaceAll("[^a-zA-Z0-9_]", "_");

            if (json) {
                Path jsonPath = outDir.resolve(strategyKey + "_report.json");
                new BacktestJsonExporter(result).writeJson(jsonPath);
                System.out.println("\n✅ JSON report: " + jsonPath.toAbsolutePath());
            }

            if (html) {
                Path htmlPath = outDir.resolve(strategyKey + "_report.html");
                new HtmlReportGenerator(result).generate(htmlPath);
                System.out.println("✅ HTML report: " + htmlPath.toAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("❌ Export failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println("""

Usage: RunBacktest [options] <csv-file> [symbol]
   or: RunBacktest --sample
   or: RunBacktest --json [--html] [--output-dir <dir>] <csv-file> [symbol]

Flags:
  --sample           Run with generated sample data
  --json             Export result as JSON (dashboard-compatible)
  --html             Export result as HTML report
  --output-dir <dir> Output directory for exported files

CSV format: Date,Time,Open,High,Low,Close,Volume
Examples:
  RunBacktest --sample
  RunBacktest --json --html data/EURUSD_H1.csv EURUSD
  RunBacktest --json --output-dir /tmp/reports data/GBPUSD_H1.csv GBPUSD
""");
    }

    static List<Bar> generateSampleData(String symbol, int count) {
        var bars = new ArrayList<Bar>(count);
        double price = 1.08000;
        var time = java.time.Instant.parse("2024-01-01T00:00:00Z");
        var rand = new Random(42);

        for (int i = 0; i < count; i++) {
            double change = (rand.nextGaussian() * 0.002);
            price += change;
            double open = price;
            double high = open + Math.abs(rand.nextGaussian() * 0.001);
            double low = open - Math.abs(rand.nextGaussian() * 0.001);
            double close = open + (rand.nextGaussian() * 0.0005);
            bars.add(new Bar(symbol, time, open, high, low, close, (long) (rand.nextInt(1000) + 100)));
            time = time.plusSeconds(3600);
        }
        return bars;
    }
}
