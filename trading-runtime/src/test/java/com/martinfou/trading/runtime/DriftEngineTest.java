package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.backtest.persistence.BacktestRunDetails;

import java.time.Instant;
import java.util.ArrayList;
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
            Optional.of(baseline(55.0, 8.0, "baseline-hash")),
            List.of(),
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
            Optional.of(baseline(55.0, 10.0, "baseline-hash")),
            List.of(),
            List.of(),
            NOW));

        assertEquals(DriftRecommendation.HOLD, result.recommendation());
        assertEquals("INSUFFICIENT", result.dataSource());
        assertTrue(result.reason().contains("No broker execution history"));
    }

    @Test
    void evaluate_brokerWithoutMinData_returnsInsufficientHold() {
        // Less than 15 trades and less than 7 days
        DriftEvaluation result = engine.evaluate(input(
            Optional.of(ExecutionLabel.PAPER_OANDA),
            Optional.of(baseline(55.0, 10.0, "baseline-hash")),
            List.of(),
            List.of(brokerRun("run-1", ExecutionLabel.PAPER_OANDA,
                NOW.minusSeconds(86_400), createTrades(5, -2.0), "baseline-hash", "H1")),
            NOW));

        assertEquals(DriftRecommendation.HOLD, result.recommendation());
        assertEquals("INSUFFICIENT", result.dataSource());
        assertTrue(result.reason().contains("INSUFFICIENT_DATA"));
    }

    @Test
    void evaluate_zeroBaselineDrawdown_usesFloorForLiveDrawdownSignal() {
        // 25 trades (exceeds min 15)
        // 5 losses of 350 = 1750 drawdown (3.5% of 50000 capital)
        // Baseline drawdown = 0.0 -> floor 2.0% -> review 3.0%, pause 4.0%. Drawdown is 3.5% (triggers review).
        DriftEvaluation result = engine.evaluate(input(
            Optional.of(ExecutionLabel.PAPER_OANDA),
            Optional.of(baseline(55.0, 0.0, "baseline-hash")),
            createBalancedTrades(20, 150.0, 5, 350.0),
            List.of(brokerRun("run-paper", ExecutionLabel.PAPER_OANDA,
                NOW.minusSeconds(86_400L * 20), createBalancedTrades(20, 150.0, 5, 350.0), "baseline-hash", "H1")),
            NOW));

        assertEquals(DriftRecommendation.REVIEW_PARAMS, result.recommendation());
        assertTrue(result.signals().stream().anyMatch(s -> "DRAWDOWN".equals(s.dimension())));
    }

    @Test
    void evaluate_brokerDrawdownBreach_returnsReviewWithBrokerSource() {
        // Baseline drawdown = 10.0% -> review 15.0%, pause 20.0%.
        // 10 losses of 850 = 8500 drawdown (17.0% of 50000 capital) -> triggers review.
        List<Trade> trades = createBalancedTrades(15, 700.0, 10, 850.0);
        DriftEvaluation result = engine.evaluate(input(
            Optional.of(ExecutionLabel.LIVE_OANDA),
            Optional.of(baseline(55.0, 10.0, "baseline-hash")),
            trades,
            List.of(brokerRun("run-live", ExecutionLabel.LIVE_OANDA,
                NOW.minusSeconds(86_400L * 20), trades, "baseline-hash", "H1")),
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
            Optional.of(baseline(55.0, 10.0, "baseline-hash")),
            createBalancedTrades(20, 150.0, 5, 100.0),
            List.of(brokerRun("run-a", ExecutionLabel.PAPER_OANDA,
                NOW.minusSeconds(86_400L * 20),
                createBalancedTrades(15, 700.0, 10, 850.0), // drawdown breach
                "other-hash", // config mismatch breach
                "H1")),
            NOW));

        assertEquals(DriftRecommendation.PAUSE, result.recommendation());
        assertEquals("BROKER", result.dataSource());
        assertTrue(result.signals().size() >= 2);
    }

    private static DriftEngine.StrategyDriftInput input(
        Optional<ExecutionLabel> deploymentLabel,
        Optional<BacktestRunDetails> baseline,
        List<Trade> baselineTrades,
        List<DriftEngine.BrokerObservation> brokerRuns,
        Instant now
    ) {
        return new DriftEngine.StrategyDriftInput(
            "LondonOpenRangeBreakout",
            deploymentLabel,
            baseline,
            baselineTrades,
            brokerRuns,
            now);
    }

    private static BacktestRunDetails baseline(double winRate, double maxDd, String paramHash) {
        return new BacktestRunDetails(
            "source-run-id",
            "LondonOpenRangeBreakout",
            "EUR_USD",
            Instant.EPOCH,
            Instant.EPOCH,
            "{}",
            paramHash,
            50000.0,
            55000.0,
            5000.0,
            10.0,
            100,
            55,
            45,
            winRate,
            maxDd,
            50.0,
            0.1, // sharpeRatio
            0.1, // sortinoRatio
            1.5, // profitFactor
            0.1, // calmarRatio
            0.0,
            0.0,
            "[]",
            Instant.EPOCH
        );
    }

    private static List<Trade> createTrades(int count, double pnlValue) {
        List<Trade> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new Trade("EUR_USD", Order.Side.BUY, 1.1000, 1.1010, 1000.0, Instant.now(), Instant.now(), 1.0, 0.0, 0.0) {
                @Override public double pnl() { return pnlValue; }
            });
        }
        return list;
    }

    private static List<Trade> createBalancedTrades(int wins, double winPnl, int losses, double lossPnl) {
        List<Trade> list = new ArrayList<>();
        for (int i = 0; i < losses; i++) {
            list.add(new Trade("EUR_USD", Order.Side.BUY, 1.1000, 1.1010, 1000.0, Instant.now(), Instant.now(), 1.0, 0.0, 0.0) {
                @Override public double pnl() { return -lossPnl; }
            });
        }
        for (int i = 0; i < wins; i++) {
            list.add(new Trade("EUR_USD", Order.Side.BUY, 1.1000, 1.1010, 1000.0, Instant.now(), Instant.now(), 1.0, 0.0, 0.0) {
                @Override public double pnl() { return winPnl; }
            });
        }
        return list;
    }

    private static RunConfigSnapshot config(String strategyTimeframe) {
        return new RunConfigSnapshot(
            "LondonOpenRangeBreakout",
            "EUR_USD",
            "PAPER",
            "LIVE",
            500,
            "2024",
            "",
            50000.0,
            1000.0,
            0.0,
            0.0,
            "PAPER_OANDA",
            "123",
            "H1",
            strategyTimeframe,
            null, null, null
        );
    }

    private static DriftEngine.BrokerObservation brokerRun(
        String runId,
        ExecutionLabel label,
        Instant startedAt,
        List<Trade> trades,
        String configHash,
        String strategyTimeframe
    ) {
        return new DriftEngine.BrokerObservation(
            runId,
            label,
            startedAt,
            configHash,
            trades,
            config(strategyTimeframe)
        );
    }
}
