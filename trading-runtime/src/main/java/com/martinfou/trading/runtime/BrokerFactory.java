package com.martinfou.trading.runtime;

import com.martinfou.trading.broker.Broker;

/** Creates a {@link Broker} for worker-local PAPER_OANDA / LIVE execution (Story 16.5 / 16.9). */
@FunctionalInterface
public interface BrokerFactory {

    Broker create(RunConfigSnapshot config);

    static BrokerFactory fromRegistry(BrokerAccountRegistry registry) {
        return config -> {
            ExecutionLabel label = config.resolvedExecutionLabel();
            if (label.isBrokerBacked()) {
                return registry.broker(config.brokerAccountId(), label)
                    .orElseThrow(() -> new IllegalArgumentException(
                        credentialsMessage(label, config.resolvedBrokerAccountId())));
            }
            throw new IllegalArgumentException("No broker factory for execution label " + label);
        };
    }

    static BrokerFactory oandaFromEnvironment() {
        return fromRegistry(BrokerAccountRegistry.loadDefault());
    }

    static String oandaCredentialsMessage(ExecutionLabel label) {
        return credentialsMessage(label, BrokerAccountRegistry.DEFAULT_ID);
    }

    static String credentialsMessage(ExecutionLabel label, String accountId) {
        return label.name() + " requires credentials for broker account " + accountId
            + " (configure data/runtime/broker-accounts.json and environment variables)";
    }
}
