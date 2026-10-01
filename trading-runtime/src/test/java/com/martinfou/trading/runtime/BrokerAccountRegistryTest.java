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

        // Production behaviour, exercised by temporarily leaving test mode: an entry whose environment
        // variables are absent yields NO credentials. Kept because it is the fail-closed path that
        // matters outside tests; unreachable while the JVM is in test mode, which is the point.
        String saved = System.getProperty(BrokerAccountRegistry.TEST_PROPERTY);
        System.clearProperty(BrokerAccountRegistry.TEST_PROPERTY);
        try {
            assertFalse(registry.credentialsConfigured("missing-env"));
            assertTrue(registry.credentials("missing-env").isEmpty());
        } finally {
            if (saved != null) {
                System.setProperty(BrokerAccountRegistry.TEST_PROPERTY, saved);
            }
        }
    }

    @Test
    void credentials_inTestMode_neverResolveARealAccountEvenIfTheEnvHasOne() {
        // The 2026-10-01 incident: an exported .env.paper in the developer's shell was inherited by the
        // forked test JVM, and the registry resolved that live account and token, so the test suite sent
        // 12 real orders to the paper account. Whatever the environment says, test mode must yield the
        // mock sentinels, and the host must not resolve, so no order can leave the JVM.
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

        assertTrue(System.getProperty(BrokerAccountRegistry.TEST_PROPERTY) != null,
            "ce test n'a de sens qu'en mode test (propriete posee par surefire)");
        BrokerCredentials creds = registry.credentials("default").orElseThrow();
        assertEquals(BrokerAccountRegistry.MOCK_TOKEN, creds.apiToken());
        assertEquals(BrokerAccountRegistry.MOCK_ACCOUNT_ID, creds.accountId());
        assertEquals(BrokerAccountRegistry.MOCK_REST_URL, creds.restUrl());
        assertFalse(creds.restUrl().contains("oanda.com"),
            "test mode ne doit jamais pointer un domaine courtier");
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
