package com.martinfou.trading.broker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerCredentialsTest {

    @Test
    void oandaFromEnvironment_reflectsEnvPresence() {
        boolean tokenPresent = System.getenv(BrokerCredentials.ENV_OANDA_TOKEN) != null
            || System.getenv(BrokerCredentials.ENV_OANDA_API_KEY) != null;
        boolean accountPresent = System.getenv(BrokerCredentials.ENV_OANDA_ACCOUNT) != null;

        if (tokenPresent && accountPresent) {
            assertTrue(BrokerCredentials.oandaFromEnvironment().isPresent());
            assertEquals("OANDA", BrokerCredentials.oandaFromEnvironment().orElseThrow().provider());
        } else {
            assertTrue(BrokerCredentials.oandaFromEnvironment().isEmpty());
        }
    }

    @Test
    void envConstants_documentOandaKeys() {
        assertEquals("OANDA_API_TOKEN", BrokerCredentials.ENV_OANDA_TOKEN);
        assertEquals("OANDA_ACCOUNT_ID", BrokerCredentials.ENV_OANDA_ACCOUNT);
    }
}
