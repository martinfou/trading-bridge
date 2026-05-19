package com.martinfou.trading.genetics;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility for testing a genetically-evolved strategy across multiple currency
 * pairs to detect curve-fitting.
 *
 * <p>A strategy that performs well on only one pair but poorly on others
 * is likely overfitted. This tool runs backtests across a set of markets
 * and computes cross-market Sharpe stability, providing a verdict on
 * robustness.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Generate synthetic data for FX majors
 * Map<String, List<Bar>> allData = DEFAULT_SYMBOLS.stream()
 *     .collect(Collectors.toMap(s -> s, s -> MultiMarketTest.generateMarketData(s, 500)));
 *
 * MultiMarketReport report = MultiMarketTest.testOnMarkets(bestChromosome, allData, 100_000.0);
 * System.out.println("Verdict: " + report.verdict());
 * }</pre>
 */
public final class MultiMarketTest {

    private static final Logger log = LoggerFactory.getLogger(MultiMarketTest.class);

    /** Default initial capital for backtests. */
    public static final double DEFAULT_INITIAL_CAPITAL = 100_000.0;

    /** Default set of FX major pairs used for multi-market testing. */
    public static final List<String> DEFAULT_SYMBOLS = List.of(
        "EURUSD", "GBPUSD", "USDJPY", "USDCAD", "AUDUSD", "NZDUSD", "USDCHF"
    );

    /** Default number of bars to generate per market when using synthetic data. */
    public static final int DEFAULT_BAR_COUNT = 500;

    private MultiMarketTest() {
        // Utility class — instantiate nothing
    }

    // ---------------------------------------------------------------
    //  Records
    // ---------------------------------------------------------------

    /**
     * Result of testing a chromosome on a single market.
     *
     * @param symbol     the currency pair / symbol identifier
     * @param result     the backtest result for this symbol
     * @param robustness a simplified robustness score (0–100) for this single market
     */
    public record MarketResult(
        String symbol,
        BacktestResult result,
        RobustnessScore robustness
    ) {}

    /**
     * Aggregate report across all tested markets.
     *
     * @param results       individual market results, one per tested symbol
     * @param avgSharpe     mean Sharpe ratio across all markets
     * @param sharpeStdDev  standard deviation of Sharpe ratios (lower = more stable)
     * @param verdict       "STABLE", "MODERATE", or "UNSTABLE"
     */
    public record MultiMarketReport(
        List<MarketResult> results,
        double avgSharpe,
        double sharpeStdDev,
        String verdict
    ) {}

    // ---------------------------------------------------------------
    //  Public API
    // ---------------------------------------------------------------

    /**
     * Tests the given chromosome against multiple markets and produces
     * an aggregate report.
     *
     * <p>Each market is backtested independently with the same chromosome.
     * The report includes per-market results, average Sharpe, Sharpe
     * standard deviation, and a cross-market stability verdict.</p>
     *
     * @param chromosome     the strategy chromosome to test
     * @param marketData     map of symbol → historical bar data
     * @param initialCapital starting capital for each backtest
     * @return aggregated report with market-by-market results
     * @throws NullPointerException     if chromosome or marketData is null
     * @throws IllegalArgumentException if marketData is empty
     * @throws IllegalStateException    if no valid backtest results were produced
     */
    public static MultiMarketReport testOnMarkets(
            Chromosome chromosome,
            Map<String, List<Bar>> marketData,
            double initialCapital) {
        Objects.requireNonNull(chromosome, "chromosome must not be null");
        Objects.requireNonNull(marketData, "marketData must not be null");
        if (marketData.isEmpty()) {
            throw new IllegalArgumentException("marketData must not be empty");
        }

        List<MarketResult> results = new ArrayList<>(marketData.size());
        List<Double> sharpes = new ArrayList<>(marketData.size());

        for (var entry : marketData.entrySet()) {
            String symbol = entry.getKey();
            List<Bar> bars = entry.getValue();

            if (bars == null || bars.isEmpty()) {
                log.warn("No bar data for {}, skipping", symbol);
                continue;
            }

            try {
                BacktestResult backtestResult = runSingleBacktest(chromosome, symbol, bars, initialCapital);
                RobustnessScore robustness = computeSimpleRobustness(backtestResult);
                results.add(new MarketResult(symbol, backtestResult, robustness));
                sharpes.add(backtestResult.sharpeRatio());
                log.info("{} → Sharpe={}, WinRate={}%, ProfitFactor={}, Robustness={}",
                    symbol,
                    String.format("%.2f", backtestResult.sharpeRatio()),
                    String.format("%.1f", backtestResult.winRatePct()),
                    String.format("%.2f", backtestResult.profitFactor()),
                    String.format("%.1f", robustness.overall()));
            } catch (Exception e) {
                log.error("Backtest failed for {}: {}", symbol, e.getMessage(), e);
            }
        }

        if (results.isEmpty()) {
            throw new IllegalStateException("No valid backtest results were produced");
        }

        double avgSharpe = sharpes.stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);

