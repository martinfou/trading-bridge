package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OosHoldoutValidationModuleTest {

    @Test
    void evaluate_disabledModule_isSkipped() throws Exception {
        OosHoldoutValidationModule module = new OosHoldoutValidationModule(
            new OosHoldoutConfig(false, 0.20, 50, 20.0, -30.0));

        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            String runId = startBacktest(manager);
            RunRecord run = manager.getRun(runId).orElseThrow();

            Optional<GateCheckResult> result = module.evaluate(
                new ValidationContext(run.strategyId(), run, store));

            assertTrue(result.isEmpty());
        }
    }

    @Test
    void evaluate_enabledModule_passesAndJournalsHoldoutEvent() throws Exception {
        OosHoldoutValidationModule module = new OosHoldoutValidationModule(
            new OosHoldoutConfig(true, 0.20, 50, 100.0, -100.0));

        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            String runId = startBacktest(manager);
            RunRecord run = manager.getRun(runId).orElseThrow();

            GateCheckResult gate = module.evaluate(
                new ValidationContext(run.strategyId(), run, store)).orElseThrow();

            assertEquals(OosHoldoutValidationModule.GATE_NAME, gate.name());
            assertTrue(gate.passed());
            assertTrue(gate.message().contains("OOS holdout passed"));

            var events = store.replayAll(runId);
            assertTrue(events.stream().anyMatch(event ->
                event.type() == RunEventType.OPERATOR_ACTION
                    && "OOS_HOLDOUT".equals(event.payload().get("validationType"))));

            var holdoutEvent = events.stream()
                .filter(event -> event.type() == RunEventType.OPERATOR_ACTION
                    && "OOS_HOLDOUT".equals(event.payload().get("validationType")))
                .findFirst()
                .orElseThrow();
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = (Map<String, Object>) holdoutEvent.payload()
                .get("validationConfigSnapshot");
            assertNotNull(snapshot);
            assertEquals(run.configHash(), snapshot.get("sourceConfigHash"));
            assertEquals(0.20, (Double) snapshot.get("holdoutPctConfigured"));
            assertTrue(snapshot.containsKey("holdoutStart"));
            assertTrue(snapshot.containsKey("holdoutEnd"));
        }
    }

    @Test
    void evaluate_strictThresholds_failHoldout() throws Exception {
        OosHoldoutValidationModule module = new OosHoldoutValidationModule(
            new OosHoldoutConfig(true, 0.20, 50, 0.0, 1000.0));

        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            String runId = startBacktest(manager);
            RunRecord run = manager.getRun(runId).orElseThrow();

            GateCheckResult gate = module.evaluate(
                new ValidationContext(run.strategyId(), run, store)).orElseThrow();

            assertFalse(gate.passed());
            assertTrue(gate.message().contains("OOS holdout failed"));
        }
    }

    @Test
    void promote_withEnabledHoldoutGate_blocksOnFailure() throws Exception {
        PromoteGateThresholds thresholds = new PromoteGateThresholds(
            1, 100.0, -100.0, 100.0, 0, true);
        OosHoldoutValidationModule module = new OosHoldoutValidationModule(
            new OosHoldoutConfig(true, 0.20, 50, 0.0, 1000.0));

        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            PromoteService service = new PromoteService(
                manager,
                stores.deploymentStore(),
                thresholds,
                java.time.Clock.systemUTC(),
                List.of(module),
                () -> false);

            String runId = startBacktest(manager);
            PromoteService.PromoteResponse response = service.promote(
                "LondonOpenRangeBreakout",
                new PromoteService.PromoteRequest("PAPER", runId));

            assertFalse(response.promoted());
            assertTrue(response.checks().stream()
                .anyMatch(check -> OosHoldoutValidationModule.GATE_NAME.equals(check.name()) && !check.passed()));
            assertTrue(stores.eventStore().replayAll(runId).stream()
                .noneMatch(event -> "OOS_HOLDOUT".equals(event.payload().get("validationType"))));
        }
    }

    private static String startBacktest(RunManager manager) throws Exception {
        String runId = manager.startRun(new RunManager.StartRunRequest(
            "LondonOpenRangeBreakout",
            "EUR_USD",
            RunMode.BACKTEST.name(),
            new BarSourceResolver.BarsSource("sample", 500, null),
            100_000.0,
            null,
            null,
            null));
        waitForCompletion(manager, runId);
        return runId;
    }

    private static void waitForCompletion(RunManager manager, String runId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            RunRecord record = manager.getRun(runId).orElseThrow();
            if (record.status() != RunRecord.Status.RUNNING) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("timeout");
    }
}
