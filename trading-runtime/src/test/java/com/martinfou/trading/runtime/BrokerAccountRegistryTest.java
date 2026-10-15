package com.martinfou.trading.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.martinfou.trading.broker.BrokerCredentials;
import com.martinfou.trading.core.guardrails.OrderTripwire;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerAccountRegistryTest {

    /**
     * System properties this class manipulates. Kept as a list so the snapshot below cannot fall behind the
     * body: every earlier leak in this class was one property that somebody forgot to restore.
     *
     * <p>{@code IBKR_ACCOUNT_ID} was the second one — set to simulate an environment variable while only the
     * keys found in the real environment were restored in the local {@code finally}, so the value survived
     * for the rest of the JVM fork exactly like the test-runtime property did.</p>
     */
    private static final List<String> TOUCHED_PROPERTIES =
        List.of(OrderTripwire.TEST_PROPERTY, "IBKR_ACCOUNT_ID");

    private final Map<String, String> propertiesBefore = new HashMap<>();

    @BeforeEach
    void captureSystemProperties() {
        for (String key : TOUCHED_PROPERTIES) {
            propertiesBefore.put(key, System.getProperty(key));
        }
    }

    /**
     * Puts every property this class touches back exactly as it found it. An earlier version cleared the
     * test-runtime property in the "IDE run" assertion and left it cleared for the rest of the JVM: every
     * later test class in the same fork resolved accounts in PRODUCTION mode and ControlSummaryServiceTest
     * failed with "Unknown broker account: null" (measured 2026-10-02: green alone, red in the module-wide
     * run, at the pre-change commit too). Restoring from a list means a test method added tomorrow cannot
     * re-introduce the leak silently.
     */
    @AfterEach
    void restoreSystemProperties() {
        TOUCHED_PROPERTIES.forEach(key -> {
            String value = propertiesBefore.get(key);
            if (value != null) {
                System.setProperty(key, value);
            } else {
                System.clearProperty(key);
            }
        });
    }

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
        // In test mode the registry resolves the mock account, so the mask reflects THAT value. What
        // must hold in every mode, and what this now asserts, is that the mask never exposes an account:
        // the shape caps disclosure at four trailing characters, and the raw account is never shown.
        String masked = view.accountIdMasked();
        assertTrue(masked.matches("\\*{4}[A-Za-z0-9]{0,4}"),
            "le masque doit etre **** ou ****llll, jamais le compte : " + masked);
        assertNotEquals(BrokerAccountRegistry.MOCK_ACCOUNT_ID, masked, "le compte brut ne doit jamais sortir");
        assertFalse(masked.contains("firm-a"), "ni l'identifiant d'entree");
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
        assertTrue(registry.credentials("missing-env", java.util.Map.of(), "/home/x/surefire-booter.jar").isEmpty());
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

        // A production JVM DOES resolve those values: assert it rather than assume it. The surefire
        // property is cleared for this one assertion, the same way ControlPlaneServerTest does it,
        // because under surefire the property is always set and a test JVM cannot exercise the
        // production branch about itself.
        String saved = System.getProperty(OrderTripwire.TEST_PROPERTY);
        System.clearProperty(OrderTripwire.TEST_PROPERTY);
        try {
            assertEquals("101-002-4729622-014",
                registry.credentials("default", dirtyShell, "/opt/app/libs/trading-runtime.jar")
                    .orElseThrow().accountId());
        } finally {
            if (saved != null) {
                System.setProperty(OrderTripwire.TEST_PROPERTY, saved);
            }
        }

        // A test JVM must not, whatever the environment says.
        BrokerCredentials inTest = registry.credentials("default", dirtyShell, "/home/x/surefire-booter.jar")
            .orElseThrow();
        assertEquals(BrokerAccountRegistry.MOCK_TOKEN, inTest.apiToken());
        assertEquals(BrokerAccountRegistry.MOCK_ACCOUNT_ID, inTest.accountId());
        assertEquals(BrokerAccountRegistry.MOCK_REST_URL, inTest.restUrl());
        assertFalse(inTest.restUrl().contains("oanda.com"), "test mode must not point at a broker");

        // And an unconfigured account stays empty even in a test runtime: callers that detect
        // "unconfigured" via isEmpty() are unaffected by this whole mechanism.
        assertTrue(registry.credentials("default", java.util.Map.of(), "/home/x/surefire-booter.jar").isEmpty());
        assertTrue(registry.credentials("missing-env", dirtyShell, "/home/x/surefire-booter.jar").isEmpty());

        // An IDE run is a test runtime too: idea_rt, with the property cleared, must still substitute.
        System.clearProperty(OrderTripwire.TEST_PROPERTY);
        assertNotEquals("101-002-4729622-014",
            registry.credentials("default", dirtyShell, "/Applications/idea_rt.jar").orElseThrow().accountId());
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
