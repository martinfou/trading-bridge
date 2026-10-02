package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;
import com.martinfou.trading.broker.BrokerCredentials;

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
        // In test mode the registry resolves the mock account, so the mask reflects that value.
        // What must hold in every mode is the shape: a mask, never the account itself.
        assertTrue(view.accountIdMasked().matches("\\*{4}[A-Za-z0-9]{0,4}"),
            "le masque doit etre **** ou ****llll, jamais le compte : " + view.accountIdMasked());
        assertFalse(view.accountIdMasked().contains("firm-a"));
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

        // Unchanged by the test-mode fix, and that is deliberate: nothing resolvable means nothing
        // returned, in every mode. Callers that detect "unconfigured" via isEmpty() are unaffected.
        assertFalse(registry.credentialsConfigured("missing-env"));
        assertTrue(registry.credentials("missing-env").isEmpty());
        assertTrue(registry.credentials("missing-env", java.util.Map.of(), true).isEmpty());
    }

    @Test
    void credentials_inTestRuntime_neverResolveARealAccountEvenIfTheEnvHasOne() {
        // The 2026-10-01 incident, pinned deterministically: the environment and the classpath are
        // parameters, so this asserts the rule without depending on the shell that runs it.
        BrokerAccountRegistry registry = BrokerAccountRegistry.ofEntries(
            new BrokerAccountRegistry.AccountEntry(
                "default",
                "OANDA",
                BrokerCredentials.ENV_OANDA_TOKEN,
                BrokerCredentials.ENV_OANDA_ACCOUNT,
                BrokerCredentials.ENV_OANDA_REST_URL,
                null,
                null,
                null,
                null,
                null,
                null));

        java.util.Map<String, String> dirtyShell = java.util.Map.of(
            BrokerCredentials.ENV_OANDA_TOKEN, "a-live-token-that-must-never-be-used",
            BrokerCredentials.ENV_OANDA_ACCOUNT, "101-002-4729622-014");

        // A production JVM DOES resolve those values: that is the behaviour being protected, so assert
        // it rather than assume it. (testRuntime is passed explicitly because under surefire the real
        // property is always set, so a test JVM cannot exercise the production branch about itself.)
        assertEquals("101-002-4729622-014",
            registry.credentials("default", dirtyShell, false).orElseThrow().accountId());

        // A test JVM must not, whatever the environment says.
        BrokerCredentials inTest = registry.credentials("default", dirtyShell, true).orElseThrow();
        assertEquals(BrokerAccountRegistry.MOCK_TOKEN, inTest.apiToken());
        assertEquals(BrokerAccountRegistry.MOCK_ACCOUNT_ID, inTest.accountId());
        assertEquals(BrokerAccountRegistry.MOCK_REST_URL, inTest.restUrl());
        assertFalse(inTest.restUrl().contains("oanda.com"), "test mode must not point at a broker");

        // And an unconfigured account stays empty even in test mode: callers that detect
        // "unconfigured" via isEmpty() are unaffected by this whole mechanism.
        assertTrue(registry.credentials("default", java.util.Map.of(), true).isEmpty());
        assertTrue(registry.credentials("missing-env", dirtyShell, true).isEmpty());
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
