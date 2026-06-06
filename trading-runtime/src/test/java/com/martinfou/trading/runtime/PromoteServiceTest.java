package com.martinfou.trading.runtime;

import com.martinfou.trading.data.ibkr.IbkrConnectionConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromoteServiceTest {

    /** Lenient DD cap so sample-bar LORB backtests pass in integration tests. */
    private static final PromoteGateThresholds LENIENT = new PromoteGateThresholds(
        1, 100.0, -50.0, 1.0, 30, false);

    private static PromoteService service(RunManager manager, DeploymentStore store) {
        return service(manager, store, Clock.systemUTC());
    }

    private static PromoteService service(RunManager manager, DeploymentStore store, Clock clock) {
        return new PromoteService(manager, store, LENIENT, clock, List.of(), () -> false);
    }

    private static PromoteService serviceWithOanda(RunManager manager, DeploymentStore store, Clock clock) {
        return new PromoteService(manager, store, LENIENT, clock, List.of(), () -> true);
    }

    @Test
    void promoteToPaper_succeedsAfterCompletedBacktest() throws Exception {
        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            PromoteService service = service(manager, stores.deploymentStore());

            String runId = manager.startRun(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                new BarSourceResolver.BarsSource("sample", 500, null),
                100_000.0,
                null,
                null,
                null,
                null));
            waitForCompletion(manager, runId);

            PromoteService.PromoteResponse response = service.promote(
                "LondonOpenRangeBreakout",
                new PromoteService.PromoteRequest("PAPER", runId));

            assertTrue(response.promoted());
            assertTrue(response.checks().stream().allMatch(GateCheckResult::passed));
            assertTrue(stores.deploymentStore().get("LondonOpenRangeBreakout").isPresent());
        }
    }

    @Test
    void promoteToPaper_failsWithoutBacktest() {
        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            PromoteService service = service(manager, stores.deploymentStore());

            PromoteService.PromoteResponse response = service.promote(
                "LondonOpenRangeBreakout",
                new PromoteService.PromoteRequest("PAPER", null));

            assertFalse(response.promoted());
        }
    }

    @Test
    void promoteToPaper_failsMinTradesWithNumericReasons() throws Exception {
        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            PromoteService service = new PromoteService(
                manager,
                stores.deploymentStore(),
                new PromoteGateThresholds(100, 100.0, -50.0, 1.0, 30, false),
                Clock.systemUTC(),
                List.of(),
                () -> false);

            String runId = manager.startRun(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                new BarSourceResolver.BarsSource("sample", 500, null),
                100_000.0,
                null,
                null,
                null,
                null));
            waitForCompletion(manager, runId);

            PromoteService.PromoteResponse response = service.promote(
                "LondonOpenRangeBreakout",
                new PromoteService.PromoteRequest("PAPER", runId));

            assertFalse(response.promoted());
            GateCheckResult failed = response.checks().stream()
                .filter(c -> "min_trades".equals(c.name()) && !c.passed())
                .findFirst()
                .orElseThrow();
            assertNotNull(failed.threshold());
            assertNotNull(failed.actual());
            assertEquals(100.0, failed.threshold());
        }
    }

    @Test
    void promoteToLive_rejectsPaperStub() throws Exception {
        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            PromoteService service = service(manager, stores.deploymentStore());

            String runId = manager.startRun(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                new BarSourceResolver.BarsSource("sample", 500, null),
                100_000.0,
                null,
                null,
                null,
                null));
            waitForCompletion(manager, runId);
            service.promote("LondonOpenRangeBreakout", new PromoteService.PromoteRequest("PAPER", runId));

            PromoteService.PromoteResponse live = service.promote(
                "LondonOpenRangeBreakout",
                new PromoteService.PromoteRequest("LIVE", null));

            assertFalse(live.promoted());
            assertTrue(live.checks().stream()
                .anyMatch(c -> "paper_execution_label".equals(c.name())
                    && !c.passed()
                    && c.message().toLowerCase().contains("stub does not count")));
        }
    }

    @Test
    void promoteToLive_requiresPaperFirst() throws Exception {
        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            PromoteService service = service(manager, stores.deploymentStore());

            PromoteService.PromoteResponse response = service.promote(
                "LondonOpenRangeBreakout",
                new PromoteService.PromoteRequest("LIVE", null));

            assertFalse(response.promoted());
            assertTrue(response.checks().stream().anyMatch(c -> c.name().equals("paper_deployed") && !c.passed()));
        }
    }

    @Test
    void promoteToPaper_withOandaCredentials_setsPaperOandaLabel() throws Exception {
        Instant now = Instant.parse("2024-06-01T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            PromoteService service = serviceWithOanda(manager, stores.deploymentStore(), clock);

            String runId = manager.startRun(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                new BarSourceResolver.BarsSource("sample", 500, null),
                100_000.0,
                null,
                null,
                null,
                null));
            waitForCompletion(manager, runId);

            PromoteService.PromoteResponse response = service.promote(
                "LondonOpenRangeBreakout",
                new PromoteService.PromoteRequest("PAPER", runId));

            assertTrue(response.promoted());
            assertEquals(ExecutionLabel.PAPER_OANDA, response.deployment().executionLabel());
            assertEquals(now, response.deployment().promotedAt());
        }
    }

    @Test
    void promoteToPaper_explicitPaperOanda_withoutCredentials_rejected() throws Exception {
        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            PromoteService service = service(manager, stores.deploymentStore());

            String runId = manager.startRun(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                new BarSourceResolver.BarsSource("sample", 500, null),
                100_000.0,
                null,
                null,
                null,
                null));
            waitForCompletion(manager, runId);

            PromoteService.PromoteResponse response = service.promote(
                "LondonOpenRangeBreakout",
                new PromoteService.PromoteRequest(
                    "PAPER", runId, ExecutionLabel.PAPER_OANDA.name()));

            assertFalse(response.promoted());
            assertTrue(response.checks().stream()
                .anyMatch(c -> "oanda_credentials".equals(c.name()) && !c.passed()));
        }
    }

    @Test
    void promoteToLive_rejectsPaperOandaBefore30Days() {
        Instant paperStart = Instant.parse("2024-01-01T00:00:00Z");
        Instant now = Instant.parse("2024-01-15T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            stores.deploymentStore().save(new DeploymentRecord(
                "LondonOpenRangeBreakout",
                com.martinfou.trading.backtest.RunMode.PAPER,
                paperStart,
                "run-1",
                List.of(),
                ExecutionLabel.PAPER_OANDA));

            PromoteService service = service(manager, stores.deploymentStore(), clock);
            PromoteService.PromoteResponse live = service.promote(
                "LondonOpenRangeBreakout",
                new PromoteService.PromoteRequest("LIVE", null));

            assertFalse(live.promoted());
            GateCheckResult duration = live.checks().stream()
                .filter(c -> "paper_duration_days".equals(c.name()))
                .findFirst()
                .orElseThrow();
            assertFalse(duration.passed());
            assertEquals(14.0, duration.actual());
            assertTrue(duration.message().contains("14 days"));
        }
    }

    @Test
    void promoteToLive_succeedsAfter30DaysOnPaperOanda() {
        Instant paperStart = Instant.parse("2024-01-01T00:00:00Z");
        Instant now = Instant.parse("2024-02-01T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            stores.deploymentStore().save(new DeploymentRecord(
                "LondonOpenRangeBreakout",
                com.martinfou.trading.backtest.RunMode.PAPER,
                paperStart,
                "run-1",
                List.of(),
                ExecutionLabel.PAPER_OANDA));

            PromoteService service = service(manager, stores.deploymentStore(), clock);
            PromoteService.PromoteResponse live = service.promote(
                "LondonOpenRangeBreakout",
                new PromoteService.PromoteRequest("LIVE", null));

            assertTrue(live.promoted());
            assertEquals(com.martinfou.trading.backtest.RunMode.LIVE, live.deployment().mode());
            assertEquals(ExecutionLabel.LIVE_OANDA, live.deployment().executionLabel());
        }
    }

    @Test
    void promoteToPaper_rePromotePreservesPaperOandaStartDate() throws Exception {
        Instant paperStart = Instant.parse("2024-01-01T00:00:00Z");
        Instant rePromote = Instant.parse("2024-01-20T00:00:00Z");
        Clock clock = Clock.fixed(rePromote, ZoneOffset.UTC);

        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            stores.deploymentStore().save(new DeploymentRecord(
                "LondonOpenRangeBreakout",
                com.martinfou.trading.backtest.RunMode.PAPER,
                paperStart,
                "run-old",
                List.of(),
                ExecutionLabel.PAPER_OANDA));

            PromoteService service = serviceWithOanda(manager, stores.deploymentStore(), clock);

            String runId = manager.startRun(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                new BarSourceResolver.BarsSource("sample", 500, null),
                100_000.0,
                null,
                null,
                null,
                null));
            waitForCompletion(manager, runId);

            PromoteService.PromoteResponse response = service.promote(
                "LondonOpenRangeBreakout",
                new PromoteService.PromoteRequest("PAPER", runId));

            assertTrue(response.promoted());
            assertEquals(ExecutionLabel.PAPER_OANDA, response.deployment().executionLabel());
            assertEquals(paperStart, response.deployment().promotedAt());
        }
    }

    @Test
    void promoteToPaper_paperIbkrAllowed_failsWithoutCredentials() throws Exception {
        BrokerAccountRegistry registry = BrokerAccountRegistry.ofEntries(
            new BrokerAccountRegistry.AccountEntry(
                BrokerAccountRegistry.DEFAULT_ID,
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

        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {
            PromoteService service = new PromoteService(
                manager,
                stores.deploymentStore(),
                LENIENT,
                Clock.systemUTC(),
                List.of(),
                registry,
                accountId -> false);

            String runId = manager.startRun(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                new BarSourceResolver.BarsSource("sample", 500, null),
                100_000.0,
                null,
                null,
                null,
                null));
            waitForCompletion(manager, runId);

            PromoteService.PromoteResponse response = service.promote(
                "LondonOpenRangeBreakout",
                new PromoteService.PromoteRequest("PAPER", runId, "PAPER_IBKR"));

            if (PromoteGates.ibkrUseStub()) {
                assertTrue(response.promoted());
                assertEquals(ExecutionLabel.PAPER_IBKR, response.deployment().executionLabel());
            } else {
                assertFalse(response.promoted());
                assertTrue(response.checks().stream()
                    .anyMatch(c -> "ibkr_credentials".equals(c.name()) && !c.passed()));
            }
        }
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
