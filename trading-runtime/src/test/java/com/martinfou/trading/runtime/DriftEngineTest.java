package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriftEngineTest {

    private static final DriftThresholds THRESHOLDS = DriftThresholds.DEFAULT;
    private static final Instant NOW = Instant.parse("2024-06-15T12:00:00Z");

    private final DriftEngine engine = new DriftEngine(THRESHOLDS);

    @Test
    void evaluate_stubDeployment_returnsInsufficientHold() {
        DriftEvaluation result = engine.evaluate(input(
            Optional.of(ExecutionLabel.PAPER_STUB),
            Optional.of(baseline(10, 5.0, 8.0)),
            List.of(),
            NOW));

        assertEquals(DriftRecommendation.HOLD, result.recommendation());
        assertEquals("INSUFFICIENT", result.dataSource());
        assertTrue(result.reason().contains("broker execution deployment"));
    }

    @Test
    void evaluate_backtestOnlyHistory_returnsInsufficientHold() {
        DriftEvaluation result = engine.evaluate(input(
            Optional.of(ExecutionLabel.PAPER_OANDA),
            Optional.of(baseline(30, 12.0, 10.0)),
            List.of(),
            NOW));

        assertEquals(DriftRecommendation.HOLD, result.recommendation());
        assertEquals("INSUFFICIENT", result.dataSource());
        assertTrue(result.reason().contains("No broker execution history"));
    }

    @Test
    void evaluate_brokerWithoutMinData_returnsInsufficientHold() {
        DriftEvaluation result = engine.evaluate(input(
            Optional.of(ExecutionLabel.PAPER_OANDA),
            Optional.of(baseline(30, 12.0, 10.0)),
            List.of(brokerRun("run-1", ExecutionLabel.PAPER_OANDA,
                NOW.minusSeconds(86_400), metrics(5, -2.0, 3.0), 5)),
            NOW));

        assertEquals(DriftRecommendation.HOLD, result.recommendation());
        assertEquals("INSUFFICIENT", result.dataSource());
        assertTrue(result.reason().contains("≥14 days or ≥20 trades"));
    }

    @Test
    void evaluate_zeroBaselineDrawdown_usesFloorForLiveDrawdownSignal() {
        DriftEvaluation result = engine.evaluate(input(
            Optional.of(ExecutionLabel.PAPER_OANDA),
            Optional.of(baseline(30, 12.0, 0.0)),
            List.of(brokerRun("run-paper", ExecutionLabel.PAPER_OANDA,
                NOW.minusSeconds(86_400L * 20), metrics(25, -5.0, 8.0), 25)),
            NOW));

        assertEquals(DriftRecommendation.REVIEW_PARAMS, result.recommendation());
        assertTrue(result.signals().stream().anyMatch(s -> "DRAWDOWN".equals(s.dimension())));
    }

    @Test
    void evaluate_brokerDrawdownBreach_returnsReviewWithBrokerSource() {
        DriftEvaluation result = engine.evaluate(input(
            Optional.of(ExecutionLabel.LIVE_OANDA),
            Optional.of(baseline(40, 15.0, 10.0)),
            List.of(brokerRun("run-live", ExecutionLabel.LIVE_OANDA,
                NOW.minusSeconds(86_400L * 20), metrics(25, -8.0, 16.0), 25)),
            NOW));

        assertEquals(DriftRecommendation.REVIEW_PARAMS, result.recommendation());
        assertEquals("BROKER", result.dataSource());
        assertEquals(1, result.signals().size());
        assertEquals("DRAWDOWN", result.signals().getFirst().dimension());
    }

    @Test
    void evaluate_twoRedDimensions_returnsPause() {
        DriftEvaluation result = engine.evaluate(new DriftEngine.StrategyDriftInput(
            "LondonOpenRangeBreakout",
            Optional.of(ExecutionLabel.PAPER_OANDA),
            Optional.of(baseline(40, 55.0, 10.0)),
            Optional.of("baseline-hash"),
            List.of(brokerRun("run-a", ExecutionLabel.PAPER_OANDA,
                NOW.minusSeconds(86_400L * 20),
                metrics(25, -25.0, 22.0, 30.0),
                25,
                "other-hash")),
            NOW));

        assertEquals(DriftRecommendation.PAUSE, result.recommendation());
        assertEquals("BROKER", result.dataSource());
        assertTrue(result.signals().size() >= 2);
    }

    private static DriftEngine.StrategyDriftInput input(
        Optional<ExecutionLabel> deploymentLabel,
        Optional<BacktestRunMetrics> baseline,
        List<DriftEngine.BrokerObservation> brokerRuns,
        Instant now
    ) {
        return new DriftEngine.StrategyDriftInput(
            "LondonOpenRangeBreakout",
            deploymentLabel,
            baseline,
            Optional.of("baseline-hash"),
            brokerRuns,
            now);
    }

    private static BacktestRunMetrics baseline(int trades, double returnPct, double maxDd) {
        return new BacktestRunMetrics(trades, returnPct, maxDd, 55.0);
    }

    private static BacktestRunMetrics metrics(int trades, double returnPct, double maxDd) {
        return metrics(trades, returnPct, maxDd, null);
    }

    private static BacktestRunMetrics metrics(int trades, double returnPct, double maxDd, Double winRate) {
        return new BacktestRunMetrics(trades, returnPct, maxDd, winRate);
    }

    private static DriftEngine.BrokerObservation brokerRun(
        String runId,
        ExecutionLabel label,
        Instant startedAt,
        BacktestRunMetrics metrics,
        int tradeCount
    ) {
        return brokerRun(runId, label, startedAt, metrics, tradeCount, "baseline-hash");
    }

    private static DriftEngine.BrokerObservation brokerRun(
        String runId,
        ExecutionLabel label,
        Instant startedAt,
        BacktestRunMetrics metrics,
        int tradeCount,
        String configHash
    ) {
        return new DriftEngine.BrokerObservation(runId, label, startedAt, configHash, metrics, tradeCount);
    }
}
