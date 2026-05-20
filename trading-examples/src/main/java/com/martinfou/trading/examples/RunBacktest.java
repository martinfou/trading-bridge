package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.report.BacktestJsonExporter;
import com.martinfou.trading.backtest.report.HtmlReportGenerator;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.DataLoader;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.BarStore;
import com.martinfou.trading.data.DataProxy;
import com.martinfou.trading.data.LocalDataProxy;
import com.martinfou.trading.data.OandaDataProxy;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.*;

/**
 * Unified backtest runner.
 *
 * <h3>Architecture — proxy-based data access</h3>
 *
 * Backtesting and live trading use the same {@link DataProxy} interface.
 * Only the implementation differs:
 *
 * <pre>
 *   BacktestRunner  ──→  DataProxy.getCandles()  ──→  LocalDataProxy  →  .bars files
 *   LiveRunner      ──→  DataProxy.getCandles()  ──→  OandaDataProxy  →  OANDA API
 * </pre>
 *
 * The strategy code is identical in both modes — it receives {@code Bar}
 * objects through the same pipeline. This ensures backtest results
 * accurately reflect live trading conditions.
 *
 * <p>Replaces the old {@code JForexBacktest} — deleted. All strategies
 * implement the same {@link Strategy} interface.</p>
 */
public class RunBacktest {
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║   Trading Bridge - Backtest Runner   ║");
        System.out.println("╚══════════════════════════════════════╝");

