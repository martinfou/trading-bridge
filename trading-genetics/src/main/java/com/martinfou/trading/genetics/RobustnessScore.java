package com.martinfou.trading.genetics;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.MonteCarloSimulation;
import com.martinfou.trading.backtest.WalkForwardOptimizer;

import java.util.List;
import java.util.stream.DoubleStream;

/**
 * A composite robustness score (0–100) that quantifies how likely a trading
 * strategy is to perform well in live / unseen market conditions.
 *
 * <p>The score combines four dimensions, each normalised to 0–100:</p>
 * <table>
 *   <tr><th>Component</th><th>Weight</th><th>Source</th></tr>
 *   <tr><td>Walk-Forward OOS</td><td>40 %</td><td>{@link WalkForwardOptimizer.Result}</td></tr>
 *   <tr><td>Monte Carlo VaR</td><td>30 %</td><td>{@link MonteCarloSimulation.Result}</td></tr>
 *   <tr><td>Sharpe Stability</td><td>20 %</td><td>StdDev of OOS Sharpe across WF windows</td></tr>
 *   <tr><td>Parameter Sensitivity</td><td>10 %</td><td>Walk-Forward efficiency proxy</td></tr>
 * </table>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * WalkForwardOptimizer.Result wf = wfo.run();
 * MonteCarloSimulation.Result mc = sim.run();
 * RobustnessScore score = RobustnessScore.calculate(backtestResult, wf, mc);
 * System.out.println("Overall robustness: " + score.overall());
 * }</pre>
 *
 * @param overall          composite score 0–100
 * @param wfoos            walk-forward out-of-sample sub-score 0–100
 * @param monteCarlo       Monte Carlo VaR sub-score 0–100
 * @param sharpeStability  Sharpe stability sub-score 0–100
 * @param parameterSensitivity  parameter sensitivity sub-score 0–100 (higher = more robust)
 */
