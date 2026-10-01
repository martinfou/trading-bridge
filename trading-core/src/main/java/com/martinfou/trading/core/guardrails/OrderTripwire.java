package com.martinfou.trading.core.guardrails;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fail-closed tripwire on the order path.
 *
 * <p>No order may be sent to a broker unless the environment variable
 * {@value #ENV_ALLOW_ORDERS} is explicitly set to {@code 1} or {@code true} (case-insensitive).
 * Containers carry this flag; test JVMs never do. On top of that, the tripwire always refuses when
 * a test runtime is detected (defence in depth): {@code surefire} present on the JVM classpath, or
 * any {@code SUREFIRE_*} environment variable. Even an explicit flag does not override a detected
 * test runtime.
 *
 * <p>This is the single decision point for the whole repository: every order-sending method in the
 * live OANDA executor, the live OANDA HTTP client and the live IBKR gateway client calls
 * {@link #checkOrderAllowed(String, String, String)} before building any request. Test doubles
 * ({@code FakeBroker}, {@code StubOandaRestClient}, {@code StubIbkrGatewayClient}) are in-memory and
 * never reach a broker, so they are deliberately not wired to this tripwire.
 */
public final class OrderTripwire {

    /** Environment variable that authorises order dispatch. */
    public static final String ENV_ALLOW_ORDERS = "TB_ALLOW_ORDERS";

    /** Guardrails policy document referenced by the refusal message. */
    public static final String GUARDRAILS_DOC = "docs/TRADING-GUARDRAILS.md";

    private static final Logger LOG = LoggerFactory.getLogger(OrderTripwire.class);
    private static final AtomicBoolean ALLOWED_LOGGED = new AtomicBoolean(false);

    private OrderTripwire() {
    }

    /**
     * Pure decision function (no side effects, fully testable).
     *
     * @param env       process environment ({@code System.getenv()} in production, arbitrary map in tests)
     * @param classPath JVM classpath ({@code System.getProperty("java.class.path")} in production)
     * @return {@code true} only when a test runtime is NOT detected and {@code TB_ALLOW_ORDERS} is
     *         exactly {@code 1} or {@code true} (case-insensitive, trimmed); {@code false} otherwise.
     */
    public static boolean decision(Map<String, String> env, String classPath) {
        if (isTestRuntime(env, classPath)) {
            return false;
        }
        String flag = env == null ? null : env.get(ENV_ALLOW_ORDERS);
        if (flag == null) {
            return false;
        }
        String value = flag.trim().toLowerCase(Locale.ROOT);
        return "1".equals(value) || "true".equals(value);
    }

    /** True when {@code surefire} appears on the classpath or a {@code SUREFIRE_*} env var is set. */
    static boolean isTestRuntime(Map<String, String> env, String classPath) {
        if (classPath != null) {
            for (String entry : classPath.split(java.io.File.pathSeparator)) {
                if (entry.toLowerCase(Locale.ROOT).contains("surefire")) {
                    return true;
                }
            }
        }
        if (env != null) {
            for (String key : env.keySet()) {
                if (key.startsWith("SUREFIRE_")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Refuses to send an order unless the tripwire is satisfied. Throws {@link IllegalStateException}
     * with an actionable message naming {@value #ENV_ALLOW_ORDERS} and {@value #GUARDRAILS_DOC}.
     * Logs exactly once per JVM when the path is authorised (proof that a container carries the flag).
     *
     * @param instrument instrument (or the most specific identifier available at the call site)
     * @param units      units (or the most specific quantity available at the call site)
     * @param context    human-readable call-site label, e.g. {@code OandaExecutor.placeMarketOrder}
     */
    public static void checkOrderAllowed(String instrument, String units, String context) {
        Map<String, String> env = System.getenv();
        if (decision(env, runtimeClassPath())) {
            if (ALLOWED_LOGGED.compareAndSet(false, true)) {
                LOG.info("Order path authorised ({}={}) — live/paper order dispatch enabled. See {}.",
                        ENV_ALLOW_ORDERS, env.get(ENV_ALLOW_ORDERS), GUARDRAILS_DOC);
            }
            return;
        }
        throw new IllegalStateException(
                "Order refused by the order tripwire: " + ENV_ALLOW_ORDERS
                        + " is not set to 1/true, or a test runtime was detected. "
                        + "instrument=" + instrument + ", units=" + units + ", context=" + context
                        + ". Set " + ENV_ALLOW_ORDERS + "=1 on the trading containers "
                        + "(docker-compose.yml) before dispatching orders. See " + GUARDRAILS_DOC + ".");
    }

    /**
     * Classpath used for test-runtime detection. Surefire forks the test JVM and does NOT put its
     * own jars on {@code java.class.path}; it ships the surefirebooter jar in the
     * {@code surefire.real.class.path} system property instead (and the test classes in
     * {@code surefire.test.class.path}). Merging them is what makes the defence-in-depth detection
     * actually fire in a real test run, not just in a synthetic unit test.
     */
    static String runtimeClassPath() {
        StringBuilder cp = new StringBuilder(System.getProperty("java.class.path", ""));
        for (String key : new String[] {"surefire.test.class.path", "surefire.real.class.path"}) {
            String value = System.getProperty(key);
            if (value != null && !value.isBlank()) {
                cp.append(java.io.File.pathSeparator).append(value);
            }
        }
        return cp.toString();
    }
}
