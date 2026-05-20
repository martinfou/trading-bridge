package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.DataLoader;
import com.martinfou.trading.core.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.List;

/**
 * Backtests converted JForex strategies against real market data.
 *
 * <p>Usage:
 * <pre>
 *   java com.martinfou.trading.examples.JForexBacktest
 *       &lt;strategy-class&gt; &lt;csv-path&gt; &lt;symbol&gt;
 * </pre>
 *
 * <p>Example:
 * <pre>
 *   java ... JForexBacktest \\
 *       com.martinfou.trading.strategies.sqimported.Strategy_2_31_175_Converted \\
 *       ./data/historical/dukascopy/eurusd-h1-bid-2026.csv EUR/USD
 * </pre>
 */
public final class JForexBacktest {

    private static final Logger log = LoggerFactory.getLogger(JForexBacktest.class);

    private JForexBacktest() {}

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: JForexBacktest <class> <csv> <symbol> [capital] [usd-rate]");
            System.err.println("  class-name: fully qualified strategy class");
            System.err.println("  csv-path:   path to Dukascopy CSV file");
            System.err.println("  symbol:     instrument symbol (e.g. EUR/USD, GBP/JPY)");
            System.err.println("  capital:    initial capital (default: 10000)");
            System.err.println("  usd-rate:   quote→USD rate for non-USD pairs (e.g. 95 for JPY)");
            System.exit(1);
        }

        String className = args[0];
        String csvPath = args[1];
        String symbol = args[2];
        double capital = args.length > 3 ? Double.parseDouble(args[3]) : 10_000.0;

        try {
            // Load data (Dukascopy format: timestamp,open,high,low,close — no volume)
            var path = Paths.get(csvPath);
            if (!java.nio.file.Files.exists(path)) {
                System.err.println("ERROR: CSV not found: " + path.toAbsolutePath());
                System.exit(1);
            }
            List<Bar> bars = loadDukascopyCSV(path, symbol);
            log.info("Loaded {} bars from {}", bars.size(), csvPath);

            if (bars.isEmpty()) {
                System.err.println("ERROR: No bars loaded from " + csvPath);
                System.exit(1);
            }

            // Detect quote-to-USD rate (required for JPY pairs: P&L is in JPY)
            double quoteRate = 1.0;
            if (symbol.endsWith("/JPY")) {
                // JPY pairs: P&L calculated in JPY, need USD/JPY rate to convert
                // Pass as 4th CLI argument, default to 95 for 2026
                quoteRate = args.length > 4 ? Double.parseDouble(args[4]) : 95.0;
                log.info("JPY pair: {} — quote→USD rate: {}", symbol, String.format("%.2f", quoteRate));
            }

            // Instantiate strategy
            Class<?> clazz = Class.forName(className);
            Strategy strategy = (Strategy) clazz.getDeclaredConstructor().newInstance();
            log.info("Strategy: {}", strategy.name());

            // Run backtest (with quote-to-USD conversion if applicable)
            var engine = new BacktestEngine(strategy, bars, capital);
            if (quoteRate > 0 && Math.abs(quoteRate - 1.0) > 0.001) {
                engine.withQuoteToUsdRate(symbol, quoteRate);
            }
            BacktestResult result = engine.run();

            // Print compact summary
            printCompact(result);

        } catch (ClassNotFoundException e) {
            System.err.println("ERROR: Strategy class not found: " + className);
            System.err.println("  Make sure the class is compiled and on the classpath.");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("ERROR running backtest: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Loads Dukascopy CSV format: timestamp_ms,open,high,low,close
     * Falls back to StrategyQuant format if Dukascopy parsing fails.
     */
    private static List<Bar> loadDukascopyCSV(java.nio.file.Path path, String symbol) {
        var bars = new java.util.ArrayList<Bar>();
        try (var reader = java.nio.file.Files.newBufferedReader(path)) {
            String header = reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                var parts = line.split(",");
                try {
                    if (parts.length >= 5) {
                        // Dukascopy: timestamp_ms,open,high,low,close
                        long tsMs = Long.parseLong(parts[0].trim());
                        var ts = java.time.Instant.ofEpochMilli(tsMs);
                        double open = Double.parseDouble(parts[1].trim());
                        double high = Double.parseDouble(parts[2].trim());
                        double low = Double.parseDouble(parts[3].trim());
                        double close = Double.parseDouble(parts[4].trim());
                        long volume = parts.length >= 6 ? Long.parseLong(parts[5].trim()) : 0;
                        bars.add(new Bar(symbol, ts, open, high, low, close, volume));
                    }
                } catch (Exception ignored) { /* skip malformed lines */ }
            }
        } catch (Exception e) {
            log.warn("Dukascopy CSV load failed: {}. Trying SQ format...", e.getMessage());
            return DataLoader.loadStrategyQuantCSV(path, symbol);
        }
        return bars;
    }

    private static void printCompact(BacktestResult r) {
        System.out.println("=== Backtest Result ===");
        System.out.println("Strategy:    " + r.strategyName());
        System.out.println("Period:      " + r.periodStart() + " → " + r.periodEnd());
        System.out.println("Trades:      " + r.totalTrades());
        System.out.println("Win Rate:    " + String.format("%.1f%%", r.winRatePct()));
        System.out.println("Net Profit:  $" + String.format("%.2f", r.totalPnl()));
        System.out.println("Sharpe:      " + String.format("%.2f", r.sharpeRatio()));
        System.out.println("Profit Fac:  " + String.format("%.2f", r.profitFactor()));
        System.out.println("Max DD:      " + String.format("%.2f%%", r.maxDrawdownPct()));
    }
}
