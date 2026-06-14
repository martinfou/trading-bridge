package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerAccountRegistryTest {

    @Test
    void listMasked_neverExposesToken() {
        BrokerAccountRegistry registry = BrokerAccountRegistry.ofEntries(
            new BrokerAccountRegistry.AccountEntry(
                "firm-a",
                "OANDA",
                "TEST_TOKEN_ENV",
                "TEST_ACCOUNT_ENV",
                null,
                "https://api-fxpractice.oanda.com",
                null,
                null,
                null,
                null,
                null));

        BrokerAccount view = registry.listMasked().getFirst();
        assertEquals("firm-a", view.id());
        assertEquals("OANDA", view.provider());
        assertFalse(view.accountIdMasked().contains("token"));
        assertEquals("****", view.accountIdMasked());
    }

    @Test
    void maskAccountId_showsLastFourDigits() {
        assertEquals("****7890", BrokerAccountRegistry.maskAccountId("101-001-1234567890"));
    }


    @Test
    void resolveId_defaultsWhenBlank() {
        assertEquals(BrokerAccountRegistry.DEFAULT_ID, BrokerAccountRegistry.resolveId(null));
        assertEquals("firm-a", BrokerAccountRegistry.resolveId("firm-a"));
    }

    @Test
    void credentials_missingEnvReturnsEmpty() {
        BrokerAccountRegistry registry = BrokerAccountRegistry.ofEntries(
            new BrokerAccountRegistry.AccountEntry(
                "missing-env",
                "OANDA",
                "TB_MISSING_TOKEN_XYZ",
                "TB_MISSING_ACCOUNT_XYZ",
                "TB_MISSING_URL_XYZ",
                null,
                null,
                null,
                null,
                null,
                null));

        assertFalse(registry.credentialsConfigured("missing-env"));
        assertTrue(registry.credentials("missing-env").isEmpty());
    }
}
