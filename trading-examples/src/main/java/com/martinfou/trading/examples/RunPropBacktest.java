package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.prop.PropStrategyCatalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Backtest runner for prop-firm mechanical strategies.
 *
 * <pre>{@code
 *   mvn exec:java -pl trading-examples \
 *     -Dexec.mainClass="com.martinfou.trading.examples.RunPropBacktest" \
 *     -Dexec.args="LondonOpenRangeBreakout EUR_USD 2012"
 *
 *   mvn exec:java -pl trading-examples \
 *     -Dexec.mainClass="com.martinfou.trading.examples.RunPropBacktest" \
 *     -Dexec.args="LondonOpenRangeBreakout EUR_USD 2006-2012"
 *
 *   mvn exec:java -pl trading-examples \
 *     -Dexec.mainClass="com.martinfou.trading.examples.RunPropBacktest" \
 *     -Dexec.args="ConnorsRsi2 --sample"
 * }</pre>
 */
public class RunPropBacktest {

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("--help")) {
            printUsage();
            return;
        }

        if (args[0].equals("--list")) {
            PropStrategyCatalog.all().keySet().forEach(k ->
                System.out.printf("  %-28s default: %s%n", k, PropStrategyCatalog.defaultSymbol(k)));
            return;
        }

        if (args[0].equals("--all")) {
            runAll(args);
            return;
        }

        runSingle(args);
    }

    private static void runSingle(String[] args) {
        String key = args[0];
        double capital = 100_000;
        List<Bar> bars;
        String symbol;

        try {
            if (args.length >= 2 && args[1].equals("--sample")) {
                symbol = PropStrategyCatalog.defaultSymbol(key);
                bars = generateSample(symbol, 3000);
                System.out.println("Synthetic sample: " + symbol + " (" + bars.size() + " bars)");
            } else if (args.length >= 2) {
                String[] dataArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
                if (args.length >= 4 && !args[args.length - 1].contains(".") && !args[args.length - 1].contains("-")) {
                    try {
                        capital = Double.parseDouble(args[args.length - 1]);
                        dataArgs = java.util.Arrays.copyOfRange(args, 1, args.length - 1);
                    } catch (NumberFormatException ignored) {
                        // last arg is not capital
                    }
                }
                var loaded = HistoricalDataLoader.loadFromArgs(
                    PropStrategyCatalog.defaultSymbol(key), dataArgs);
                bars = loaded.bars();
                symbol = loaded.symbol();
                System.out.println("Loaded: " + loaded.source() + " (" + symbol + ", " + bars.size() + " bars)");
            } else {
                System.err.println("Provide data: SYMBOL YEAR, file path, or --sample");
                printUsage();
                System.exit(1);
                return;
            }
        } catch (Exception e) {
            System.err.println("Failed to load data: " + e.getMessage());
            System.exit(1);
            return;
        }

        if (bars.isEmpty()) {
            System.err.println("No bars loaded. Check path/year — 2024 may not be downloaded yet.");
            System.err.println("Try: ./scripts/download-data.sh --list --tf h1");
            System.exit(1);
        }

        Strategy strategy = PropStrategyCatalog.create(key, symbol);
        runBacktest(strategy, bars, capital);
    }

    private static void runAll(String[] args) {
        double capital = 100_000;
        boolean sample = args.length >= 2 && args[1].equals("--sample");

        System.out.println("=== Prop Strategy Suite Backtest ===\n");
        for (String key : PropStrategyCatalog.all().keySet()) {
            String sym = PropStrategyCatalog.defaultSymbol(key);
            List<Bar> bars;
            try {
                if (sample) {
                    bars = generateSample(sym, 3000);
                } else if (args.length >= 2 && !args[1].startsWith("--")) {
                    var loaded = HistoricalDataLoader.loadFromArgs(sym, java.util.Arrays.copyOfRange(args, 1, args.length));
                    bars = loaded.bars();
                } else {
                    System.err.println("--all requires --sample or SYMBOL YEAR (e.g. EUR_USD 2012)");
                    System.exit(1);
                    return;
                }
            } catch (Exception e) {
                System.err.println("Failed to load data: " + e.getMessage());
                System.exit(1);
                return;
            }

            if (bars.isEmpty()) continue;

            Strategy strategy = PropStrategyCatalog.create(key, sym);
            System.out.printf("--- %s (%s) ---%n", key, sym);
            runBacktest(strategy, bars, capital);
            System.out.println();
        }
    }

    private static void runBacktest(Strategy strategy, List<Bar> bars, double capital) {
        var engine = new BacktestEngine(strategy, bars, capital);
        var result = engine.run();
        result.printSummary();
    }

    private static List<Bar> generateSample(String symbol, int count) {
        var bars = new ArrayList<Bar>(count);
        double price = symbol.contains("JPY") ? 150.0 : 1.08;
        var time = Instant.parse("2024-01-01T00:00:00Z");
        var rand = new Random(42);
        double vol = symbol.contains("JPY") ? 0.15 : 0.002;

        for (int i = 0; i < count; i++) {
            double open = price;
            double close = open + rand.nextGaussian() * vol;
            double high = Math.max(open, close) + Math.abs(rand.nextGaussian()) * vol * 0.5;
            double low = Math.min(open, close) - Math.abs(rand.nextGaussian()) * vol * 0.5;
            bars.add(new Bar(symbol, time, open, high, low, close, 1000 + rand.nextInt(500)));
            time = time.plusSeconds(3600);
            price = close;
        }
        return bars;
    }

    private static void printUsage() {
        System.out.println("""
            Usage:
              RunPropBacktest --list
              RunPropBacktest --all --sample
              RunPropBacktest --all EUR_USD 2012
              RunPropBacktest <strategy> --sample
              RunPropBacktest <strategy> EUR_USD 2012 [capital]
              RunPropBacktest <strategy> EUR_USD 2006-2012
              RunPropBacktest <strategy> data/historical/bars/EUR_USD_H1_2012.bars
              RunPropBacktest <strategy> data/historical/dukascopy/eurusd-h1-bid-2012-01-01-2012-12-31.csv

            Data lives in data/historical/ (see data/README.md).
            Check available years: ./scripts/download-data.sh --list --tf h1

            Strategies:""");
        PropStrategyCatalog.all().keySet().forEach(k -> System.out.println("  " + k));
    }
}
