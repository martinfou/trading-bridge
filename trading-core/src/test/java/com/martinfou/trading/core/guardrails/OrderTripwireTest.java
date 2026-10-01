package com.martinfou.trading.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure {@link OrderTripwire#decision(Map, String)} function, the test-runtime
 * detection, the destination restriction on the test-only gate, and the fail-closed
 * {@link OrderTripwire#checkOrderAllowed} refusal. Hermetic: no broker, no network, no env mutation.
 */
class OrderTripwireTest {

    private static final String NO_SUREFIRE = "/app/lib/foo.jar:/app/lib/bar.jar";

    /** A classpath that clearly denotes a test runtime (surefire booter jar present by file name). */
    private static final String TEST_CP = "/tmp/surefire-booter-3.2.5.jar" + File.pathSeparator + "/app/lib/x.jar";

    /** An environment that denotes a test runtime via a {@code SUREFIRE_*} variable. */
    private static final Map<String, String> TEST_ENV = Map.of("SUREFIRE_REPORT_DIR", "/tmp/reports");

    private static final String LOCAL_HTTP = "http://127.0.0.1:8080/";

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
        String surefireClassPath = "/app/lib/x.jar" + File.pathSeparator
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
                () -> OrderTripwire.checkOrderAllowed("EUR_USD", "-1000", "test", LOCAL_HTTP));
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
    // Test-runtime detection: filename-only, never junit/jupiter/platform
    // ------------------------------------------------------------------

    @Test
    void isTestRuntime_tokenInDirectoryNotFilename_isNotTestRuntime() {
        // Anti-false-positive: the DIRECTORY contains the token, the file name does not. A checkout
        // directory named "surefire-docs" must not trip the detection in production.
        String cp = "/home/user/surefire-docs/readme.jar"
                + File.pathSeparator + "/home/user/gradle-build/notes.txt"
                + File.pathSeparator + "/home/user/testng-report/report.pdf";
        assertFalse(OrderTripwire.isTestRuntime(Map.of(), cp),
                "a token in a directory path must not count — only the file name is matched");
        assertTrue(OrderTripwire.decision(envWithFlag("1"), cp),
                "with the flag set and no test-runner jar, production must authorise");
    }

    @Test
    void decision_junitJarsOnProductionClasspath_flagAuthorises() {
        // Anti-production-outage (encodes the 2026-10-01 image measurement): the production image
        // ships 6 JUnit jars on its classpath. JUnit must NOT be treated as a test runtime, or every
        // container would refuse every order with TB_ALLOW_ORDERS=1 (silent total outage).
        String prodCp = String.join(File.pathSeparator,
                "/app/libs/junit-jupiter-5.11.0.jar",
                "/app/libs/junit-jupiter-api-5.11.0.jar",
                "/app/libs/junit-jupiter-engine-5.11.0.jar",
                "/app/libs/junit-jupiter-params-5.11.0.jar",
                "/app/libs/junit-platform-commons-1.11.0.jar",
                "/app/libs/junit-platform-engine-1.11.0.jar",
                "/app/libs/slf4j-api-2.0.16.jar");
        assertFalse(OrderTripwire.isTestRuntime(Map.of(), prodCp),
                "JUnit jars present in the production image must not be classified as a test runtime");
        assertTrue(OrderTripwire.decision(envWithFlag("1"), prodCp),
                "a production classpath (JUnit present, no test-runner jar) + flag=1 must authorise");
    }

    @Test
    void isTestRuntime_ideaRtAndGradleAndTestngDetected() {
        // IDE / Gradle runners: IntelliJ puts idea_rt.jar, Gradle forks a gradle-worker jar, TestNG
        // ships its own jar — all recognised as test runtimes so the gate can open under them.
        String ideaCp = "/home/user/.IntelliJIdea2024.1/system/idea_rt.jar";
        String gradleCp = "/home/user/.gradle/8.5/worker/gradle-worker-8.5.jar";
        String testngCp = "/opt/testng-7.8.0.jar";
        assertTrue(OrderTripwire.isTestRuntime(Map.of(), ideaCp));
        assertTrue(OrderTripwire.isTestRuntime(Map.of(), gradleCp));
        assertTrue(OrderTripwire.isTestRuntime(Map.of(), testngCp));
        // Defence in depth: a detected test runtime refuses even when the flag is present.
        assertFalse(OrderTripwire.decision(envWithFlag("1"), ideaCp));
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
                () -> OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "prod-sim", LOCAL_HTTP, prodEnv, prodCp));
    }

    @Test
    void testGate_flagPresentButTestRuntime_refuses() {
        // Anti-circumvention: flag present AND test runtime detected -> still refused.
        Map<String, String> env = Map.of(
                OrderTripwire.ENV_ALLOW_ORDERS, "1",
                "SUREFIRE_REPORT_DIR", "/tmp/reports");
        String cp = "/app/lib/x.jar" + File.pathSeparator + "/maven/surefire-booter-3.2.5.jar";

        assertFalse(OrderTripwire.decision(env, cp));
        assertThrows(IllegalStateException.class,
                () -> OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "anti-circumvention", LOCAL_HTTP, env, cp));
    }

    @Test
    void testGate_notInstalledByDefault_refusesUnderTestRuntime() {
        // No gate installed -> even under a real test runtime the tripwire refuses: no default
        // authorisation in test.
        assertThrows(IllegalStateException.class,
                () -> OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "no-gate", LOCAL_HTTP));
    }

    @Test
    void testGate_installedUnderTestRuntime_allowsOrders() throws Exception {
        // With the gate installed (try-with-resources) and the live test runtime detected, the
        // 6-arg overload must authorise a LOCAL destination (no exception).
        try (AutoCloseable ignored = OrderTripwire.allowOrdersForTestingOnly()) {
            OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "test-gate", LOCAL_HTTP,
                    System.getenv(), OrderTripwire.runtimeClassPath());
        }
    }

    @Test
    void testGate_flippedButProductionEnv_stillRefuses() throws Exception {
        // Install the gate under the real test runtime, then prove that even with the gate open,
        // a production environment (no test-runner jar on the classpath) is still refused:
        // the gate is honoured ONLY while a test runtime is detected.
        try (AutoCloseable ignored = OrderTripwire.allowOrdersForTestingOnly()) {
            assertThrows(IllegalStateException.class,
                    () -> OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "flipped-but-prod",
                            LOCAL_HTTP, Map.of(), NO_SUREFIRE));
        }
    }

    // ------------------------------------------------------------------
    // Destination restriction: the gate can only reach a local destination
    // ------------------------------------------------------------------

    @Test
    void testGate_installedButRealBrokerDestination_refuses() throws Exception {
        // ANTI-INCIDENT: the gate is open (test runtime + installed), but the destination is the
        // real OANDA practice URL. The tripwire must refuse BEFORE any I/O — the throw happens in
        // checkOrderAllowed, ahead of any HTTP request, so this is hermetic and sends zero requests.
        try (AutoCloseable ignored = OrderTripwire.allowOrdersForTestingOnly(TEST_ENV, TEST_CP)) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "anti-incident",
                            "https://api-fxpractice.oanda.com", TEST_ENV, TEST_CP));
            assertTrue(ex.getMessage().contains("local destination"),
                    "refusal must name the local-destination rule: " + ex.getMessage());
        }
    }

    @Test
    void testGate_installedAndLocalDestination_allows() throws Exception {
        // POSITIVE CONTROL: with the gate open, a local destination (any loopback spelling) is
        // authorised — this is what the restored POST retry tests rely on to keep working.
        try (AutoCloseable ignored = OrderTripwire.allowOrdersForTestingOnly(TEST_ENV, TEST_CP)) {
            OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "local-http", "http://127.0.0.1:54321/", TEST_ENV, TEST_CP);
            OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "local-host", "127.0.0.1", TEST_ENV, TEST_CP);
            OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "local-localhost", "localhost:54321", TEST_ENV, TEST_CP);
            OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "local-ipv6-bracketed", "[::1]", TEST_ENV, TEST_CP);
            OrderTripwire.checkOrderAllowed("EUR_USD", "1000", "local-ipv6-bare", "::1", TEST_ENV, TEST_CP);
        }
    }
}
