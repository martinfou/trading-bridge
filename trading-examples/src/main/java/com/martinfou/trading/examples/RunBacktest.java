package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEventJson;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.LotSizing;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.StrategyCatalog;
import com.martinfou.trading.strategies.StrategyCatalog.Family;
import com.martinfou.trading.backtest.persistence.BacktestPersistenceService;
import com.martinfou.trading.backtest.persistence.BacktestQueryFilters;
import com.martinfou.trading.backtest.persistence.BacktestRunSummary;
import com.martinfou.trading.backtest.persistence.SqliteBacktestRunStore;
import com.martinfou.trading.backtest.wfa.WfaConfig;
import com.martinfou.trading.backtest.wfa.WfaEngine;
import com.martinfou.trading.backtest.wfa.WfaReport;
import com.martinfou.trading.backtest.wfa.WfaFoldResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Unified backtest CLI for all strategy families.
 *
 * <pre>{@code
 *   mvn exec:java -pl trading-examples \
 *     -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
 *     -Dexec.args="--list"
 *
 *   mvn exec:java -pl trading-examples \
 *     -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
 *     -Dexec.args="LondonOpenRangeBreakout EUR_USD 2012 --paper --json"
 * }</pre>
 */
public class RunBacktest {

    private static final int SAMPLE_BARS = 3000;

