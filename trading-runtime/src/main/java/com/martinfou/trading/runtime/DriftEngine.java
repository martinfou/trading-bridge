package com.martinfou.trading.runtime;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.core.metrics.SharpeRatio;
import com.martinfou.trading.core.metrics.ProfitFactor;
import com.martinfou.trading.core.metrics.WinLossRatio;
import com.martinfou.trading.core.metrics.MaxDrawdown;
import com.martinfou.trading.core.metrics.SortinoRatio;
import com.martinfou.trading.core.metrics.CalmarRatio;
import com.martinfou.trading.backtest.persistence.BacktestRunDetails;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * FR-15 drift engine with broker-only gating (Story 17.12 / PS-GR14).
 * Evaluates live/paper broker performance against promote baseline backtest.
 */
public final class DriftEngine {

    private final DriftThresholds thresholds;

    public DriftEngine() {
        this(DriftThresholds.loadDefault());
    }

    DriftEngine(DriftThresholds thresholds) {
        this.thresholds = thresholds != null ? thresholds : DriftThresholds.DEFAULT;
    }

    public record BrokerObservation(
        String runId,
        ExecutionLabel label,
        Instant startedAt,
        String configHash,
        List<Trade> trades,
        RunConfigSnapshot config
    ) {
        public int tradeCount() {
            return trades.size();
        }
    }

    public record StrategyDriftInput(
        String strategyId,
        Optional<ExecutionLabel> deploymentLabel,
        Optional<BacktestRunDetails> baseline,
        List<Trade> baselineTrades,
        List<BrokerObservation> brokerObservations,
        Instant evaluatedAt
    ) {}

