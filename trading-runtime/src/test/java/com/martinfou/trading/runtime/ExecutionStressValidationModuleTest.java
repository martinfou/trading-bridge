package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStressValidationModuleTest {

    @Test
    void evaluate_disabledModule_isSkipped() throws Exception {
        ExecutionStressValidationModule module = new ExecutionStressValidationModule(
            new ExecutionStressConfig(false, 3.0, 2.0, 0.0001, 5.0, 25.0, -40.0));

        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            String runId = startBacktest(manager);
            RunRecord run = manager.getRun(runId).orElseThrow();

            assertTrue(module.evaluate(new ValidationContext(run.strategyId(), run, store)).isEmpty());
        }
    }

    @Test
    void evaluate_ciDeterministicScenario_passesWithLenientThresholds() throws Exception {
        ExecutionStressValidationModule module = new ExecutionStressValidationModule(
            new ExecutionStressConfig(true, 2.0, 2.0, 0.0001, 5.0, 100.0, -100.0));

        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            String runId = startBacktest(manager);
            RunRecord run = manager.getRun(runId).orElseThrow();

            GateCheckResult gate = module.evaluate(
                new ValidationContext(run.strategyId(), run, store)).orElseThrow();

            assertEquals(ExecutionStressValidationModule.GATE_NAME, gate.name());
            assertTrue(gate.passed());

            var events = store.replayAll(runId);
            assertTrue(events.stream().anyMatch(event ->
                event.type() == RunEventType.OPERATOR_ACTION
                    && "EXECUTION_STRESS".equals(event.payload().get("validationType"))));

            var stressEvent = events.stream()
                .filter(event -> "EXECUTION_STRESS".equals(event.payload().get("validationType")))
                .findFirst()
                .orElseThrow();
            assertTrue(stressEvent.payload().containsKey("validationConfigSnapshot"));
            @SuppressWarnings("unchecked")
            var snapshot = (java.util.Map<String, Object>) stressEvent.payload().get("validationConfigSnapshot");
            assertEquals(run.configHash(), snapshot.get("sourceConfigHash"));
        }
    }

    @Test
    void evaluate_strictDrawdownVeto_fails() throws Exception {
        ExecutionStressValidationModule module = new ExecutionStressValidationModule(
            new ExecutionStressConfig(true, 5.0, 5.0, 0.001, 20.0, 0.0, -100.0));

        try (EventStore store = EventStores.inMemory();
             RunManager manager = new RunManager(store)) {
            String runId = startBacktest(manager);
            RunRecord run = manager.getRun(runId).orElseThrow();

            GateCheckResult gate = module.evaluate(
                new ValidationContext(run.strategyId(), run, store)).orElseThrow();

            assertFalse(gate.passed());
            assertTrue(gate.message().contains("Execution stress failed"));
        }
    }

    @Test
    void promote_withEnabledStressGate_blocksOnFailure() throws Exception {
        PromoteGateThresholds thresholds = new PromoteGateThresholds(
            1, 100.0, -100.0, 100.0, 0, true);
        ExecutionStressValidationModule module = new ExecutionStressValidationModule(
            new ExecutionStressConfig(true, 5.0, 5.0, 0.001, 20.0, 0.0, -100.0));

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
                .anyMatch(check -> ExecutionStressValidationModule.GATE_NAME.equals(check.name())
                    && !check.passed()));
            assertTrue(stores.eventStore().replayAll(runId).stream()
                .noneMatch(event -> "EXECUTION_STRESS".equals(event.payload().get("validationType"))));
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
