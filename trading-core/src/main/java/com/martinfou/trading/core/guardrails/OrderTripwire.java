package com.martinfou.trading.core.guardrails;

import java.net.URI;
import java.nio.file.Path;
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
 * a test runtime is detected (defence in depth): a known test-runner jar on the JVM classpath, or
 * any {@code SUREFIRE_*} environment variable. Even an explicit flag does not override a detected
 * test runtime.
 *
 * <p>This is the single decision point for the whole repository: every order-sending method in the
 * live OANDA executor, the live OANDA HTTP client and the live IBKR gateway client calls
 * {@link #checkOrderAllowed(String, String, String, String)} before building any request. Test
 * doubles ({@code FakeBroker}, {@code StubOandaRestClient}, {@code StubIbkrGatewayClient}) are
 * in-memory and never reach a broker, so they are deliberately not wired to this tripwire.
 *
 * <p>Test code that must exercise the <em>live</em> order path (e.g. the HTTP client's POST retry
 * loop) can open an explicit, test-only gate via {@link #allowOrdersForTestingOnly()}. The gate is
 * honoured only while a test runtime is detected, the install call itself throws in production, and
 * — crucially — a gate-honoured order may only target a <em>local</em> destination
 * ({@code localhost}, {@code 127.0.0.1}, {@code ::1}). Even an open gate can therefore never reach a
 * real broker. {@link #decision(Map, String)} remains the pure, unchanged fail-closed decision
 * function.
 */
public final class OrderTripwire {

    /** Environment variable that authorises order dispatch. */
    public static final String ENV_ALLOW_ORDERS = "TB_ALLOW_ORDERS";

    /** Guardrails policy document referenced by the refusal message. */
    public static final String GUARDRAILS_DOC = "docs/TRADING-GUARDRAILS.md";

    private static final Logger LOG = LoggerFactory.getLogger(OrderTripwire.class);
    private static final AtomicBoolean ALLOWED_LOGGED = new AtomicBoolean(false);

    /**
     * Filename tokens that identify a test runtime. Matching is done against the FILE NAME of each
     * classpath entry ({@link Path#getFileName()}), never the full path, so a checkout directory
     * named e.g. {@code surefire-docs} cannot produce a false positive in production.
     *
     * <p><b>MEASUREMENT (do not widen without re-measuring).</b> The production image was inspected
     * on 2026-10-01 ({@code docker run --rm --entrypoint sh <prod-image> -c 'ls /app/libs'}):
     * <b>47 jars</b>, of which <b>6 are JUnit</b> ({@code junit-jupiter-5.11.0},
     * {@code junit-jupiter-api}, {@code junit-jupiter-engine}, {@code junit-jupiter-params},
     * {@code junit-platform-commons}, {@code junit-platform-engine}) and <b>0</b> are
     * {@code surefire}, {@code testng} or {@code idea_rt}. JUnit therefore <em>lives on the
     * production classpath</em>. Adding {@code junit} (or {@code jupiter}, or {@code platform}) to
     * this list would classify every production container as a test runtime and refuse
     * <em>every</em> order even with {@value #ENV_ALLOW_ORDERS}=1 — a silent total trading outage.
     * <b>NEVER add junit/jupiter/platform here.</b>
     */
    private static final String[] TEST_RUNTIME_TOKENS = {"surefire", "idea_rt", "testng", "gradle"};

    /**
     * Test-only escape hatch. Installed exclusively by test code via
     * {@link #allowOrdersForTestingOnly()} and honoured by {@link #checkOrderAllowed} ONLY while a
     * test runtime is detected on the classpath/environment. Defaults to {@code false}: a test that
     * does not explicitly install the gate remains refused. Cleared via
     * {@link #resetForTestingOnly()} (or, preferably, by closing the {@link AutoCloseable} returned
     * by {@link #allowOrdersForTestingOnly()}) so it never leaks across test classes sharing a JVM.
     */
    private static final AtomicBoolean TEST_GATE_INSTALLED = new AtomicBoolean(false);

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

    /**
     * True when a known test-runner jar appears (by file name) on the classpath or a
     * {@code SUREFIRE_*} env var is set. The comparison is against the <em>file name</em> of each
     * entry, so a directory whose <em>path</em> happens to contain a token (e.g.
     * {@code /home/me/surefire-docs/x.jar}) does NOT count.
     */
    static boolean isTestRuntime(Map<String, String> env, String classPath) {
        if (classPath != null) {
            for (String entry : classPath.split(java.io.File.pathSeparator)) {
                String fileName = fileNameOf(entry);
                if (fileName == null) {
                    continue;
                }
                fileName = fileName.toLowerCase(Locale.ROOT);
                for (String token : TEST_RUNTIME_TOKENS) {
                    if (fileName.contains(token)) {
                        return true;
                    }
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

    /** Returns the file name of a classpath entry, or {@code null} when it has none. */
    private static String fileNameOf(String entry) {
        if (entry == null || entry.isEmpty()) {
            return null;
        }
        try {
            Path fileName = Path.of(entry).getFileName();
            return fileName == null ? null : fileName.toString();
        } catch (RuntimeException invalidPath) {
            int lastSep = Math.max(entry.lastIndexOf('/'), entry.lastIndexOf('\\'));
            return lastSep >= 0 ? entry.substring(lastSep + 1) : entry;
        }
    }

    /**
     * Refuses to send an order unless the tripwire is satisfied. Throws {@link IllegalStateException}
     * with an actionable message naming {@value #ENV_ALLOW_ORDERS} and {@value #GUARDRAILS_DOC}.
     * Logs exactly once per JVM when the path is authorised (proof that a container carries the flag).
     *
     * <p>When the test-only gate is honoured, the destination {@code target} MUST be local
     * ({@code localhost}, {@code 127.0.0.1}, {@code ::1}); any other destination is refused even
     * though the gate is open. In production (flag path) the destination is not restricted.
     *
     * @param instrument instrument (or the most specific identifier available at the call site)
     * @param units      units (or the most specific quantity available at the call site)
     * @param context    human-readable call-site label, e.g. {@code OandaExecutor.placeMarketOrder}
     * @param target     the destination host or base URL of this order (e.g. {@code baseUrl} for the
     *                   OANDA clients, {@code config.host()} for the IBKR gateway client)
     */
    public static void checkOrderAllowed(String instrument, String units, String context, String target) {
        checkOrderAllowed(instrument, units, context, target, System.getenv(), runtimeClassPath());
    }

    /**
     * Package-private overload with an explicit environment and classpath so the fail-closed,
     * production-refusal and destination contracts can be exercised hermetically (no env /
     * system-property mutation). The public 4-arg form delegates here with the live
     * {@code System.getenv()} / {@link #runtimeClassPath()}.
     *
     * <p>The test-only gate is honoured <em>only</em> while a test runtime is detected: in a
     * production environment (no test-runner jar on the classpath, no {@code SUREFIRE_*} env var)
     * the gate is ignored even if the flag was somehow flipped, and only the
     * {@value #ENV_ALLOW_ORDERS} flag can authorise. And when the gate IS honoured, the destination
     * must be local — the gate is strictly weaker than the flag, never able to touch a real broker.
     */
    static void checkOrderAllowed(String instrument, String units, String context, String target,
                                  Map<String, String> env, String classPath) {
        if (isTestRuntime(env, classPath) && TEST_GATE_INSTALLED.get()) {
            if (!isLocalDestination(target)) {
                throw new IllegalStateException(
                        "Order refused by the order tripwire: the test-only gate authorises orders ONLY "
                                + "to a local destination (localhost, 127.0.0.1, ::1), but the destination is "
                                + (target == null ? "<missing>" : target) + ". A test must never reach a real broker. "
                                + "instrument=" + instrument + ", units=" + units + ", context=" + context
                                + ". See " + GUARDRAILS_DOC + ".");
            }
            LOG.debug("Order path authorised via test-only override (test runtime detected, local destination): "
                            + "instrument={}, units={}, context={}, target={}",
                    instrument, units, context, target);
            return;
        }
        if (decision(env, classPath)) {
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
     * True when {@code target} names a loopback destination. Accepts a full URL (host extracted from
     * it), a bare {@code host[:port]} and a bracketed or bare IPv6 literal.
     */
    static boolean isLocalDestination(String target) {
        String host = extractHost(target);
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
    }

    private static String extractHost(String target) {
        if (target == null) {
            return "";
        }
        String t = target.trim();
        if (t.isEmpty()) {
            return "";
        }
        int schemeIdx = t.indexOf("://");
        if (schemeIdx >= 0) {
            try {
                return URI.create(t).getHost();
            } catch (IllegalArgumentException invalidUri) {
                return "";
            }
        }
        String host = t;
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        if (host.startsWith("[") && host.contains("]")) {
            return host.substring(1, host.indexOf(']'));
        }
        int firstColon = host.indexOf(':');
        int lastColon = host.lastIndexOf(':');
        if (firstColon >= 0 && firstColon == lastColon) {
            host = host.substring(0, firstColon); // single colon => host:port
        }
        return host;
    }

    /**
     * Test-only escape hatch. Authorises {@link #checkOrderAllowed} to pass — but ONLY when a test
     * runtime is detected (a test-runner jar on the classpath, or a {@code SUREFIRE_*} env var),
     * and even then only for a <em>local</em> destination.
     *
     * <p>In production (no test runtime) this method <em>throws</em> and never opens the gate, so it
     * cannot be used to dispatch a live order. The returned {@link AutoCloseable} clears the gate
     * when closed, so prefer the try-with-resources form to avoid leaking the gate across tests
     * sharing a JVM. A test that does not call this method remains refused (no default authorisation).
     */
    public static AutoCloseable allowOrdersForTestingOnly() {
        return allowOrdersForTestingOnly(System.getenv(), runtimeClassPath());
    }

    /** Package-private: installs the test gate only when {@code env}/{@code classPath} show a test runtime. */
    static AutoCloseable allowOrdersForTestingOnly(Map<String, String> env, String classPath) {
        if (!isTestRuntime(env, classPath)) {
            throw new IllegalStateException(
                    "OrderTripwire: test-only override refused — no test runtime detected on the "
                            + "classpath or in the environment. This gate can never be opened in production.");
        }
        TEST_GATE_INSTALLED.set(true);
        return OrderTripwire::resetForTestingOnly;
    }

    /**
     * Clears the test gate so it never leaks across test classes sharing a JVM. Public (not
     * package-private) because order-path tests live in other modules/packages. This method only
     * ever <em>closes</em> the gate — it sets the flag back to the fail-closed default, so it can
     * never be used to authorise an order.
     */
    public static void resetForTestingOnly() {
        TEST_GATE_INSTALLED.set(false);
    }

    /**
     * Classpath used for test-runtime detection. Surefire forks the test JVM and does NOT put its
     * own jars on {@code java.class.path}; it ships the surefirebooter jar in the
     * {@code surefire.real.class.path} system property instead (and the test classes in
     * {@code surefire.test.class.path}). Merging them is what makes the defence-in-depth detection
     * actually fire in a real test run, not just in a synthetic unit test. IntelliJ ({@code idea_rt})
     * and Gradle worker jars, by contrast, do sit on {@code java.class.path} directly.
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