        // Parse flags
        boolean jsonMode = false;
        boolean htmlMode = false;
        boolean sampleMode = false;
        String outputDir = null;
        String strategyClass = null;
        String proxyMode = null;     // "local" or "oanda"
        String dataDir = null;
        String granularity = null;
        Double quoteRate = null;
        Double capital = null;
        List<String> positional = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--sample" -> sampleMode = true;
                case "--json" -> jsonMode = true;
                case "--html" -> htmlMode = true;
                case "--proxy" -> {
                    if (i + 1 < args.length) proxyMode = args[++i];
                }
                case "--data-dir" -> {
                    if (i + 1 < args.length) dataDir = args[++i];
                }
                case "--strategy" -> {
                    if (i + 1 < args.length) strategyClass = args[++i];
                }
                case "--granularity" -> {
                    if (i + 1 < args.length) granularity = args[++i];
                }
                case "--quote-rate" -> {
                    if (i + 1 < args.length) quoteRate = Double.parseDouble(args[++i]);
                }
                case "--capital" -> {
                    if (i + 1 < args.length) capital = Double.parseDouble(args[++i]);
                }
                case "--output-dir" -> {
                    if (i + 1 < args.length) outputDir = args[++i];
                }
                default -> positional.add(args[i]);
            }
        }

        // --sample overrides everything else
        if (sampleMode) {
            if (jsonMode || htmlMode) {
                runSampleBacktestWithExport(jsonMode, htmlMode, outputDir);
            } else {
                runSampleBacktest();
            }
            return;
        }

        if (positional.isEmpty()) {
            printUsage();
            return;
        }

        // ---------------------------------------------------------------
        //  Proxy mode: data through the same abstraction as live trading
        // ---------------------------------------------------------------
        if ("local".equals(proxyMode) || "oanda".equals(proxyMode)) {
            String symbol = positional.get(0);
            String gran = granularity != null ? granularity
                : (positional.size() > 1 ? positional.get(1) : "H1");
            int count = positional.size() > 2 ? Integer.parseInt(positional.get(2)) : 5000;
            if (capital == null) capital = positional.size() > 3
                ? Double.parseDouble(positional.get(3)) : 10_000.0;

            DataProxy proxy = createProxy(proxyMode, dataDir);
            runProxyBacktest(proxy, strategyClass, symbol, gran, count,
                capital, quoteRate, jsonMode, htmlMode, outputDir);
            return;
        }

        // ---------------------------------------------------------------
        //  Legacy mode: direct CSV loading (backward compat, no proxy)
        // ---------------------------------------------------------------
        if (proxyMode != null) {
            System.err.println("ERROR: Unknown proxy mode: " + proxyMode);
            System.err.println("  Supported: local (recommended), oanda");
            System.exit(1);
        }

        String file = positional.get(0);
        String symbol = positional.size() > 1 ? positional.get(1) : null;
        if (capital == null) capital = positional.size() > 2
            ? Double.parseDouble(positional.get(2)) : 10_000.0;

        System.err.println("WARNING: Direct CSV loading — use --proxy local for "
            + "identical pipeline to live trading.");
        Strategy strategy = loadStrategy(strategyClass, symbol);

        System.out.println("Loading CSV: " + file);
        var path = Paths.get(file);
        if (!java.nio.file.Files.exists(path)) {
            System.err.println("ERROR: File not found: " + path.toAbsolutePath());
            System.exit(1);
        }

        String dataSymbol = symbol != null ? symbol : "SYMBOL";
        List<Bar> bars = DataLoader.loadAuto(path, dataSymbol);
        System.out.println("Loaded " + bars.size() + " bars");

        if (bars.isEmpty()) {
            System.err.println("ERROR: No bars loaded from " + file);
            System.exit(1);
        }

        runEngine(strategy, bars, symbol, capital, quoteRate, jsonMode, htmlMode, outputDir);
    }

    // ---------------------------------------------------------------
    //  Proxy backtest — same pipeline as live trading
    // ---------------------------------------------------------------

    private static DataProxy createProxy(String mode, String dataDir) {
        return switch (mode) {
            case "local" -> {
                String dir = dataDir != null ? dataDir : "data/historical/bars";
                System.out.println("Proxy: local ← " + dir);
                yield new LocalDataProxy(dir);
            }
            case "oanda" -> {
                System.out.println("Proxy: OANDA (live pipeline)");
                try {
                    yield OandaDataProxy.fromEnv(true);
                } catch (IllegalArgumentException e) {
                    System.err.println("ERROR: " + e.getMessage());
                    System.err.println("  Source ~/projects/trading-dashboard/.env or export manually.");
                    System.exit(1);
                    yield null;
                }
            }
            default -> throw new IllegalArgumentException("Unknown proxy: " + mode);
        };
    }

    private static void runProxyBacktest(DataProxy proxy, String strategyClass,
            String symbol, String granularity, int count, double capital,
            Double quoteRateOverride, boolean json, boolean html, String outputDir) {
        try {
            // Fetch data through the proxy (same interface as live)
            String instrument = symbol.replace('/', '_');
            if (!instrument.contains("_") && instrument.length() >= 6) {
                // "GBPJPY" → "GBP_JPY"
                for (int i = 3; i < instrument.length(); i++) {
                    if (Character.isUpperCase(instrument.charAt(i))) {
                        instrument = instrument.substring(0, i) + "_" + instrument.substring(i);
                        break;
                    }
                }
            }

            String gran = granularity != null ? granularity : "H1";

            // Show data availability for local proxy
            if (proxy instanceof LocalDataProxy local) {
                int total = local.totalBars(instrument, gran);
                System.out.println("Data: " + instrument + " " + gran
                    + " — " + total + " bars available, requesting " + count);
            } else {
                System.out.println("Fetching: " + instrument + " " + gran + " x" + count);
            }

            List<Bar> bars = proxy.getCandles(instrument, gran, count);

            if (bars.isEmpty()) {
                System.err.println("ERROR: Proxy returned 0 candles for "
                    + instrument + " " + gran);
                System.exit(1);
            }

            System.out.println("Received " + bars.size() + " candles via proxy");
            var first = bars.getFirst().timestamp();
            var last = bars.getLast().timestamp();
            System.out.println("Range: " + first + "  →  " + last);

            // Load and execute strategy
            Strategy strategy = loadStrategy(strategyClass, symbol);
            runEngine(strategy, bars, symbol, capital, quoteRateOverride, json, html, outputDir);

        } catch (java.io.FileNotFoundException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("ERROR: Proxy call failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------
    //  Core backtest execution (identical for proxy and legacy modes)
    // ---------------------------------------------------------------

    private static Strategy loadStrategy(String strategyClass, String defaultSymbol) {
        if (strategyClass != null) return loadStrategyByClass(strategyClass);
        String sym = defaultSymbol != null ? defaultSymbol : "SYMBOL";
        return new SmaCrossoverStrategy("SMA_20_50", sym, 20, 50);
    }

    private static void runEngine(Strategy strategy, List<Bar> bars, String symbol,
            double capital, Double quoteRateOverride,
            boolean json, boolean html, String outputDir) {
        System.out.println("Strategy: " + strategy.name());

        String strategySymbol = extractSymbol(strategy);
        var engine = new BacktestEngine(strategy, bars, capital);

        // Quote-to-USD conversion (priority: --quote-rate > auto-detect)
        if (quoteRateOverride != null) {
            engine.withQuoteToUsdRate(strategySymbol, quoteRateOverride);
            System.out.println("Quote→USD rate: " + String.format("%.2f", quoteRateOverride)
                + " (explicit)");
        } else {
            Double detected = DataLoader.detectQuoteToUsdRate(strategySymbol);
            if (detected != null) {
                engine.withQuoteToUsdRate(strategySymbol, detected);
                System.out.println("Quote→USD rate: " + String.format("%.2f", detected)
                    + " (auto-detected for " + strategySymbol + ")");
            }
        }

        var result = engine.run();
        result.printSummary();

        if (json || html) exportResult(result, json, html, outputDir);
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private static Strategy loadStrategyByClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return (Strategy) clazz.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            System.err.println("ERROR: Strategy class not found: " + className);
            System.err.println("  Make sure the class is compiled and on the classpath.");
            System.exit(1);
            return null;
        } catch (Exception e) {
            System.err.println("ERROR: Failed to instantiate strategy: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return null;
        }
    }

    private static String extractSymbol(Strategy strategy) {
        String name = strategy.name();
        if (name == null || name.isBlank()) return "SYMBOL";
        var m = java.util.regex.Pattern.compile("[A-Z]{3}[/_]?[A-Z]{3}").matcher(name);
        if (m.find()) return m.group().replace('_', '/');
        return "SYMBOL";
    }

    // ---------------------------------------------------------------
    //  Sample backtests
    // ---------------------------------------------------------------

    static void runSampleBacktest() {
        System.out.println("\n--- Sample backtest with generated data ---\n");
        var bars = generateSampleData("EURUSD", 500);
        var strategy = new SmaCrossoverStrategy("SMA_20_50_EURUSD", "EURUSD", 20, 50);
        var engine = new BacktestEngine(strategy, bars, 10000);
        var result = engine.run();
        result.printSummary();
    }

    static void runSampleBacktestWithExport(boolean json, boolean html, String outputDir) {
        System.out.println("\n--- Sample backtest with export ---\n");
        var bars = generateSampleData("EURUSD", 500);
        var strategy = new SmaCrossoverStrategy("SMA_20_50_EURUSD", "EURUSD", 20, 50);
        var engine = new BacktestEngine(strategy, bars, 10000);
        var result = engine.run();
        result.printSummary();
        exportResult(result, json, html, outputDir);
    }

    // ---------------------------------------------------------------
    //  Export
    // ---------------------------------------------------------------

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

    // ---------------------------------------------------------------
    //  Usage
    // ---------------------------------------------------------------

    private static void printUsage() {
        System.out.println("""

Usage:
  RunBacktest --proxy local <symbol> [granularity] [count] [capital]    ← recommended
  RunBacktest --proxy oanda <symbol> [granularity] [count] [capital]    ← OANDA live fetch
  RunBacktest [options] <csv-file> [symbol] [capital]                   ← legacy (CSV only)
  RunBacktest --sample [--json] [--html]

Modes:
  --proxy local         Read from local .bars files via DataProxy
                        (same interface as live trading)
  --proxy oanda         Fetch via OANDA REST API (identical to live pipeline)

Proxy flags:
  --data-dir <path>     Data directory for .bars files (default: data/historical/bars)
  --granularity <tf>    Timeframe: M1, M5, M15, M30, H1 (default), H4, D, W

Shared flags:
  --strategy <class>    Fully-qualified strategy class (default: SMA_20_50)
  --json                Export JSON report (dashboard-compatible)
  --html                Export HTML report
  --output-dir <dir>    Output directory (default: _bmad-output/...)
  --capital <amount>    Initial capital (default: 10000)
  --quote-rate <rate>   Force quote→USD conversion rate (e.g. 95 for JPY)
  --sample              Sample with generated data

Architecture:
  Backtest ──→ DataProxy.getCandles() ──→ LocalDataProxy  →  .bars files  ✓
  Live     ──→ DataProxy.getCandles() ──→ OandaDataProxy  →  OANDA API    ✓
  Same pipeline, same interface, same format.

Examples:
  # Proxy mode (recommended) — via .bars, same pipeline as live
  RunBacktest --proxy local \\\\
      --strategy com...sqimported.Strategy_2_31_175_Converted \\\\
      --json --html GBP/JPY H1 5000 1000

  # OANDA proxy — fetch fresh data from OANDA API
  RunBacktest --proxy oanda GBP/JPY H1 5000 1000

  # Legacy — direct CSV (no proxy)
  RunBacktest --json data/EURUSD_H1.csv EURUSD 10000

  # Sample
  RunBacktest --sample --json --html
""");
    }

    // ---------------------------------------------------------------
    //  Data generator
    // ---------------------------------------------------------------

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