    static {
        StrategyCatalog.register("SmaCrossover", Family.EXAMPLE,
            sym -> new SmaCrossoverStrategy("SMA 20/50", sym, 20, 50),
            "EUR_USD");
    }

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("--help")) {
            printUsage();
            return;
        }

        if (args[0].equals("--list")) {
            printList();
            return;
        }

        if (args[0].equals("--query")) {
            runCliQuery(args);
            return;
        }

        if (args[0].equals("--sample")) {
            CliFlags flags = stripCliFlags(args);
            runBareSample(flags);
            return;
        }

        if (args[0].equals("--wfa")) {
            runWfa(args);
            return;
        }

        runStrategyBacktest(args);
    }

    /** Shared backtest execution for legacy RunPropBacktest --all. */
    public static BacktestResult runStrategy(Strategy strategy, List<Bar> bars, double capital) {
        String symbol = bars.isEmpty() ? "" : bars.getFirst().symbol();
        return RunContext.forStrategy(strategy, symbol, RunMode.BACKTEST, bars, capital).run();
    }

    public static List<Bar> generateSampleBars(String symbol, int count) {
        var bars = new ArrayList<Bar>(count);
        boolean jpy = symbol.contains("JPY");
        double price = jpy ? 185.0 : 1.08;
        double vol = jpy ? 0.15 : 0.002;
        var time = jpy
            ? Instant.parse("2003-01-01T00:00:00Z")
            : Instant.parse("2024-01-01T00:00:00Z");
        var rand = new Random(jpy ? 147 : 42);

        for (int i = 0; i < count; i++) {
            double open = price;
            double close = open + rand.nextGaussian() * vol;
            double high = Math.max(open, close) + Math.abs(rand.nextGaussian()) * vol * 0.5;
            double low = Math.min(open, close) - Math.abs(rand.nextGaussian()) * vol * 0.5;
            if (jpy) {
                price = Math.max(130.0, Math.min(250.0, close));
            } else {
                price = close;
            }
            bars.add(new Bar(symbol, time, open, high, low, close, 1000 + rand.nextInt(500)));
            time = time.plusSeconds(3600);
        }
        return bars;
    }

    private static void runBareSample(CliFlags flags) {
        String symbol = "EUR_USD";
        var bars = generateSampleBars(symbol, 500);
        var strategy = new SmaCrossoverStrategy("SMA 20/50", symbol, 20, 50);
        RunMode mode = flags.paperMode() ? RunMode.PAPER : RunMode.BACKTEST;
        var context = RunContext.forStrategy(strategy, symbol, mode, bars, 10_000);
        if (flags.jsonOutput()) {
            runWithJsonOutput(context);
        } else {
            if (flags.paperMode()) {
                System.out.println("Paper mode (stub): SmaCrossover sample (" + bars.size() + " bars)");
            } else {
                System.out.println("SmaCrossover sample (" + bars.size() + " bars, " + symbol + ")");
            }
            context.run().printSummary();
        }
    }

    private static void runStrategyBacktest(String[] args) {
        CliFlags flags = stripCliFlags(args);
        args = flags.args();

        String strategyId = args[0];
        if (!StrategyCatalog.contains(strategyId)) {
            System.err.println("Unknown strategy: " + strategyId);
            printUsage();
            System.exit(1);
        }

        double capital = LotSizing.DEFAULT_STARTING_CAPITAL;
        List<Bar> bars;
        String symbol;

        try {
            if (args.length >= 2 && args[1].equals("--sample")) {
                symbol = StrategyCatalog.defaultSymbol(strategyId);
                bars = generateSampleBars(symbol, SAMPLE_BARS);
                logStatus(flags, sampleLabel(flags, symbol, bars.size()));
            } else if (args.length >= 2) {
                String[] dataArgs = Arrays.copyOfRange(args, 1, args.length);
                if (args.length >= 4 && !looksLikeDataToken(args[args.length - 1])) {
                    try {
                        capital = Double.parseDouble(args[args.length - 1]);
                        dataArgs = Arrays.copyOfRange(args, 1, args.length - 1);
                    } catch (NumberFormatException ignored) {
                        // last arg is not capital
                    }
                }
                if (isFilePath(dataArgs[0])) {
                    var loaded = HistoricalDataLoader.loadFile(Path.of(dataArgs[0]),
                        StrategyCatalog.defaultSymbol(strategyId));
                    bars = loaded.bars();
                    symbol = loaded.symbol();
                    logStatus(flags, loadLabel(flags, loaded.source(), symbol, bars.size()));
                } else {
                    var loaded = HistoricalDataLoader.loadFromArgs(
                        StrategyCatalog.defaultSymbol(strategyId), dataArgs);
                    bars = loaded.bars();
                    symbol = loaded.symbol();
                    logStatus(flags, loadLabel(flags, loaded.source(), symbol, bars.size()));
                }
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
            System.err.println("No bars loaded. Check path/year.");
            System.err.println("Try: ./scripts/download-data.sh --list --tf h1");
            System.exit(1);
        }

        RunContext context = flags.paperMode()
            ? RunContexts.paper(strategyId, symbol, bars, capital)
            : RunContexts.backtest(strategyId, symbol, bars, capital);
        if (flags.jsonOutput()) {
            runWithJsonOutput(context);
        } else {
            context.run().printSummary();
        }
    }

    private static String sampleLabel(CliFlags flags, String symbol, int barCount) {
        String prefix = flags.paperMode() ? "Paper mode (stub): synthetic sample" : "Synthetic sample";
        return prefix + ": " + symbol + " (" + barCount + " bars)";
    }

    private static String loadLabel(CliFlags flags, String source, String symbol, int barCount) {
        String prefix = flags.paperMode() ? "Paper mode (stub): loaded" : "Loaded";
        return prefix + ": " + source + " (" + symbol + ", " + barCount + " bars)";
    }

    private static void runWithJsonOutput(RunContext context) {
        context.withEventListener(event -> System.out.println(RunEventJson.toJsonLine(event))).run();
    }

    private static void logStatus(CliFlags flags, String message) {
        if (flags.jsonOutput()) {
            System.err.println(message);
        } else {
            System.out.println(message);
        }
    }

    private static CliFlags stripCliFlags(String[] args) {
        boolean json = false;
        boolean paper = false;
        var kept = new ArrayList<String>();
        for (String arg : args) {
            if ("--json".equals(arg)) {
                json = true;
            } else if ("--paper".equals(arg)) {
                paper = true;
            } else {
                kept.add(arg);
            }
        }
        return new CliFlags(kept.toArray(String[]::new), json, paper);
    }

    private record CliFlags(String[] args, boolean jsonOutput, boolean paperMode) {}

    private static boolean looksLikeDataToken(String arg) {
        return arg.contains(".") || arg.contains("-") && arg.matches(".*\\d{4}.*");
    }

    private static boolean isFilePath(String arg) {
        return arg.endsWith(".bars") || arg.endsWith(".csv") || Path.of(arg).toFile().exists();
    }

    private static void printList() {
        System.out.printf("%-32s %-12s %s%n", "ID", "FAMILY", "DEFAULT_SYMBOL");
        System.out.println("-".repeat(60));
        for (StrategyCatalog.Entry e : StrategyCatalog.entries()) {
            System.out.printf("%-32s %-12s %s%n", e.id(), e.family(), e.defaultSymbol());
        }
    }

    private static void printUsage() {
        System.out.println("""
            Usage:
              RunBacktest --list
              RunBacktest --help
              RunBacktest --sample
              RunBacktest --query [--symbol <sym>] [--strategy <id>] [--min-sharpe <val>] [--min-pf <val>] [--sort-by <col>] [--limit <n>]
              RunBacktest --wfa <strategyId> <symbol> <yearSpec> --config <config-file.json>
              RunBacktest <strategyId> --sample
              RunBacktest <strategyId> EUR_USD 2012 [capital]
              RunBacktest <strategyId> EUR_USD 2012 --json
              RunBacktest <strategyId> EUR_USD 2012 --paper [--json]
              RunBacktest <strategyId> EUR_USD 2006-2012
              RunBacktest <strategyId> data/historical/bars/EUR_USD_H1_2012.bars

            Data: data/historical/ (see data/README.md)
            Download: ./scripts/download-data.sh --list --tf h1

            --json   Emit RunEvent JSONL to stdout (status messages on stderr)
            --paper  Paper mode stub (historical bar replay, same fills as backtest)

            Deprecated aliases: RunPropBacktest, RunSqBacktest
            """);
    }

    private static void runCliQuery(String[] args) {
        String symbol = null;
        String strategyId = null;
        Double minSharpe = null;
        Double minProfitFactor = null;
        String sortBy = "created_at";
        String sortOrder = "DESC";
        Integer limit = 20;
        Integer offset = 0;

        for (int i = 1; i < args.length; i++) {
            if ("--symbol".equals(args[i]) && i + 1 < args.length) {
                symbol = args[++i];
            } else if ("--strategy".equals(args[i]) && i + 1 < args.length) {
                strategyId = args[++i];
            } else if (("--min-sharpe".equals(args[i]) || "--sharpe".equals(args[i])) && i + 1 < args.length) {
                try {
                    minSharpe = Double.parseDouble(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid min-sharpe value: " + args[i]);
                }
            } else if (("--min-profit-factor".equals(args[i]) || "--min-pf".equals(args[i]) || "--min-profitfactor".equals(args[i])) && i + 1 < args.length) {
                try {
                    minProfitFactor = Double.parseDouble(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid min-profit-factor value: " + args[i]);
                }
            } else if ("--sort-by".equals(args[i]) && i + 1 < args.length) {
                sortBy = args[++i];
            } else if ("--sort-order".equals(args[i]) && i + 1 < args.length) {
                sortOrder = args[++i];
            } else if ("--limit".equals(args[i]) && i + 1 < args.length) {
                try {
                    limit = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid limit value: " + args[i]);
                }
            } else if ("--offset".equals(args[i]) && i + 1 < args.length) {
                try {
                    offset = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid offset value: " + args[i]);
                }
            }
        }

        var filters = BacktestQueryFilters.builder()
            .symbol(symbol)
            .strategyId(strategyId)
            .minSharpe(minSharpe)
            .minProfitFactor(minProfitFactor)
            .sortBy(sortBy)
            .sortOrder(sortOrder)
            .limit(limit)
            .offset(offset)
            .build();

        Path dbPath = BacktestPersistenceService.resolveDefaultDbPath();
        System.out.println("Querying backtest runs from: " + dbPath);

        try (var store = new SqliteBacktestRunStore(dbPath)) {
            var results = store.list(filters);
            if (results.isEmpty()) {
                System.out.println("No matching backtest runs found.");
                return;
            }

            System.out.println("+----------+--------------------------------+---------+--------+--------+--------+--------+--------+--------------+----------------------+");
            System.out.printf("| %-8s | %-30s | %-7s | %6s | %6s | %6s | %6s | %6s | %12s | %-20s |%n",
                "Run ID", "Strategy", "Symbol", "Trades", "Win %", "MaxDD%", "Sharpe", "PF", "Total PnL", "Created At");
            System.out.println("+----------+--------------------------------+---------+--------+--------+--------+--------+--------+--------------+----------------------+");

            for (var run : results) {
                String shortId = run.runId().length() > 8 ? run.runId().substring(0, 8) : run.runId();
                String stratId = run.strategyId().length() > 30 ? run.strategyId().substring(0, 27) + "..." : run.strategyId();
                System.out.printf("| %-8s | %-30s | %-7s | %6d | %5.1f%% | %5.2f%% | %6.2f | %6.2f | %12.2f | %-20s |%n",
                    shortId,
                    stratId,
                    run.symbol(),
                    run.totalTrades(),
                    run.winRatePct(),
                    run.maxDrawdownPct(),
                    run.sharpeRatio(),
                    run.profitFactor(),
                    run.totalPnl(),
                    run.createdAt().toString().substring(0, 19).replace('T', ' ')
                );
            }
            System.out.println("+----------+--------------------------------+---------+--------+--------+--------+--------+--------+--------------+----------------------+");
            int totalCount = store.count(filters);
            System.out.printf("Showing %d of %d total matching runs.%n", results.size(), totalCount);
        } catch (Exception e) {
            System.err.println("Error querying database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void runWfa(String[] args) {
        if (args.length < 6 || !args[4].equals("--config")) {
            System.err.println("Usage: mvn exec:java -pl trading-examples -Dexec.mainClass=\"com.martinfou.trading.examples.RunBacktest\" -Dexec.args=\"--wfa <strategyId> <symbol> <yearSpec> --config <config-file.json>\"");
            System.exit(1);
        }

        String strategyId = args[1];
        String symbol = args[2];
        String yearSpec = args[3];
        String configFile = args[5];

        System.out.println("Starting WFA with strategy: " + strategyId + ", symbol: " + symbol + ", data spec: " + yearSpec + ", config: " + configFile);

        // 1. Load configuration from JSON
        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD, com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY);

        WfaConfig config;
        try {
            config = mapper.readValue(new File(configFile), WfaConfig.class);
        } catch (Exception e) {
            System.err.println("Error reading config file: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return;
        }

        // 2. Load historical bars
        List<Bar> bars;
        try {
            if (isFilePath(yearSpec)) {
                var loaded = HistoricalDataLoader.loadFile(Path.of(yearSpec), symbol);
                bars = loaded.bars();
            } else {
                bars = HistoricalDataLoader.loadYearSpec(symbol, yearSpec, HistoricalDataLoader.DEFAULT_BARS_DIR);
            }
        } catch (Exception e) {
            System.err.println("Error loading historical data: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return;
        }

        if (bars.isEmpty()) {
            System.err.println("No bars loaded. Check path/year spec.");
            System.exit(1);
            return;
        }

        System.out.println("Loaded " + bars.size() + " bars.");

        // 3. Setup Strategy supplier
        if (!StrategyCatalog.contains(strategyId)) {
            System.err.println("Unknown strategy: " + strategyId);
            System.exit(1);
            return;
        }
        java.util.function.Supplier<Strategy> supplier = () -> StrategyCatalog.create(strategyId, symbol);

        // 4. Run WfaEngine
        try (WfaEngine engine = new WfaEngine(config, supplier, bars)) {
            WfaReport report = engine.execute();

            // 5. Save report JSON to data/reports/wfa/wfa-{id}.json
            File reportDir = new File("data/reports/wfa");
            if (!reportDir.exists()) {
                if (!reportDir.mkdirs()) {
                    throw new java.io.IOException("Failed to create directory: " + reportDir.getAbsolutePath());
                }
            }
            File reportFile = new File(reportDir, "wfa-" + report.wfaId() + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(reportFile, report);

            // 6. Display console summary of metrics
            double avgIsSharpe = report.folds().stream().mapToDouble(WfaFoldResult::isSharpe).average().orElse(0.0);
            double avgOosSharpe = report.folds().stream().mapToDouble(WfaFoldResult::oosSharpe).average().orElse(0.0);

            System.out.println("========================================================================");
            System.out.println("Walk-Forward Analysis (WFA) Execution Summary");
            System.out.println("========================================================================");
            System.out.printf("WFA Run ID:          %s%n", report.wfaId());
            System.out.printf("Strategy:            %s%n", report.strategyName());
            System.out.printf("Instrument:          %s%n", report.instrument());
            System.out.printf("Global OOS Sharpe:   %.4f%n", report.oosSharpe());
            System.out.printf("Average IS Sharpe:   %.4f%n", avgIsSharpe);
            System.out.printf("Average OOS Sharpe:  %.4f%n", avgOosSharpe);
            System.out.printf("WFE (Efficiency):    %.4f%n", report.wfe());
            System.out.printf("Total OOS Trades:    %d%n", report.oosTradesCount());
            System.out.println("------------------------------------------------------------------------");
            System.out.println("Complete WFA report saved to: " + reportFile.getPath());
            System.out.println("========================================================================");

        } catch (Exception e) {
            System.err.println("Error executing WFA: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
