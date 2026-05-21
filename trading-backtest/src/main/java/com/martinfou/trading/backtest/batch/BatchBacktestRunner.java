package com.martinfou.trading.backtest.batch;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.DataLoader;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.backtest.report.BacktestJsonExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Runs backtests for ALL available strategies and produces a ranked comparison.
 * <p>
 * Discovers strategies from known classes. Each strategy gets the same bar data
 * for fair comparison. Results are ranked by Sharpe ratio.
 * <p>
 * Usage:
 * <pre>{@code
 * BatchBacktestRunner runner = new BatchBacktestRunner()
 *     .withDataFile(Path.of("data/GBP_JPY_H1.csv"))
 *     .withInitialCapital(50000)
 *     .withCommissionFixed(3.0)
 *     .withSymbol("GBP_JPY");
 * BatchComparisonReport report = runner.runAll();
 * System.out.println(report.toJson());
 * }</pre>
 */
public class BatchBacktestRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchBacktestRunner.class);

    private Path dataFile;
    private String symbol = "GBP_JPY";
    private double initialCapital = 50_000;
    private double commissionFixed = 0;
    private double commissionPct = 0.0003; // 3 pips per trade
    private final List<StrategyFactory> strategyFactories = new ArrayList<>();

    public BatchBacktestRunner withDataFile(Path dataFile) {
        this.dataFile = dataFile;
        return this;
    }

    public BatchBacktestRunner withSymbol(String symbol) {
        this.symbol = symbol;
        return this;
    }

    public BatchBacktestRunner withInitialCapital(double capital) {
        this.initialCapital = capital;
        return this;
    }

    public BatchBacktestRunner withCommissionFixed(double fixed) {
        this.commissionFixed = fixed;
        return this;
    }

    public BatchBacktestRunner withCommissionPct(double pct) {
        this.commissionPct = pct;
        return this;
    }

    /** Register a strategy factory by class (uses no-arg or String constructor). */
    public BatchBacktestRunner register(Class<? extends Strategy> clazz) {
        strategyFactories.add(() -> {
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                try {
                    return clazz.getDeclaredConstructor(String.class).newInstance(symbol);
                } catch (Exception e2) {
                    log.error("Cannot instantiate {}: {}", clazz.getSimpleName(), e2.getMessage());
                    return null;
                }
            }
        });
        return this;
    }

    /** Register a pre-built strategy. */
    public BatchBacktestRunner register(String name, Strategy strategy) {
        strategyFactories.add(() -> strategy);
        return this;
    }

    /**
     * Run backtests for all registered + auto-discovered strategies.
     *
     * @return a ranked batch report, or empty report if no data available
     */
    public BatchComparisonReport runAll() {
        // Load bar data
        List<Bar> bars = loadBars();
        if (bars.isEmpty()) {
            log.warn("No bar data found for {} — returning empty report", symbol);
            return new BatchComparisonReport(List.of());
        }
        log.info("Loaded {} bars of {} for batch backtest", bars.size(), symbol);

        // Auto-discover all known strategies
        autoDiscover();

        // Run each strategy
        List<StrategyResult> results = new ArrayList<>();
        for (StrategyFactory factory : strategyFactories) {
            Strategy strategy = factory.create();
            if (strategy == null) continue;

            try {
                BacktestEngine engine = new BacktestEngine(strategy, bars, initialCapital)
                    .withCommissionFixed(commissionFixed)
                    .withCommissionPct(commissionPct);

                long start = System.currentTimeMillis();
                BacktestResult btResult = engine.run();
                long elapsed = System.currentTimeMillis() - start;

                results.add(new StrategyResult(
                    strategy.getClass().getSimpleName(),
                    strategy.name(),
                    btResult.totalReturnPct(),
                    btResult.sharpeRatio(),
                    btResult.sortinoRatio(),
                    btResult.profitFactor(),
                    btResult.winRatePct(),
                    btResult.maxDrawdownPct(),
                    btResult.totalTrades(),
                    btResult.totalPnl(),
                    btResult.calmarRatio(),
                    elapsed
                ));
                log.info("  ✓ {} — Return: {:.2f}%  Sharpe: {:.2f}  PF: {:.2f}  Trades: {}  ({}ms)",
                    strategy.name(), btResult.totalReturnPct(), btResult.sharpeRatio(),
                    btResult.profitFactor(), btResult.totalTrades(), elapsed);
            } catch (Exception e) {
                log.error("  ✗ {} — {}", strategy.name(), e.getMessage());
            }
        }

        // Rank by Sharpe ratio (descending)
        results.sort((a, b) -> Double.compare(b.sharpeRatio(), a.sharpeRatio()));
        return new BatchComparisonReport(results);
    }

    private void autoDiscover() {
        // Generated strategies (from genetic algorithm)
        register(com.martinfou.trading.strategies.generated.TestFast.class);
        register(com.martinfou.trading.strategies.generated.Test123.class);
        register(com.martinfou.trading.strategies.generated.MyStrategy.class);

        // SQ-imported OANDA strategies (converted to trading-bridge interface)
        register(com.martinfou.trading.strategies.sqimported.Strategy_2_31_175_Converted.class);
        register(com.martinfou.trading.strategies.sqimported.Strategy_2_31_177_Converted.class);
        register(com.martinfou.trading.strategies.sqimported.Strategy_2_32_120_Converted.class);
        register(com.martinfou.trading.strategies.sqimported.Strategy_2_36_190_Converted.class);
        register(com.martinfou.trading.strategies.sqimported.Strategy_2_38_112_Converted.class);
        register(com.martinfou.trading.strategies.sqimported.Strategy_2_14_147_Adapted.class);
        register(com.martinfou.trading.strategies.sqimported.Strategy_2_15_195_Adapted.class);
    }

    private List<Bar> loadBars() {
        if (dataFile == null || !dataFile.toFile().exists()) {
            log.warn("Data file not set or not found: {}", dataFile);
            return List.of();
        }
        return DataLoader.loadCSV(dataFile, symbol);
    }

    // ---------------------------------------------------------------
    //  Result types
    // ---------------------------------------------------------------

    @FunctionalInterface
    public interface StrategyFactory {
        Strategy create();
    }

    /** One strategy's backtest result in the comparison. */
    public record StrategyResult(
        String className,
        String displayName,
        double totalReturnPct,
        double sharpeRatio,
        double sortinoRatio,
        double profitFactor,
        double winRate,
        double maxDrawdown,
        int totalTrades,
        double totalPnl,
        double calmarRatio,
        long elapsedMs
    ) {}

    /** Ranked comparison report for all strategies. */
    public record BatchComparisonReport(List<StrategyResult> results) {
        /** The top-ranked strategy by Sharpe ratio. */
        public StrategyResult topBySharpe() {
            return results.isEmpty() ? null : results.getFirst();
        }

        /** Produces a compact JSON string of the comparison. */
        public String toJson() {
            StringBuilder sb = new StringBuilder("{\"comparison\":[");
            boolean first = true;
            for (StrategyResult r : results) {
                if (!first) sb.append(',');
                first = false;
                sb.append(String.format(
                    "{\"class\":\"%s\",\"name\":\"%s\",\"returnPct\":%.2f,\"sharpe\":%.2f," +
                    "\"sortino\":%.2f,\"profitFactor\":%.2f,\"winRate\":%.2f,\"maxDd\":%.2f," +
                    "\"trades\":%d,\"pnl\":%.2f,\"calmar\":%.2f,\"elapsedMs\":%d}",
                    r.className(), r.displayName(), r.totalReturnPct(), r.sharpeRatio(),
                    r.sortinoRatio(), r.profitFactor(), r.winRate(), r.maxDrawdown(),
                    r.totalTrades(), r.totalPnl(), r.calmarRatio(), r.elapsedMs()));
            }
            sb.append("]}");
            return sb.toString();
        }
    }
}
