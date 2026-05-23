package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.sqimported.Strategy_2_14_147_Adapted;
import com.martinfou.trading.strategies.sqimported.Strategy_2_15_195_Adapted;
import com.martinfou.trading.strategies.sqimported.Strategy_2_31_175_Converted;
import com.martinfou.trading.strategies.sqimported.Strategy_2_31_177_Converted;
import com.martinfou.trading.strategies.sqimported.Strategy_2_32_120_Converted;
import com.martinfou.trading.strategies.sqimported.Strategy_2_36_190_Converted;
import com.martinfou.trading.strategies.sqimported.Strategy_2_38_112_Converted;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Backtest runner for StrategyQuant-imported strategies in {@code trading-strategies}.
 *
 * <pre>{@code
 *   # Strategy_2_14_147_Adapted on Dukascopy CSV (StrategyQuant format)
 *   mvn exec:java -pl trading-examples \
 *     -Dexec.mainClass="com.martinfou.trading.examples.RunSqBacktest" \
 *     -Dexec.args="Strategy_2_14_147_Adapted GBP_JPY 2012"
 *
 *   # Quick smoke test with synthetic GBP/JPY H1 bars
 *   mvn exec:java -pl trading-examples \
 *     -Dexec.mainClass="com.martinfou.trading.examples.RunSqBacktest" \
 *     -Dexec.args="Strategy_2_14_147_Adapted --sample"
 * }</pre>
 */
public class RunSqBacktest {

    private static final Map<String, StrategyFactory> STRATEGIES = new LinkedHashMap<>();

    static {
        register("Strategy_2_14_147_Adapted", Strategy_2_14_147_Adapted::new);
        register("Strategy_2_15_195_Adapted", Strategy_2_15_195_Adapted::new);
        register("Strategy_2_31_175_Converted", Strategy_2_31_175_Converted::new);
        register("Strategy_2_31_177_Converted", Strategy_2_31_177_Converted::new);
        register("Strategy_2_32_120_Converted", Strategy_2_32_120_Converted::new);
        register("Strategy_2_36_190_Converted", Strategy_2_36_190_Converted::new);
        register("Strategy_2_38_112_Converted", Strategy_2_38_112_Converted::new);
    }

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("--help")) {
            printUsage();
            return;
        }

        String strategyName = args[0];
        StrategyFactory factory = STRATEGIES.get(strategyName);
        if (factory == null) {
            System.err.println("Unknown strategy: " + strategyName);
            printUsage();
            System.exit(1);
        }

        double capital = 100_000;
        List<Bar> bars = List.of();

        if (args.length >= 2 && args[1].equals("--sample")) {
            bars = generateGbpJpySample(2000);
            System.out.println("Using synthetic GBP/JPY H1 sample (" + bars.size() + " bars)");
        } else if (args.length >= 2) {
            try {
                String[] dataArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
                if (args.length >= 4) {
                    try {
                        capital = Double.parseDouble(args[args.length - 1]);
                        dataArgs = java.util.Arrays.copyOfRange(args, 1, args.length - 1);
                    } catch (NumberFormatException ignored) {}
                }
                var loaded = HistoricalDataLoader.loadFromArgs(inferSymbol(strategyName), dataArgs);
                bars = loaded.bars();
                System.out.println("Loaded: " + loaded.source() + " (" + loaded.symbol() + ", " + bars.size() + " bars)");
            } catch (Exception e) {
                System.err.println("Failed to load data: " + e.getMessage());
                System.exit(1);
            }
        } else {
            System.err.println("Missing CSV path or --sample");
            printUsage();
            System.exit(1);
        }

        if (bars.isEmpty()) {
            System.err.println("No bars loaded. Check path/year — see ./scripts/download-data.sh --list --tf h1");
            System.exit(1);
        }

        Strategy strategy = factory.create();
        System.out.println("Strategy: " + strategy.name());
        System.out.println("Bars:     " + bars.size()
            + " (" + bars.getFirst().timestamp() + " → " + bars.getLast().timestamp() + ")");
        System.out.println("Capital:  $" + String.format("%,.0f", capital));
        System.out.println();

        var engine = new BacktestEngine(strategy, bars, capital);
        var result = engine.run();
        result.printSummary();
    }

    private static String inferSymbol(String strategyName) {
        return switch (strategyName) {
            case "Strategy_2_14_147_Adapted",
                 "Strategy_2_15_195_Adapted",
                 "Strategy_2_31_175_Converted",
                 "Strategy_2_31_177_Converted",
                 "Strategy_2_32_120_Converted",
                 "Strategy_2_36_190_Converted",
                 "Strategy_2_38_112_Converted" -> "GBP_JPY";
            default -> "EUR_USD";
        };
    }

    private static List<Bar> generateGbpJpySample(int count) {
        var bars = new ArrayList<Bar>(count);
        double price = 185.0;
        var time = Instant.parse("2003-01-01T00:00:00Z");
        var rand = new Random(147);

        for (int i = 0; i < count; i++) {
            double change = rand.nextGaussian() * 0.15;
            price = Math.max(130.0, Math.min(250.0, price + change));
            double open = price;
            double close = open + rand.nextGaussian() * 0.08;
            double high = Math.max(open, close) + Math.abs(rand.nextGaussian()) * 0.12;
            double low = Math.min(open, close) - Math.abs(rand.nextGaussian()) * 0.12;
            bars.add(new Bar("GBP_JPY", time, open, high, low, close, 1000 + rand.nextInt(500)));
            time = time.plusSeconds(3600);
            price = close;
        }
        return bars;
    }

    private static void register(String name, StrategyFactory factory) {
        STRATEGIES.put(name, factory);
    }

    private static void printUsage() {
        System.out.println("""
            Usage:
              RunSqBacktest <strategy> --sample
              RunSqBacktest <strategy> GBP_JPY 2012 [capital]
              RunSqBacktest <strategy> GBP_JPY 2006-2012
              RunSqBacktest <strategy> <path-to-.bars-or-.csv>

            Strategies:""");
        STRATEGIES.keySet().forEach(name -> System.out.println("  " + name));
        System.out.println("""
            
            Example:
              RunSqBacktest Strategy_2_14_147_Adapted EUR_USD 2012
            """);
    }

    @FunctionalInterface
    private interface StrategyFactory {
        Strategy create();
    }
}
