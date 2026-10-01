package com.martinfou.trading.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure {@link OrderTripwire#decision(Map, String)} function plus the fail-closed
 * {@link OrderTripwire#checkOrderAllowed} refusal. Hermetic: no broker, no network, no env mutation.
 */
class OrderTripwireTest {

    private static final String NO_SUREFIRE = "/app/lib/foo.jar:/app/lib/bar.jar";

    private static Map<String, String> envWithFlag(String value) {
        return Map.of(OrderTripwire.ENV_ALLOW_ORDERS, value);
    }

    // ------------------------------------------------------------------
    // decision() — the pure function
    // ------------------------------------------------------------------

    @Test
    void decision_absentFlag_refuses() {
        assertFalse(OrderTripwire.decision(Map.of(), NO_SUREFIRE));
        assertFalse(OrderTripwire.decision(Map.of("OTHER", "x"), NO_SUREFIRE));
    }

    @Test
    void decision_zero_refuses() {
        assertFalse(OrderTripwire.decision(envWithFlag("0"), NO_SUREFIRE));
    }

    @Test
    void decision_false_refuses() {
        assertFalse(OrderTripwire.decision(envWithFlag("false"), NO_SUREFIRE));
    }

    @Test
    void decision_yes_refuses() {
        // "yes" is not an authorised value: only 1/true authorise.
        assertFalse(OrderTripwire.decision(envWithFlag("yes"), NO_SUREFIRE));
        assertFalse(OrderTripwire.decision(envWithFlag("on"), NO_SUREFIRE));
        assertFalse(OrderTripwire.decision(envWithFlag("2"), NO_SUREFIRE));
        assertFalse(OrderTripwire.decision(envWithFlag(""), NO_SUREFIRE));
    }

    @Test
    void decision_one_authorises() {
        assertTrue(OrderTripwire.decision(envWithFlag("1"), NO_SUREFIRE));
    }

    @Test
    void decision_true_authorises() {
        assertTrue(OrderTripwire.decision(envWithFlag("true"), NO_SUREFIRE));
    }

    @Test
    void decision_caseInsensitiveAndTrimmed() {
        assertTrue(OrderTripwire.decision(envWithFlag("TRUE"), NO_SUREFIRE));
        assertTrue(OrderTripwire.decision(envWithFlag("True"), NO_SUREFIRE));
        assertTrue(OrderTripwire.decision(envWithFlag(" 1 "), NO_SUREFIRE));
        assertTrue(OrderTripwire.decision(envWithFlag(" tRuE "), NO_SUREFIRE));
    }

    @Test
    void decision_flagPresentButTestRuntimeOnClasspath_refuses() {
        String surefireClassPath = "/app/lib/x.jar" + java.io.File.pathSeparator
                + "/maven/surefire-booter-3.2.5.jar";
        assertFalse(OrderTripwire.decision(envWithFlag("1"), surefireClassPath));
    }

    @Test
    void decision_flagPresentButSurefireEnv_refuses() {
        assertFalse(OrderTripwire.decision(
                Map.of(OrderTripwire.ENV_ALLOW_ORDERS, "1", "SUREFIRE_REPORT_DIR", "/tmp/reports"),
                NO_SUREFIRE));
    }

    // ------------------------------------------------------------------
    // checkOrderAllowed() — the fail-closed side effect
    // ------------------------------------------------------------------

    @Test
    void checkOrderAllowed_refusesWithActionableMessage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> OrderTripwire.checkOrderAllowed("EUR_USD", "-1000", "test"));
        assertTrue(ex.getMessage().contains(OrderTripwire.ENV_ALLOW_ORDERS),
                "message must name the flag: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(OrderTripwire.GUARDRAILS_DOC),
                "message must name the guardrails doc: " + ex.getMessage());
    }

    @Test
    void isTestRuntime_detectsRealSurefireRuntime() {
        // Empirical: this test itself runs under Surefire, so the merged runtime classpath
        // (java.class.path + surefire.test.class.path) must be detected. If this ever fails, the
        // defence-in-depth detection is not wired to the real runtime and must be fixed before the
        // tripwire can be trusted.
        assertTrue(OrderTripwire.isTestRuntime(System.getenv(), OrderTripwire.runtimeClassPath()),
                "Surefire not detected on the live runtime classpath — test-runtime detection is broken");
    }

    // ------------------------------------------------------------------
    // Test-only gate: can never open in production
    // ------------------------------------------------------------------

    @Test
    void testGate_installRefusedWithoutTestRuntime() {
        // Production environment: no SUREFIRE_* var, no flag; classpath without surefire.
        assertThrows(IllegalStateException.class,
                () -> OrderTripwire.allowOrdersForTestingOnly(Map.of(), NO_SUREFIRE));
    }

    @Test
    void testGate_cannotOpenInProduction() {
        Map<String, String> prodEnv = Map.of(); // no SUREFIRE_*, no TB_ALLOW_ORDERS
        String prodCp = NO_SUREFIRE;

        // 1. Installing the gate in a simulated production environment is refused outright.
        assertThrows(IllegalStateException.class,
                () -> OrderTripwire.allowOrdersForTestingOnly(prodEnv, prodCp));

        // 2. The pure decision still refuses (flag absent).
        assertFalse(OrderTripwire.decision(prodEnv, prodCp));

        // 3. checkOrderAllowed still refuses even after the refused install attempt.
        assertThrows(IllegalStateException.class,
                () -> OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "prod-sim", prodEnv, prodCp));
    }

    @Test
    void testGate_flagPresentButTestRuntime_refuses() {
        // Anti-circumvention: flag present AND test runtime detected -> still refused.
        Map<String, String> env = Map.of(
                OrderTripwire.ENV_ALLOW_ORDERS, "1",
                "SUREFIRE_REPORT_DIR", "/tmp/reports");
        String cp = "/app/lib/x.jar" + java.io.File.pathSeparator + "/maven/surefire-booter-3.2.5.jar";

        assertFalse(OrderTripwire.decision(env, cp));
        assertThrows(IllegalStateException.class,
                () -> OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "anti-circumvention", env, cp));
    }

    @Test
    void testGate_notInstalledByDefault_refusesUnderTestRuntime() {
        // No gate installed -> even under a real test runtime the tripwire refuses: no default
        // authorisation in test.
        assertThrows(IllegalStateException.class,
                () -> OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "no-gate"));
    }

    @Test
    void testGate_installedUnderTestRuntime_allowsOrders() {
        OrderTripwire.allowOrdersForTestingOnly();
        try {
            // With the gate installed and the live test runtime detected, the 4-arg overload
            // must authorise (no exception).
            OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "test-gate",
                    System.getenv(), OrderTripwire.runtimeClassPath());
        } finally {
            OrderTripwire.resetForTestingOnly();
        }
    }

    @Test
    void testGate_flippedButProductionEnv_stillRefuses() {
        // Install the gate under the real test runtime, then prove that even with the flag
        // flipped, a production environment (no surefire on the classpath) is still refused:
        // the gate is honoured ONLY while a test runtime is detected.
        OrderTripwire.allowOrdersForTestingOnly();
        try {
            assertThrows(IllegalStateException.class,
                    () -> OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "flipped-but-prod",
                            Map.of(), NO_SUREFIRE));
        } finally {
            OrderTripwire.resetForTestingOnly();
        }
    }
}