public record RobustnessScore(
    double overall,
    double wfoos,
    double monteCarlo,
    double sharpeStability,
    double parameterSensitivity
) {

    /** Weight for the walk-forward OOS component. */
    private static final double WF_WEIGHT = 0.40;

    /** Weight for the Monte Carlo component. */
    private static final double MC_WEIGHT = 0.30;

    /** Weight for the Sharpe stability component. */
    private static final double SS_WEIGHT = 0.20;

    /** Weight for the parameter sensitivity component. */
    private static final double PS_WEIGHT = 0.10;

    // ---------------------------------------------------------------
    //  Factory
    // ---------------------------------------------------------------

    /**
     * Computes a full robustness score from backtest, walk-forward, and
     * Monte Carlo results.
     *
     * @param result    primary backtest result (used for context, e.g. strategy name)
     * @param wfResult  walk-forward optimisation result
     * @param mcResult  Monte Carlo simulation result
     * @return a new {@link RobustnessScore} with all sub-scores and the composite
     */
    public static RobustnessScore calculate(
            BacktestResult result,
            WalkForwardOptimizer.Result wfResult,
            MonteCarloSimulation.Result mcResult) {

        double wfScore   = calculateWfOosScore(wfResult);
        double mcScore   = calculateMonteCarloScore(mcResult);
        double ssScore   = calculateSharpeStability(wfResult);
        double psScore   = calculateParameterSensitivity(wfResult);

        double overall = wfScore * WF_WEIGHT
                       + mcScore * MC_WEIGHT
                       + ssScore * SS_WEIGHT
                       + psScore * PS_WEIGHT;

        overall = clamp(overall);

        return new RobustnessScore(overall, wfScore, mcScore, ssScore, psScore);
    }

    // ---------------------------------------------------------------
    //  Walk-Forward OOS score (40%)
    // ---------------------------------------------------------------

    /**
     * Scores the out-of-sample performance from walk-forward analysis.
     *
     * <p>Factors:</p>
     * <ul>
     *   <li>Average OOS Sharpe (0→0, 1→60, 2→90, 3→100)</li>
     *   <li>Walk-forward efficiency (how much of IS is preserved in OOS)</li>
     *   <li>Percentage of windows with positive OOS returns</li>
     * </ul>
     */
    private static double calculateWfOosScore(WalkForwardOptimizer.Result wf) {
        if (wf == null || wf.windowCount() < 1) return 0.0;

        // --- Sharpe component (0–40 points) ---
        double avgSharpe = Math.max(0, wf.avgOosSharpe());
        double sharpeScore;
        if (avgSharpe >= 3.0) sharpeScore = 40;
        else if (avgSharpe >= 2.0) sharpeScore = 36;
        else if (avgSharpe >= 1.5) sharpeScore = 30;
        else if (avgSharpe >= 1.0) sharpeScore = 24;
        else if (avgSharpe >= 0.5) sharpeScore = 16;
        else sharpeScore = avgSharpe / 0.5 * 8;

        // --- Efficiency component (0–35 points) ---
        double efficiency = Math.max(0, Math.min(2.0, wf.walkForwardEfficiency()));
        double effScore = efficiency / 2.0 * 35;

        // --- OOS win rate component (0–25 points) ---
        double winRate = wf.oosWinRate();  // 0–100 %
        double winScore = winRate / 100.0 * 25;

        return clamp(sharpeScore + effScore + winScore);
    }

    // ---------------------------------------------------------------
    //  Monte Carlo score (30%)
    // ---------------------------------------------------------------

    /**
     * Scores the Monte Carlo simulation results.
     *
     * <p>Factors:</p>
     * <ul>
     *   <li>Probability of loss (low = good)</li>
     *   <li>VaR 95th percentile (less negative = better)</li>
     *   <li>Median Sharpe across runs</li>
     * </ul>
     */
    private static double calculateMonteCarloScore(MonteCarloSimulation.Result mc) {
        if (mc == null || mc.totalRuns() < 1) return 0.0;

        double initialCapital = mc.initialCapital();

        // --- Loss probability component (0–40 points) ---
        double lossProb = mc.probabilityOfLoss(); // 0–100 %
        double lossScore = Math.max(0, 40 - lossProb * 0.4);

        // --- VaR component (0–35 points) ---
        double var95 = mc.var95(); // could be negative
        double varScore;
        if (initialCapital <= 0) {
            varScore = var95 >= 0 ? 35 : Math.max(0, 35 + var95 / 1000);
        } else {
            double varRatio = var95 / initialCapital; // e.g. -0.05 = 5% loss at VaR
            // varRatio >= 0 → 35 | -0.1 → 17.5 | -0.2 → 0
            varScore = Math.max(0, 35 + varRatio * 175);
        }

        // --- Median Sharpe component (0–25 points) ---
        double medSharpe = Math.max(0, mc.medianSharpe());
        double medSharpeScore = Math.min(25, medSharpe / 3.0 * 25);

        return clamp(lossScore + varScore + medSharpeScore);
    }

    // ---------------------------------------------------------------
    //  Sharpe Stability (20%)
    // ---------------------------------------------------------------

    /**
     * Scores the stability of the Sharpe ratio across walk-forward windows.
     *
     * <p>A low coefficient of variation (std/mean) of OOS Sharpe across
     * windows indicates the strategy performs consistently.</p>
     *
     * <p>If fewer than 2 windows exist, we fall back to a simple score
     * based on the walk-forward efficiency.</p>
     */
    private static double calculateSharpeStability(WalkForwardOptimizer.Result wf) {
        if (wf == null || wf.windowCount() < 1) return 50.0;

        List<WalkForwardOptimizer.WindowResult> windows = wf.windows();

        if (windows.size() < 2) {
            // Not enough windows to compute stability; fall back to efficiency
            double eff = Math.max(0, Math.min(1.0, wf.walkForwardEfficiency()));
            return eff * 80.0;
        }

        double[] oosSharpes = windows.stream()
            .mapToDouble(WalkForwardOptimizer.WindowResult::oosSharpe)
            .toArray();

        double mean = DoubleStream.of(oosSharpes).average().orElse(0);
        double variance = DoubleStream.of(oosSharpes)
            .map(s -> (s - mean) * (s - mean))
            .average()
            .orElse(0);
        double std = Math.sqrt(variance);

        // Coefficient of variation (CV = std/|mean|)
        double cv;
        if (Math.abs(mean) < 0.01) {
            // Near-zero mean → use absolute std directly
            cv = std / 100.0; // arbitrary normalisation
        } else {
            cv = std / Math.abs(mean);
        }

        // CV of 0 → 100, CV of 1 → 40, CV of 3+ → 0
        double stabilityScore = 100.0 / (1.0 + cv * 1.5);

        return clamp(stabilityScore);
    }

    // ---------------------------------------------------------------
    //  Parameter Sensitivity (10%)
    // ---------------------------------------------------------------

    /**
     * Scores how sensitive the strategy is to parameter changes, proxied
     * by the walk-forward efficiency.
     *
     * <p>Efficiency close to 1.0 (IS = OOS) suggests the strategy is not
     * overfit and is stable across parameter regimes.</p>
     */
    private static double calculateParameterSensitivity(WalkForwardOptimizer.Result wf) {
        if (wf == null || wf.windowCount() < 1) return 50.0;

        double efficiency = wf.walkForwardEfficiency();

        // efficiency = 0 → score 0
        // efficiency = 0.25 → score 25
        // efficiency = 0.5 → score 50
        // efficiency = 0.75 → score 75
        // efficiency = 0.9+ → score 90–100
        // cap at 1.5
        double eff = Math.max(0, Math.min(1.5, efficiency));
        double score = eff / 1.5 * 100;

        return clamp(score);
    }

    // ---------------------------------------------------------------
    //  Utility
    // ---------------------------------------------------------------

    /**
     * Clamps a value to the [0, 100] range.
     */
    private static double clamp(double value) {
        return Math.max(0, Math.min(100, value));
    }

    /**
     * Returns a human-readable label for the robustness bracket.
     *
     * @return "Excellent", "Good", "Fair", "Poor", or "Very Poor"
     */
    public String label() {
        if (overall >= 85) return "Excellent";
        if (overall >= 70) return "Good";
        if (overall >= 50) return "Fair";
        if (overall >= 30) return "Poor";
        return "Very Poor";
    }

    /**
     * Returns the emoji / colour indicator for dashboard display.
     *
     * @return "🟢", "🟡", "🟠", or "🔴"
     */
    public String indicator() {
        if (overall >= 70) return "🟢";
        if (overall >= 50) return "🟡";
        if (overall >= 30) return "🟠";
        return "🔴";
    }

    @Override
    public String toString() {
        return String.format("RobustnessScore{overall=%.1f, wfoos=%.1f, mc=%.1f, stability=%.1f, sensitivity=%.1f, label=%s}",
            overall, wfoos, monteCarlo, sharpeStability, parameterSensitivity, label());
    }
}
