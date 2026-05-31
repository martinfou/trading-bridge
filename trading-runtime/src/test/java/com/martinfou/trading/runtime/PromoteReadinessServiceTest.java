package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromoteReadinessServiceTest {

    private static final PromoteGateThresholds LENIENT = new PromoteGateThresholds(
        1, 100.0, -50.0, 1.0, 30, false);

    @Test
    void assess_beforePaperDeployment_targetsPaperWithBacktestGates() throws Exception {
        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            PromoteService promote = new PromoteService(manager, stores.deploymentStore(), LENIENT, Clock.systemUTC(), List.of(), () -> false);
            PromoteReadinessService readiness = new PromoteReadinessService(manager, promote);

            Map<String, Object> result = readiness.assess("LondonOpenRangeBreakout");

            assertEquals(1, result.get("schemaVersion"));
            assertEquals("PAPER", result.get("targetMode"));
            assertFalse((Boolean) result.get("ready"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> gates = (List<Map<String, Object>>) result.get("gates");
            assertTrue(gates.stream().anyMatch(g -> "backtest_exists".equals(g.get("name"))));
            @SuppressWarnings("unchecked")
            Map<String, Object> reconciliation = (Map<String, Object>) result.get("reconciliation");
            assertTrue((Boolean) reconciliation.get("clear"));
        }
    }

    @Test
    void assess_onPaperOanda_targetsLiveWithElapsedDays() {
        Instant promotedAt = Instant.parse("2024-01-01T00:00:00Z");
        Clock clock = Clock.fixed(Instant.parse("2024-01-16T00:00:00Z"), ZoneOffset.UTC);

        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            stores.deploymentStore().save(new DeploymentRecord(
                "LondonOpenRangeBreakout",
                RunMode.PAPER,
                promotedAt,
                "run-bt",
                List.of(),
                ExecutionLabel.PAPER_OANDA));

            PromoteService promote = new PromoteService(manager, stores.deploymentStore(), LENIENT, clock, List.of(), () -> true);
            PromoteReadinessService readiness = new PromoteReadinessService(manager, promote, clock);

            Map<String, Object> result = readiness.assess("LondonOpenRangeBreakout");

            assertEquals("LIVE", result.get("targetMode"));
            assertEquals(15L, result.get("paperElapsedDays"));
            assertEquals(30, result.get("paperDaysRequired"));
            assertFalse((Boolean) result.get("ready"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> gates = (List<Map<String, Object>>) result.get("gates");
            assertTrue(gates.stream().anyMatch(g -> "paper_duration_days".equals(g.get("name")) && !(Boolean) g.get("passed")));
        }
    }

    @Test
    void assess_stubPaper_failsPaperExecutionLabelGate() {
        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            stores.deploymentStore().save(new DeploymentRecord(
                "LondonOpenRangeBreakout",
                RunMode.PAPER,
                Instant.parse("2024-01-01T00:00:00Z"),
                "run-bt",
                List.of(),
                ExecutionLabel.PAPER_STUB));

            PromoteService promote = new PromoteService(manager, stores.deploymentStore(), LENIENT, Clock.systemUTC(), List.of(), () -> false);
            PromoteReadinessService readiness = new PromoteReadinessService(manager, promote);

            Map<String, Object> result = readiness.assess("LondonOpenRangeBreakout");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> gates = (List<Map<String, Object>>) result.get("gates");
            assertTrue(gates.stream().anyMatch(g ->
                "paper_execution_label".equals(g.get("name")) && !(Boolean) g.get("passed")));
            assertEquals(0L, result.get("paperElapsedDays"));
        }
    }
}
