package com.martinfou.trading.genetics;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Analyzes the stability of a strategy's parameters by perturbing each
 * parameter (±10 %, ±20 %, ±50 %), re-backtesting, and measuring the
 * impact on the Sharpe ratio.
 *
 * <p>The standard deviation of Sharpe ratios across perturbations is
 * used as the instability metric: <em>lower = more robust</em>.
 * The {@link SensitivityResult#stabilityScore()} maps this to a
 * 0–100 scale where higher means more stable (less sensitivity).</p>
 *
 * <p>This analysis is the anti-thesis of curve-fitting: parameters that
 * produce wildly different Sharpe ratios after a small tweak are fragile
 * and likely overfit to historical noise.</p>
 *
 * <h3>Parameters analyzed</h3>
 * <ul>
 *   <li>Period of each entry {@link Gene} (e.g. SMA(14) → test 13, 17, 11, 7…)</li>
 *   <li>Period of each exit {@link Gene}</li>
 *   <li>Stop-loss value</li>
 *   <li>Take-profit value</li>
 * </ul>
 */
public final class ParameterSensitivityAnalysis {

    private static final Logger log = LoggerFactory.getLogger(ParameterSensitivityAnalysis.class);

    /** Perturbation factors applied to every parameter: ±10 %, ±20 %, ±50 %. */
    private static final double[] PERTURBATIONS = {-0.50, -0.20, -0.10, 0.10, 0.20, 0.50};

    private static final double MIN_PERIOD = 2.0;
    private static final double MAX_PERIOD = 200.0;
    private static final double MIN_RISK = 0.0;
    private static final double MAX_RISK = 500.0;

    /**
     * Result for a single parameter: its name, base value, the test values
     * tried, the corresponding Sharpe ratios, and a composite stability score.
     *
     * @param paramName     human-readable parameter name (e.g. "entry_0_period", "stopLoss")
     * @param baseValue     original parameter value before perturbation
     * @param testValues    the actual values that were tested (may differ from ideal
     *                      perturbations due to clamping)
     * @param sharpeValues  Sharpe ratio produced by each test value (same order)
     * @param stabilityScore  composite score 0–100; higher = more stable
     */
    public record SensitivityResult(
        String paramName,
        double baseValue,
        List<Double> testValues,
        List<Double> sharpeValues,
        double stabilityScore
    ) {}

    /**
     * Results for all parameters of a chromosome, plus a global stability summary.
     *
     * @param perParameter  one {@link SensitivityResult} per parameter
     * @param globalScore   average of all per-parameter stability scores (0–100)
     * @param globalStdDev  standard deviation of all Sharpe ratios across ALL
     *                      perturbations (lower = more robust overall)
     */
    public record SensitivityReport(
        List<SensitivityResult> perParameter,
        double globalScore,
        double globalStdDev
    ) {}

    // ---------------------------------------------------------------
    //  Public API
    // ---------------------------------------------------------------

    /**
     * Analyzes parameter sensitivity for the given chromosome.
     *
     * <p>For each tunable parameter (gene periods, stop-loss, take-profit),
     * the method creates perturbed copies of the chromosome, re-backtests,
     * records the resulting Sharpe ratio, and computes a stability score.</p>
     *
     * @param chromosome     the strategy chromosome to analyze
     * @param baseResult     backtest result of the unmodified chromosome (used
     *                       only for reference / logging; the base Sharpe is
     *                       not included in the sensitivity computation)
     * @param bars           historical bar data
     * @param initialCapital initial capital for backtesting
     * @return a {@link SensitivityReport} with per-parameter results and a
     *         global summary
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if bars is empty
     */
    public SensitivityReport analyze(Chromosome chromosome,
                                      BacktestResult baseResult,
                                      List<Bar> bars,
                                      double initialCapital) {
        Objects.requireNonNull(chromosome, "chromosome must not be null");
        Objects.requireNonNull(baseResult, "baseResult must not be null");
        Objects.requireNonNull(bars, "bars must not be null");
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be empty");
        }

        log.info("Starting parameter sensitivity analysis for chromosome: {}", chromosome);

        List<SensitivityResult> results = new ArrayList<>();

        // --- Entry gene periods ---
        List<Gene> entries = chromosome.entryGenes();
        for (int idx = 0; idx < entries.size(); idx++) {
            final int i = idx;
            String name = "entry_" + i + "_period";
            double baseValue = entries.get(i).period();
            SensitivityResult r = analyzeSingleParameter(
                name, baseValue,
                testValue -> {
                    Chromosome mod = chromosome.copy();
                    mod.entryGenes().get(i).setPeriod((int) Math.round(testValue));
                    return mod;
                },
                bars, initialCapital
            );
            results.add(r);
        }

        // --- Exit gene periods ---
        List<Gene> exits = chromosome.exitGenes();
        for (int idx = 0; idx < exits.size(); idx++) {
            final int i = idx;
            String name = "exit_" + i + "_period";
            double baseValue = exits.get(i).period();
            SensitivityResult r = analyzeSingleParameter(
                name, baseValue,
                testValue -> {
                    Chromosome mod = chromosome.copy();
                    mod.exitGenes().get(i).setPeriod((int) Math.round(testValue));
                    return mod;
                },
                bars, initialCapital
            );
            results.add(r);
        }

        // --- Stop-loss ---
        SensitivityResult slResult = analyzeSingleParameter(
            "stopLoss", chromosome.stopLoss(),
            testValue -> {
                Chromosome mod = chromosome.copy();
                mod.stopLoss((int) Math.round(testValue));
                return mod;
            },
            bars, initialCapital
        );
        results.add(slResult);

        // --- Take-profit ---
        SensitivityResult tpResult = analyzeSingleParameter(
            "takeProfit", chromosome.takeProfit(),
            testValue -> {
                Chromosome mod = chromosome.copy();
                mod.takeProfit((int) Math.round(testValue));
                return mod;
            },
            bars, initialCapital
        );
        results.add(tpResult);

        // --- Global stats ---
        List<Double> allSharpes = results.stream()
            .flatMap(r -> r.sharpeValues().stream())
            .toList();

        double globalStdDev = allSharpes.isEmpty() ? 0.0 : stdDev(allSharpes);
        double globalScore = results.stream()
            .mapToDouble(SensitivityResult::stabilityScore)
            .average()
            .orElse(0.0);

        log.info("Sensitivity analysis complete: {} parameters analyzed, global score={:.2f}, global stddev={:.4f}",
            results.size(), globalScore, globalStdDev);

        return new SensitivityReport(Collections.unmodifiableList(results), globalScore, globalStdDev);
    }

    // ---------------------------------------------------------------
    //  Internal helpers
    // ---------------------------------------------------------------

    /**
     * Analyzes one parameter by generating perturbed values, backtesting each,
     * and computing a stability score.
     *
     * @param paramName     display name for this parameter
     * @param baseValue     the original parameter value
     * @param modifier      function that creates a modified chromosome for a given test value
     * @param bars          bar data
     * @param initialCapital initial capital
     * @return the sensitivity result for this parameter
     */
    private SensitivityResult analyzeSingleParameter(
            String paramName,
            double baseValue,
            java.util.function.DoubleFunction<Chromosome> modifier,
            List<Bar> bars,
            double initialCapital) {

        List<Double> testValues = generateTestValues(baseValue);
        List<Double> sharpeValues = new ArrayList<>(testValues.size());

        for (double testVal : testValues) {
            Chromosome modified = modifier.apply(testVal);
            double sharpe = backtestAndGetSharpe(modified, bars, initialCapital);
            sharpeValues.add(sharpe);

            log.debug("  {}={} → Sharpe={:.4f}", paramName, testVal, sharpe);
        }

        double stabilityScore = computeStabilityScore(sharpeValues);

        return new SensitivityResult(
            paramName,
            baseValue,
            Collections.unmodifiableList(testValues),
            Collections.unmodifiableList(sharpeValues),
            stabilityScore
        );
    }

    /**
     * Generates the list of perturbed test values for a parameter.
     *
     * <p>Base value is perturbed by {@link #PERTURBATIONS} and clamped to
     * the valid domain for that parameter type. If two perturbations map
     * to the same integer after rounding, duplicates are removed (the
     * second occurrence is discarded).</p>
     */
    private List<Double> generateTestValues(double baseValue) {
        // Determine clamp bounds: periods live in [2, 200], risk in [0, 500].
        // Use the period range when baseValue falls within it (all period
        // values are [2,200], while risk values can be up to 500).
        double min, max;
        if (baseValue >= MIN_PERIOD && baseValue <= MAX_PERIOD) {
            min = MIN_PERIOD;
            max = MAX_PERIOD;
        } else {
            min = MIN_RISK;
            max = MAX_RISK;
        }

        List<Double> values = new ArrayList<>(PERTURBATIONS.length);
        for (double pct : PERTURBATIONS) {
            double raw = baseValue * (1.0 + pct);
            double clamped = Math.clamp(raw, min, max);
            // Round to integer since all parameters are integral
            double rounded = Math.round(clamped);
            if (rounded == baseValue) {
                // If the perturbation collapses to base, nudge by ±1
                rounded = pct < 0
                    ? Math.max(min, baseValue - 1)
                    : Math.min(max, baseValue + 1);
            }
            // De-duplicate (same integer can arise from ±10% and ±20% on small values)
            if (!values.contains(rounded)) {
                values.add(rounded);
            }
        }

        return values;
    }

    /**
     * Creates a strategy from the chromosome, backtests it, and returns
     * the Sharpe ratio.
     */
    private double backtestAndGetSharpe(Chromosome chromosome,
                                         List<Bar> bars,
                                         double initialCapital) {
        StrategyTemplate strategy = new StrategyTemplate(chromosome);
        BacktestEngine engine = new BacktestEngine(strategy, bars, initialCapital);
        engine.withCommissionFixed(0.0)
            .withCommissionPct(0.0007);
        BacktestResult result = engine.run();
        return result.sharpeRatio();
    }

    /**
     * Computes a stability score from a list of Sharpe ratios.
     *
     * <p>Score = 100 / (1 + stdDev * 5). A standard deviation of 0 → 100,
     * stddev of 0.2 → 50, stddev of 1.0 → ~16.7, stddev of 4+ → ~4.7.</p>
     *
     * <p>This aggressive scaling means any parameter whose Sharpe ratio
     * wobbles by more than 0.2 across perturbations is flagged as fragile.</p>
     */
    private double computeStabilityScore(List<Double> sharpeValues) {
        if (sharpeValues == null || sharpeValues.size() < 2) {
            return 100.0; // not enough data to judge
        }
        double sd = stdDev(sharpeValues);
        // Score: 100 / (1 + 5 * stdDev)
        // sd=0 → 100, sd=0.2 → 50, sd=1.0 → 16.7, sd=4+ → ~4.7
        double score = 100.0 / (1.0 + 5.0 * sd);
        return Math.clamp(score, 0.0, 100.0);
    }

    /**
     * Computes the population standard deviation of a list of values.
     */
    private double stdDev(List<Double> values) {
        if (values == null || values.isEmpty()) return 0.0;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = values.stream()
            .mapToDouble(v -> (v - mean) * (v - mean))
            .average()
            .orElse(0.0);
        return Math.sqrt(variance);
    }
}
