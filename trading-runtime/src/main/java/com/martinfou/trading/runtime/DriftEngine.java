package com.martinfou.trading.runtime;

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
        BacktestRunMetrics metrics,
        int tradeCount
    ) {}

    public record StrategyDriftInput(
        String strategyId,
        Optional<ExecutionLabel> deploymentLabel,
        Optional<BacktestRunMetrics> baseline,
        Optional<String> baselineConfigHash,
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

        if (observationDays < thresholds.minObservationDays()
            && totalTrades < thresholds.minTradesBeforeSignal()) {
            return insufficient(
                input.strategyId(),
                input.deploymentLabel(),
                "Broker drift requires ≥" + thresholds.minObservationDays()
                    + " days or ≥" + thresholds.minTradesBeforeSignal() + " trades (have "
                    + observationDays + " days, " + totalTrades + " trades)",
                now);
        }

        Optional<BacktestRunMetrics> baselineOpt = input.baseline();
        if (baselineOpt.isEmpty()) {
            return insufficient(
                input.strategyId(),
                input.deploymentLabel(),
                "No promote baseline backtest metrics for comparison",
                now);
        }
        BacktestRunMetrics baseline = baselineOpt.get();

        List<DriftMetricSignal> signals = new ArrayList<>();
        int redDimensions = 0;

        if (input.baselineConfigHash().isPresent()) {
            String baselineHash = input.baselineConfigHash().get();
            boolean configMismatch = brokerRuns.stream()
                .anyMatch(obs -> obs.configHash() != null && !obs.configHash().equals(baselineHash));
            if (configMismatch) {
                signals.add(new DriftMetricSignal(
                    "config_hash_mismatch",
                    1.0,
                    0.0,
                    "CONFIG",
                    true,
                    now));
                redDimensions++;
            }
        }

        double liveMaxDrawdown = brokerRuns.stream()
            .mapToDouble(DriftEngine::observationDrawdownPct)
            .max()
            .orElse(0.0);
        double baselineDd = baseline.maxDrawdownPct();
        double effectiveBaselineDd = baselineDd > 0.0 ? baselineDd : 2.0;
        if (effectiveBaselineDd > 0.0) {
            double reviewThreshold = effectiveBaselineDd * thresholds.drawdownReviewMultiplier();
            double pauseThreshold = Math.max(
                effectiveBaselineDd * thresholds.drawdownPauseMultiplier(),
                effectiveBaselineDd);
            if (liveMaxDrawdown >= pauseThreshold) {
                signals.add(metric("max_drawdown_pct", liveMaxDrawdown, pauseThreshold, "DRAWDOWN", now));
                redDimensions++;
            } else if (liveMaxDrawdown >= reviewThreshold) {
                signals.add(metric("max_drawdown_pct", liveMaxDrawdown, reviewThreshold, "DRAWDOWN", now));
                redDimensions++;
            }
        }

        Double baselineWinRate = baseline.winRatePct();
        Double liveWinRate = weightedWinRate(brokerRuns);
        if (baselineWinRate != null && liveWinRate != null && totalTrades >= 15) {
            double delta = baselineWinRate - liveWinRate;
            if (delta >= thresholds.winRatePauseDeltaPts()) {
                signals.add(metric("win_rate_pct", liveWinRate, baselineWinRate - thresholds.winRatePauseDeltaPts(),
                    "WIN_RATE", now));
                redDimensions++;
            } else if (delta >= thresholds.winRateReviewDeltaPts()) {
                signals.add(metric("win_rate_pct", liveWinRate, baselineWinRate - thresholds.winRateReviewDeltaPts(),
                    "WIN_RATE", now));
                redDimensions++;
            }
        }

        if (baseline.totalTrades() > 0 && observationDays >= thresholds.minObservationDays()) {
            double expectedTrades = baseline.totalTrades()
                * (observationDays / (double) thresholds.rollingWindowDays());
            if (expectedTrades > 0 && totalTrades < expectedTrades * thresholds.tradeVolumeReviewRatio()) {
                signals.add(metric("trade_count", totalTrades, expectedTrades * thresholds.tradeVolumeReviewRatio(),
                    "TRADE_VOLUME", now));
                redDimensions++;
            }
        }

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
            input.deploymentLabel());
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
        double dd = observation.metrics().maxDrawdownPct();
        if (dd > 0.0) {
            return dd;
        }
        double ret = observation.metrics().totalReturnPct();
        return ret < 0.0 ? Math.abs(ret) : 0.0;
    }

    private static Double weightedWinRate(List<BrokerObservation> observations) {
        double weightedSum = 0.0;
        int weight = 0;
        for (BrokerObservation observation : observations) {
            Double winRate = winRateFromMetrics(observation.metrics());
            if (winRate == null) {
                continue;
            }
            int trades = Math.max(1, observation.tradeCount());
            weightedSum += winRate * trades;
            weight += trades;
        }
        return weight > 0 ? weightedSum / weight : null;
    }

    private static Double winRateFromMetrics(BacktestRunMetrics metrics) {
        return metrics.winRatePct();
    }
}
