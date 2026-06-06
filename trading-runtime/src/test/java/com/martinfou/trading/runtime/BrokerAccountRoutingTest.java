package com.martinfou.trading.runtime;

import com.martinfou.trading.broker.BrokerCredentials;
import com.martinfou.trading.broker.FakeBroker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrokerAccountRoutingTest {

    @Test
    void runManager_blocksCrossAccountBrokerRun() {
        BrokerAccountRegistry registry = BrokerAccountRegistry.ofEntries(
            new BrokerAccountRegistry.AccountEntry(
                "firm-a", "OANDA", "T1", "A1", null, null, null, null, null, null, null),
            new BrokerAccountRegistry.AccountEntry(
                "firm-b", "OANDA", "T2", "A2", null, null, null, null, null, null, null));

        try (EventStore store = EventStores.inMemory();
             InMemoryDeploymentStore deploymentStore = new InMemoryDeploymentStore()) {
            deploymentStore.save(new DeploymentRecord(
                "LondonOpenRangeBreakout",
                com.martinfou.trading.backtest.RunMode.PAPER,
                java.time.Instant.now(),
                "run-bt",
                java.util.List.of(),
                ExecutionLabel.PAPER_OANDA,
                "firm-a"));

            RunManager manager = new RunManager(
                store,
                config -> new FakeBroker(100_000.0),
                deploymentStore,
                registry);

            assertThrows(IllegalArgumentException.class, () -> manager.startRun(
                new RunManager.StartRunRequest(
                    "LondonOpenRangeBreakout",
                    "EUR_USD",
                    "PAPER",
                    new BarSourceResolver.BarsSource("sample", 10, null),
                    100_000.0,
                null,
                    null,
                    null,
                    ExecutionLabel.PAPER_OANDA.name(),
                    "firm-b")));
        }
    }

    @Test
    void promoteService_blocksLivePromoteWithMismatchedAccount() {
        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore(), stores.deploymentStore())) {
            stores.deploymentStore().save(new DeploymentRecord(
                "LondonOpenRangeBreakout",
                com.martinfou.trading.backtest.RunMode.PAPER,
                java.time.Instant.now(),
                "run-bt",
                java.util.List.of(),
                ExecutionLabel.PAPER_OANDA,
                "firm-a"));

            PromoteService service = new PromoteService(manager, stores.deploymentStore());

            assertThrows(IllegalArgumentException.class, () -> service.promote(
                "LondonOpenRangeBreakout",
                new PromoteService.PromoteRequest("LIVE", null, null, "firm-b")));
        }
    }

    @Test
    void brokerFactory_usesAccountFromConfig() {
        BrokerAccountRegistry registry = BrokerAccountRegistry.ofEntries(
            new BrokerAccountRegistry.AccountEntry(
                "firm-a",
                "OANDA",
                BrokerCredentials.ENV_OANDA_TOKEN,
                BrokerCredentials.ENV_OANDA_ACCOUNT,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

        BrokerFactory factory = BrokerFactory.fromRegistry(registry);
        RunConfigSnapshot config = new RunConfigSnapshot(
            "Test",
            "EUR_USD",
            "PAPER",
            "sample",
            10,
            null,
            100_000.0,
            null,
            null,
            ExecutionLabel.PAPER_OANDA.name(),
            "firm-a");

        if (registry.credentialsConfigured("firm-a")) {
            factory.create(config);
        } else {
            assertThrows(IllegalArgumentException.class, () -> factory.create(config));
        }
    }
}
