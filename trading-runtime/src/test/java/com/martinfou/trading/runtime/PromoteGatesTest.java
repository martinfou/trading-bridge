package com.martinfou.trading.runtime;

import com.martinfou.trading.data.ibkr.IbkrConnectionConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromoteGatesTest {

    private static final PromoteGateThresholds THRESHOLDS = PromoteGateThresholds.DEFAULT;

    @Test
    void minTrades_failureIncludesNumericFields() {
        GateCheckResult result = PromoteGates.minTrades(
            new BacktestRunMetrics(0, 1.0, 0.5, null),
            THRESHOLDS);

        assertFalse(result.passed());
        assertEquals("min_trades", result.name());
        assertEquals((double) THRESHOLDS.minTrades(), result.threshold());
        assertEquals(0.0, result.actual());
    }

    @Test
    void maxDrawdown_failureIncludesNumericFields() {
        GateCheckResult result = PromoteGates.maxDrawdown(
            new BacktestRunMetrics(5, 2.0, 20.0, null),
            THRESHOLDS);

        assertFalse(result.passed());
        assertEquals(THRESHOLDS.maxDrawdownPct(), result.threshold());
        assertEquals(20.0, result.actual());
    }

    @Test
    void goldenBaseline_passesForSampleBarsConfig() {
        RunRecord run = completedRun(Map.of("barsSourceType", "sample", "barsSourceCount", 500));
        run.markCompleted(Map.of(
            "totalTrades", 2,
            "totalReturnPct", 1.0,
            "maxDrawdownPct", 1.0));

        GateCheckResult result = PromoteGates.goldenBaseline(run, THRESHOLDS);
        assertTrue(result.passed());
        assertEquals("golden_baseline", result.name());
    }

    @Test
    void paperDuration_stubExcludedFromLive() {
        DeploymentRecord stubPaper = new DeploymentRecord(
            "LondonOpenRangeBreakout",
            com.martinfou.trading.backtest.RunMode.PAPER,
            Instant.parse("2020-01-01T00:00:00Z"),
            "run-1",
            List.of(),
            ExecutionLabel.PAPER_STUB);

        GateCheckResult label = PromoteGates.paperExecutionLabel(Optional.of(stubPaper));
        assertFalse(label.passed());

        Clock clock = Clock.fixed(Instant.parse("2024-06-01T00:00:00Z"), ZoneOffset.UTC);
        GateCheckResult duration = PromoteGates.paperDuration(Optional.of(stubPaper), THRESHOLDS, clock);
        assertFalse(duration.passed());
        assertEquals(0.0, duration.actual());
        assertEquals(30.0, duration.threshold());
    }

    @Test
    void paperDuration_passesAfterThirtyDaysOnOanda() {
        Clock clock = Clock.fixed(Instant.parse("2024-02-01T00:00:00Z"), ZoneOffset.UTC);
        DeploymentRecord paper = new DeploymentRecord(
            "LondonOpenRangeBreakout",
            com.martinfou.trading.backtest.RunMode.PAPER,
            Instant.parse("2024-01-01T00:00:00Z"),
            "run-1",
            List.of(),
            ExecutionLabel.PAPER_OANDA);

        GateCheckResult duration = PromoteGates.paperDuration(Optional.of(paper), THRESHOLDS, clock);
        assertTrue(duration.passed());
        assertEquals(31.0, duration.actual());
    }

    @Test
    void resolvePromotedAt_preservesPaperOandaLineage() {
        Instant paperStart = Instant.parse("2024-01-01T00:00:00Z");
        Instant now = Instant.parse("2024-02-01T00:00:00Z");
        DeploymentRecord current = new DeploymentRecord(
            "LondonOpenRangeBreakout",
            com.martinfou.trading.backtest.RunMode.PAPER,
            paperStart,
            "run-1",
            List.of(),
            ExecutionLabel.PAPER_OANDA);

        Instant resolved = PromoteService.resolvePromotedAt(
            Optional.of(current),
            com.martinfou.trading.backtest.RunMode.PAPER,
            ExecutionLabel.PAPER_OANDA,
            now);

        assertEquals(paperStart, resolved);
    }

    @Test
    void validationModule_emptyModulesPassesWhenEnabled() {
        PromoteGateThresholds enabled = new PromoteGateThresholds(
            1, 100.0, -50.0, 1.0, 30, true);
        RunRecord run = completedRun(Map.of("barsSourceType", "sample", "barsSourceCount", 500));

        GateCheckResult result = PromoteGates.validationModule(
            new ValidationContext("LondonOpenRangeBreakout", run, null),
            enabled,
            List.of());

        assertTrue(result.passed());
        assertEquals("validation_module", result.name());
        assertTrue(result.message().contains("skipped"));
    }

    @Test
    void ibkrCredentials_notRequiredForPaperStub() {
        GateCheckResult result = PromoteGates.ibkrCredentialsForPaper(
            ExecutionLabel.PAPER_STUB, BrokerAccountRegistry.DEFAULT_ID, null);
        assertTrue(result.passed());
    }

    @Test
    void ibkrCredentials_failWhenIbkrNotConfigured() {
        BrokerAccountRegistry registry = BrokerAccountRegistry.ofEntries(
            new BrokerAccountRegistry.AccountEntry(
                "ibkr-paper",
                "IBKR",
                null,
                IbkrConnectionConfig.ENV_ACCOUNT,
                null,
                null,
                IbkrConnectionConfig.ENV_HOST,
                IbkrConnectionConfig.ENV_PORT,
                IbkrConnectionConfig.ENV_CLIENT_ID,
                IbkrConnectionConfig.DEFAULT_PAPER_PORT,
                IbkrConnectionConfig.DEFAULT_LIVE_PORT));

        assertFalse(PromoteGates.ibkrCredentialsForPaper(
            ExecutionLabel.PAPER_IBKR, "ibkr-paper", registry).passed());
        assertEquals("ibkr_credentials", PromoteGates.ibkrCredentialsForPaper(
            ExecutionLabel.PAPER_IBKR, "ibkr-paper", registry).name());
    }

    @Test
    void paperExecutionLabel_ibkrMessageDocumentsLivePath() {
        DeploymentRecord ibkrPaper = new DeploymentRecord(
            "LondonOpenRangeBreakout",
            com.martinfou.trading.backtest.RunMode.PAPER,
            Instant.parse("2024-01-01T00:00:00Z"),
            "run-1",
            List.of(),
            ExecutionLabel.PAPER_IBKR);

        GateCheckResult label = PromoteGates.paperExecutionLabel(Optional.of(ibkrPaper));
        assertFalse(label.passed());
        assertTrue(label.message().contains("PAPER_OANDA"));
    }

    @Test
    void resolvePromotedAt_resetsWhenUpgradingFromStub() {
        Instant stubStart = Instant.parse("2023-06-01T00:00:00Z");
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        DeploymentRecord current = new DeploymentRecord(
            "LondonOpenRangeBreakout",
            com.martinfou.trading.backtest.RunMode.PAPER,
            stubStart,
            "run-1",
            List.of(),
            ExecutionLabel.PAPER_STUB);

        Instant resolved = PromoteService.resolvePromotedAt(
            Optional.of(current),
            com.martinfou.trading.backtest.RunMode.PAPER,
            ExecutionLabel.PAPER_OANDA,
            now);

        assertEquals(now, resolved);
    }

    private static RunRecord completedRun(Map<String, Object> configExtras) {
        RunConfigSnapshot config = new RunConfigSnapshot(
            "LondonOpenRangeBreakout",
            "EUR_USD",
            "BACKTEST",
            (String) configExtras.get("barsSourceType"),
            (Integer) configExtras.get("barsSourceCount"),
            null,
            100_000.0,
            null,
            null,
            null);
        RunRecord run = new RunRecord(
            "run-test",
            "LondonOpenRangeBreakout",
            "EUR_USD",
            com.martinfou.trading.backtest.RunMode.BACKTEST,
            config);
        run.markCompleted(Map.of("totalTrades", 0, "totalReturnPct", 0.0, "maxDrawdownPct", 0.0));
        return run;
    }
}
