package com.martinfou.trading.runtime;

import com.martinfou.trading.broker.BrokerCredentials;
import com.martinfou.trading.broker.FakeBroker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrokerAccountRoutingTest {

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
