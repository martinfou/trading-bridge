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

    @Test
    void ibkrPaperConnection_resolvesPort7497FromLocalConfig() throws Exception {
        // Phase B: le fichier local (gitignored) contient le compte paper IBKR. Le registry
        // doit charger ce fichier et résoudre le port paper 7497 (jamais 7496 live).
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("broker-accounts-test");
        java.nio.file.Path localFile = dir.resolve("broker-accounts.local.json");
        java.nio.file.Files.writeString(localFile, """
            {
              "accounts": [
                {
                  "id": "paper",
                  "provider": "IBKR",
                  "accountIdEnv": "IBKR_ACCOUNT_ID",
                  "hostEnv": "IBKR_GATEWAY_HOST",
                  "portEnv": "IBKR_GATEWAY_PORT",
                  "clientIdEnv": "IBKR_CLIENT_ID",
                  "defaultPortPaper": 7497,
                  "defaultPortLive": 7496
                }
              ]
            }
            """);

        BrokerAccountRegistry registry = BrokerAccountRegistry.load(localFile);

        java.util.Map<String, String> saved = new java.util.HashMap<>();
        for (String k : new String[]{"IBKR_ACCOUNT_ID", "IBKR_GATEWAY_HOST", "IBKR_GATEWAY_PORT", "IBKR_CLIENT_ID"}) {
            String v = System.getenv(k);
            if (v != null) saved.put(k, v);
        }
        try {
            System.setProperty("IBKR_ACCOUNT_ID", "DU1234567");
            // Simule les env vars via le registry: il lit System.getenv, donc on passe par
            // l'entrée résolue avec valeurs littérales via un registry construit de la config.
            var entry = registry.getRawAccount("paper");
            assertEquals("IBKR", entry.provider());
            assertEquals(Integer.valueOf(7497), entry.defaultPortPaper());
            assertEquals(Integer.valueOf(7496), entry.defaultPortLive());
        } finally {
            for (var e : saved.entrySet()) {
                System.setProperty(e.getKey(), e.getValue());
            }
        }
    }
}