        double sharpeStdDev = sharpes.size() > 1
            ? Math.sqrt(sharpes.stream()
                .mapToDouble(s -> Math.pow(s - avgSharpe, 2))
                .average()
                .orElse(0.0))
            : 0.0;

        String verdict = generateVerdict(sharpeStdDev);

        log.info("MultiMarket report: avgSharpe={}, stdDev={}, verdict={}",
            String.format("%.2f", avgSharpe),
            String.format("%.2f", sharpeStdDev),
            verdict);

        return new MultiMarketReport(List.copyOf(results), avgSharpe, sharpeStdDev, verdict);
    }

    /**
     * Generates a verdict based on the standard deviation of Sharpe ratios
     * across markets.
     *
     * <table>
     *   <tr><th>Range</th><th>Verdict</th><th>Meaning</th></tr>
     *   <tr><td>{@code stdDev < 0.5}</td><td><b>STABLE</b></td>
     *       <td>Strategy performs consistently across markets; unlikely
     *           to be curve-fitted</td></tr>
     *   <tr><td>{@code 0.5 ≤ stdDev < 1.0}</td><td><b>MODERATE</b></td>
     *       <td>Some variability across markets; may be partially
     *           curve-fitted</td></tr>
     *   <tr><td>{@code stdDev ≥ 1.0}</td><td><b>UNSTABLE</b></td>
     *       <td>Highly variable across markets; likely curve-fitted to
     *           one or two pairs</td></tr>
     * </table>
     *
     * @param sharpeStdDev standard deviation of Sharpe ratios across markets
     * @return {@code "STABLE"}, {@code "MODERATE"}, or {@code "UNSTABLE"}
     */
    public static String generateVerdict(double sharpeStdDev) {
        if (sharpeStdDev < 0.5) {
            return "STABLE";
        } else if (sharpeStdDev < 1.0) {
            return "MODERATE";
        } else {
            return "UNSTABLE";
        }
    }

    /**
     * Generates synthetic sinusoidal bar data with a trend component,
     * priced appropriately for the given symbol.
     *
     * <p>Each symbol gets a unique base price, amplitude, and drift to
     * simulate distinct market conditions:</p>
     * <table>
     *   <tr><th>Symbol</th><th>Base Price</th></tr>
     *   <tr><td>EURUSD</td><td>1.08</td></tr>
     *   <tr><td>GBPUSD</td><td>1.26</td></tr>
     *   <tr><td>USDJPY</td><td>150.00</td></tr>
     *   <tr><td>USDCAD</td><td>1.36</td></tr>
     *   <tr><td>AUDUSD</td><td>0.65</td></tr>
     *   <tr><td>NZDUSD</td><td>0.61</td></tr>
     *   <tr><td>USDCHF</td><td>0.89</td></tr>
     *   <tr><td>Others</td><td>1.00</td></tr>
     * </table>
     *
     * @param symbol   currency pair symbol (case-insensitive)
     * @param barCount number of bars to generate
     * @return list of generated bars, never null
     */
    public static List<Bar> generateMarketData(String symbol, int barCount) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        if (barCount < 2) {
            throw new IllegalArgumentException("barCount must be >= 2, got " + barCount);
        }

        double basePrice = switch (symbol.toUpperCase()) {
            case "EURUSD" -> 1.08;
            case "GBPUSD" -> 1.26;
            case "USDJPY" -> 150.00;
            case "USDCAD" -> 1.36;
            case "AUDUSD" -> 0.65;
            case "NZDUSD" -> 0.61;
            case "USDCHF" -> 0.89;
            default -> 1.00;
        };

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double amplitude = basePrice * 0.01 * (0.8 + rng.nextDouble() * 0.4);
        double trend = 0.9995 + rng.nextDouble() * 0.001;

        List<Bar> bars = new ArrayList<>(barCount);
        for (int i = 0; i < barCount; i++) {
            double phase = (double) i / barCount * 2 * Math.PI * 3;
            double sineComponent = amplitude * Math.sin(phase);
            double trendComponent = Math.pow(trend, i);
            double price = (basePrice + sineComponent) * trendComponent;

            double open = price;
            double close = price * (1 + (rng.nextDouble() - 0.5) * 0.005);
            double high = Math.max(open, close) + amplitude * 0.1;
            double low = Math.min(open, close) - amplitude * 0.1;
            long volume = rng.nextLong(100, 5000);

            bars.add(new Bar(
                symbol,
                Instant.ofEpochSecond(86400L * i),
                open, high, low, close, volume));
        }
        return bars;
    }

    /**
     * Convenience method that generates synthetic data for all default symbols.
     *
     * @param barCount number of bars per symbol
     * @return map of symbol → synthetic bar data
     */
    public static java.util.Map<String, List<Bar>> generateDefaultMarketData(int barCount) {
        java.util.LinkedHashMap<String, List<Bar>> data = new java.util.LinkedHashMap<>();
        for (String symbol : DEFAULT_SYMBOLS) {
            data.put(symbol, generateMarketData(symbol, barCount));
        }
        return java.util.Collections.unmodifiableMap(data);
    }

    // ---------------------------------------------------------------
    //  Private helpers
    // ---------------------------------------------------------------

    /**
     * Runs a single backtest for the given chromosome on a specific symbol.
     */
    private static BacktestResult runSingleBacktest(
            Chromosome chromosome,
            String symbol,
            List<Bar> bars,
            double initialCapital) {
        Strategy strategy = new StrategyTemplate(chromosome);
        BacktestEngine engine = new BacktestEngine(strategy, bars, initialCapital);
        engine.withCommissionFixed(0.0)
              .withCommissionPct(0.0007);
        return engine.run();
    }

    /**
     * Computes a simplified robustness score from a backtest result alone,
     * without requiring walk-forward or Monte Carlo analysis.
     *
     * <p>This lightweight proxy scores the strategy on four dimensions:</p>
     * <ul>
     *   <li><b>Sharpe ratio</b> (0–50 pts) — risk-adjusted returns</li>
     *   <li><b>Profit factor</b> (0–25 pts) — gross profit / gross loss</li>
     *   <li><b>Win rate</b> (0–15 pts) — percentage of winning trades</li>
     *   <li><b>Max drawdown</b> (0–10 pts) — peak-to-trough decline penalty</li>
     * </ul>
     */
    private static RobustnessScore computeSimpleRobustness(BacktestResult result) {
        // --- Sharpe component (0–50) ---
        double sharpe = Math.max(0, result.sharpeRatio());
        double sharpeScore;
        if (sharpe >= 2.0) {
            sharpeScore = 50;
        } else if (sharpe >= 1.5) {
            sharpeScore = 40;
        } else if (sharpe >= 1.0) {
            sharpeScore = 30;
        } else if (sharpe >= 0.5) {
            sharpeScore = 20;
        } else {
            sharpeScore = sharpe / 0.5 * 15;
        }

        // --- Profit factor component (0–25) ---
        double pf = Math.max(0, result.profitFactor());
        double pfScore;
        if (pf >= 2.0) {
            pfScore = 25;
        } else if (pf >= 1.5) {
            pfScore = 20;
        } else if (pf > 1.0) {
            pfScore = 12;
        } else {
            pfScore = Math.max(0, pf * 5);
        }

        // --- Win rate component (0–15) ---
        double wr = Math.max(0, Math.min(100, result.winRatePct()));
        double wrScore;
        if (wr >= 60) {
            wrScore = 15;
        } else if (wr >= 45) {
            wrScore = 12;
        } else if (wr >= 35) {
            wrScore = 8;
        } else {
            wrScore = wr / 35.0 * 5;
        }

        // --- Max drawdown penalty component (0–10) ---
        double dd = Math.max(0, Math.min(100, result.maxDrawdownPct()));
        double ddScore;
        if (dd <= 5) {
            ddScore = 10;
        } else if (dd <= 10) {
            ddScore = 8;
        } else if (dd <= 20) {
            ddScore = 5;
        } else if (dd <= 30) {
            ddScore = 3;
        } else {
            ddScore = 1;
        }

        double overall = Math.min(100, sharpeScore + pfScore + wrScore + ddScore);

        return new RobustnessScore(overall, sharpeScore, pfScore, wrScore, ddScore);
    }
}