    public DriftEvaluation evaluate(StrategyDriftInput input) {
        Instant now = input.evaluatedAt() != null ? input.evaluatedAt() : Instant.now();

        if (input.deploymentLabel().isPresent() && !input.deploymentLabel().get().isBrokerBacked()) {
            return insufficient(
                input.strategyId(),
                input.deploymentLabel(),
                "Drift requires broker execution deployment (not "
                    + input.deploymentLabel().get().name() + ")",
                now);
        }

        List<BrokerObservation> brokerRuns = input.brokerObservations().stream()
            .filter(obs -> obs.label().isBrokerBacked())
            .toList();

        if (brokerRuns.isEmpty()) {
            return insufficient(
                input.strategyId(),
                input.deploymentLabel(),
                "No broker execution history — drift not computed on BACKTEST or PAPER_STUB alone",
                now);
        }

        int totalTrades = brokerRuns.stream().mapToInt(BrokerObservation::tradeCount).sum();
        Instant earliest = brokerRuns.stream()
            .map(BrokerObservation::startedAt)
            .min(Comparator.naturalOrder())
            .orElse(now);
        long observationDays = Math.max(0, ChronoUnit.DAYS.between(earliest, now));

        // Story 37-5: Minimum Sample Guard (return INSUFFICIENT if < 15 trades and < 7 days of data)
        if (totalTrades < 15 && observationDays < 7) {
            return insufficient(
                input.strategyId(),
                input.deploymentLabel(),
                "INSUFFICIENT_DATA: Broker drift requires ≥15 trades or ≥7 days of data (have "
                    + observationDays + " days, " + totalTrades + " trades)",
                now);
        }

        Optional<BacktestRunDetails> baselineOpt = input.baseline();
        if (baselineOpt.isEmpty()) {
            return insufficient(
                input.strategyId(),
                input.deploymentLabel(),
                "No promote baseline backtest details for comparison",
                now);
        }
        BacktestRunDetails baseline = baselineOpt.get();

        List<DriftMetricSignal> signals = new ArrayList<>();
        int redDimensions = 0;

        // Story 37-9: Timeframe Mismatch Check (Skip comparison, return REVIEW_PARAMS warning)
        if (baseline.toSummary().createdAt() != null || baseline.parameters() != null) {
            // Find if any run config timeframe is different
            boolean timeframeMismatch = brokerRuns.stream()
                .anyMatch(obs -> obs.config().strategyTimeframe() != null
                    && baseline.toSummary().parameters() != null // parameters often encode tf
                    && !obs.config().strategyTimeframe().isEmpty());
            
            // For safety, let's inspect strategyTimeframe directly on RunConfigSnapshot
            String baselineTf = brokerRuns.isEmpty() ? null : brokerRuns.get(0).config().strategyTimeframe();
            if (baselineTf != null && !baselineTf.isEmpty()) {
                boolean mismatch = brokerRuns.stream()
                    .anyMatch(obs -> obs.config().strategyTimeframe() != null
                        && !obs.config().strategyTimeframe().equalsIgnoreCase(baselineTf));
                if (mismatch) {
                    signals.add(new DriftMetricSignal("TIMEFRAME_MISMATCH", 1.0, 0.0, "TIMEFRAME_MISMATCH", true, now));
                    return new DriftEvaluation(
                        input.strategyId(),
                        DriftRecommendation.REVIEW_PARAMS,
                        "TIMEFRAME_MISMATCH",
                        "TIMEFRAME_MISMATCH: Strategy timeframe deviates between active runs",
                        List.copyOf(signals),
                        now,
                        input.deploymentLabel()
                    );
                }
            }
        }

        // Story 37-8: Parameter Hash Mismatch Check
        if (baseline.parameterHash() != null) {
            String baselineHash = baseline.parameterHash();
            boolean configMismatch = brokerRuns.stream()
                .anyMatch(obs -> obs.configHash() != null && !obs.configHash().equals(baselineHash));
            if (configMismatch) {
                signals.add(new DriftMetricSignal(
                    "config_mismatch",
                    1.0,
                    0.0,
                    "CONFIG_MISMATCH",
                    true,
                    now));
                redDimensions++;
            }
        }

        // Aggregate actual trades
        List<Trade> actualTrades = new ArrayList<>();
        for (BrokerObservation obs : brokerRuns) {
            actualTrades.addAll(obs.trades());
        }

        // Run ComparisonEngine statistical evaluations
        double btCommission = baseline.totalCommission();
        double btSlippage = baseline.totalSlippage();
        double actualCommPerTrade = brokerRuns.stream().mapToDouble(obs -> obs.config().commissionPerTrade() != null ? obs.config().commissionPerTrade() : 0.0).average().orElse(0.0);
        double actualSlippagePct = brokerRuns.stream().mapToDouble(obs -> obs.config().slippagePct() != null ? obs.config().slippagePct() : 0.0).average().orElse(0.0);

        ComparisonEngine.ComparisonResult comp = ComparisonEngine.compare(
            input.baselineTrades(),
            actualTrades,
            btCommission,
            btSlippage,
            actualCommPerTrade,
            actualSlippagePct
        );

        // Story 37-1: Compare Drawdown
        double liveMaxDrawdown = MaxDrawdown.of(actualTrades);
        double baselineDd = baseline.maxDrawdownPct();
        double effectiveBaselineDd = baselineDd > 0.0 ? baselineDd : 2.0;
        double reviewThreshold = effectiveBaselineDd * thresholds.drawdownReviewMultiplier();
        double pauseThreshold = Math.max(
            effectiveBaselineDd * thresholds.drawdownPauseMultiplier(),
            effectiveBaselineDd);
        if (liveMaxDrawdown >= pauseThreshold) {
            signals.add(metric("max_drawdown_pct", liveMaxDrawdown, pauseThreshold, "DRAWDOWN", now));
            redDimensions += 2;
        } else if (liveMaxDrawdown >= reviewThreshold) {
            signals.add(metric("max_drawdown_pct", liveMaxDrawdown, reviewThreshold, "DRAWDOWN", now));
            redDimensions++;
        }

        // Story 37-1: Compare Win Rate
        double baselineWinRate = baseline.winRatePct();
        double liveWinRate = WinLossRatio.of(actualTrades) * 100.0;
        double wrDelta = baselineWinRate - liveWinRate;
        if (wrDelta >= thresholds.winRatePauseDeltaPts()) {
            signals.add(metric("win_rate_pct", liveWinRate, baselineWinRate - thresholds.winRatePauseDeltaPts(), "WIN_RATE", now));
            redDimensions += 2;
        } else if (wrDelta >= thresholds.winRateReviewDeltaPts()) {
            signals.add(metric("win_rate_pct", liveWinRate, baselineWinRate - thresholds.winRateReviewDeltaPts(), "WIN_RATE", now));
            redDimensions++;
        }

        // Story 37-1: Compare Sharpe Ratio
        double baselineSharpe = baseline.sharpeRatio();
        double liveSharpe = SharpeRatio.of(actualTrades);
        if (liveSharpe < baselineSharpe - 0.5) {
            signals.add(metric("sharpe_ratio", liveSharpe, baselineSharpe - 0.5, "SHARPE_RATIO", now));
            redDimensions++;
        }

        // Story 37-1: Compare Profit Factor
        double baselinePF = baseline.profitFactor();
        double livePF = ProfitFactor.of(actualTrades);
        if (livePF < 1.0) {
            signals.add(metric("profit_factor", livePF, 1.0, "PROFIT_FACTOR", now));
            redDimensions += 2;
        } else if (livePF < baselinePF * 0.8) {
            signals.add(metric("profit_factor", livePF, baselinePF * 0.8, "PROFIT_FACTOR", now));
            redDimensions++;
        }

        // Story 37-1: Compare Sortino Ratio
        double baselineSortino = baseline.sortinoRatio();
        double liveSortino = SortinoRatio.of(actualTrades);
        if (liveSortino < baselineSortino - 0.5) {
            signals.add(metric("sortino_ratio", liveSortino, baselineSortino - 0.5, "SORTINO_RATIO", now));
            redDimensions++;
        }

        // Story 37-1: Compare Calmar Ratio
        double baselineCalmar = baseline.calmarRatio();
        double liveCalmar = CalmarRatio.of(actualTrades);
        if (liveCalmar < baselineCalmar - 0.5) {
            signals.add(metric("calmar_ratio", liveCalmar, baselineCalmar - 0.5, "CALMAR_RATIO", now));
            redDimensions++;
        }

        // Story 37-1: Compare Average Trade P&L
        double baselineAvgPnl = baseline.avgTradePnl();
        double liveAvgPnl = actualTrades.stream().mapToDouble(Trade::pnl).average().orElse(0.0);
        if (liveAvgPnl < 0.0) {
            signals.add(metric("avg_trade_pnl", liveAvgPnl, 0.0, "AVERAGE_PNL", now));
            redDimensions += 2;
        } else if (liveAvgPnl < baselineAvgPnl * 0.2) {
            signals.add(metric("avg_trade_pnl", liveAvgPnl, baselineAvgPnl * 0.2, "AVERAGE_PNL", now));
            redDimensions++;
        }

        // Story 37-11 to 37-13: KS Test and Pearson Equity Curve Correlation
        if (comp.ksSignificant01()) {
            signals.add(metric("ks_test_pnl_distribution", comp.ksStatistic(), 0.05, "STATISTICAL_DISTRIBUTION", now));
            redDimensions += 2;
        } else if (comp.ksSignificant05()) {
            signals.add(metric("ks_test_pnl_distribution", comp.ksStatistic(), 0.05, "STATISTICAL_DISTRIBUTION", now));
            redDimensions++;
        }

        if (comp.pearsonCorrelation() < 0.2) {
            signals.add(metric("pearson_correlation", comp.pearsonCorrelation(), 0.2, "EQUITY_CORRELATION", now));
            redDimensions += 2;
        } else if (comp.pearsonCorrelation() < 0.5) {
            signals.add(metric("pearson_correlation", comp.pearsonCorrelation(), 0.5, "EQUITY_CORRELATION", now));
            redDimensions++;
        }

        // Story 37-14: Slippage / Commission Cost Drift Check
        if (comp.costDriftExceeded()) {
            signals.add(metric("cost_drift", 1.0, 0.0, "COST_DRIFT", now));
            redDimensions++;
        }

        java.util.Map<String, Object> compMetrics = new java.util.LinkedHashMap<>();
        compMetrics.put("baselineWinRate", baselineWinRate);
        compMetrics.put("liveWinRate", liveWinRate);
        compMetrics.put("baselineSharpe", baselineSharpe);
        compMetrics.put("liveSharpe", liveSharpe);
        compMetrics.put("baselinePF", baselinePF);
        compMetrics.put("livePF", livePF);
        compMetrics.put("baselineSortino", baselineSortino);
        compMetrics.put("liveSortino", liveSortino);
        compMetrics.put("baselineCalmar", baselineCalmar);
        compMetrics.put("liveCalmar", liveCalmar);
        compMetrics.put("baselineAvgPnl", baselineAvgPnl);
        compMetrics.put("liveAvgPnl", liveAvgPnl);
        compMetrics.put("pearsonCorrelation", comp.pearsonCorrelation());
        compMetrics.put("ksStatistic", comp.ksStatistic());
        compMetrics.put("ksSignificant05", comp.ksSignificant05());
        compMetrics.put("ksSignificant01", comp.ksSignificant01());
        compMetrics.put("costDriftExceeded", comp.costDriftExceeded());

        DriftRecommendation recommendation = compositeRecommendation(redDimensions);
        String reason = recommendation == DriftRecommendation.HOLD
            ? "Broker performance within baseline thresholds"
            : recommendation.name() + " — " + redDimensions + " dimension(s) breached";

        return new DriftEvaluation(
            input.strategyId(),
            recommendation,
            "BROKER",
            reason,
            List.copyOf(signals),
            now,
            input.deploymentLabel(),
            compMetrics);
    }

    private static DriftEvaluation insufficient(
        String strategyId,
        Optional<ExecutionLabel> deploymentLabel,
        String reason,
        Instant now
    ) {
        return new DriftEvaluation(
            strategyId,
            DriftRecommendation.HOLD,
            "INSUFFICIENT",
            reason,
            List.of(),
            now,
            deploymentLabel);
    }

    private static DriftRecommendation compositeRecommendation(int redDimensions) {
        if (redDimensions >= 2) {
            return DriftRecommendation.PAUSE;
        }
        if (redDimensions == 1) {
            return DriftRecommendation.REVIEW_PARAMS;
        }
        return DriftRecommendation.HOLD;
    }

    private static DriftMetricSignal metric(
        String name,
        double value,
        double threshold,
        String dimension,
        Instant timestamp
    ) {
        return new DriftMetricSignal(name, value, threshold, dimension, true, timestamp);
    }

    private static double observationDrawdownPct(BrokerObservation observation) {
        double dd = MaxDrawdown.of(observation.trades());
        if (dd > 0.0) {
            return dd;
        }
        double ret = observation.trades().stream().mapToDouble(Trade::pnl).sum();
        return ret < 0.0 ? Math.abs(ret) : 0.0;
    }
}
