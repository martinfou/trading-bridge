package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import java.nio.file.Paths;
import java.util.*;

public class RunBacktest {
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║   Trading Bridge - Backtest Runner   ║");
        System.out.println("╚══════════════════════════════════════╝");

        if (args.length > 0 && args[0].equals("--sample")) {
            runSampleBacktest();
            return;
        }

        if (args.length < 1) {
            System.out.println("\nUsage: RunBacktest <csv-file> [symbol]");
            System.out.println("   or: RunBacktest --sample");
            System.out.println("\nCSV format: Date,Time,Open,High,Low,Close,Volume");
            return;
        }

        String file = args[0];
        String symbol = args.length > 1 ? args[1] : "SYMBOL";
        
        System.out.println("\nLoading data: " + file);
        var bars = com.martinfou.trading.core.DataLoader.loadStrategyQuantCSV(Paths.get(file), symbol);
        System.out.println("Loaded " + bars.size() + " bars");

        if (bars.isEmpty()) {
            System.out.println("No data loaded. Check file format.");
            return;
        }

        var strategy = new SmaCrossoverStrategy("SMA Crossover 20/50", symbol, 20, 50);
        var engine = new BacktestEngine(strategy, bars, 10000);
        var result = engine.run();
        result.printSummary();
    }

    static void runSampleBacktest() {
        System.out.println("\n--- Running sample backtest with generated data ---\n");
        var bars = generateSampleData("EURUSD", 500);
        var strategy = new SmaCrossoverStrategy("SMA 20/50 - EURUSD", "EURUSD", 20, 50);
        var engine = new BacktestEngine(strategy, bars, 10000);
        var result = engine.run();
        result.printSummary();
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
