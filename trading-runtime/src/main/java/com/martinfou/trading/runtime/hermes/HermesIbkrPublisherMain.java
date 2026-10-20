package com.martinfou.trading.runtime.hermes;

import com.martinfou.trading.data.ibkr.EClientIbkrGatewayClient;
import com.martinfou.trading.data.ibkr.IbkrConnectionConfig;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Standalone read-only publisher: IB Gateway -&gt; hermes-web.
 *
 * <pre>
 *   HERMES_WEB_URL=https://hermes.martinfournier.com HERMES_WEB_TOKEN=... \
 *   java -cp trading-runtime/target/trading-runtime-*-shaded.jar \
 *        com.martinfou.trading.runtime.hermes.HermesIbkrPublisherMain [--once] [--dry-run] [--interval 60]
 * </pre>
 *
 * <p>Connection comes from {@code IBKR_*} env vars or {@code ~/.trading-bridge/ibkr.properties}, on the paper
 * port by default. It uses its own client id ({@code HERMES_IBKR_CLIENT_ID}, default 90) so it never shares a
 * session with a trading client. To see fills placed by other API clients, set that id as the
 * <em>Master API client ID</em> in IB Gateway (Configure &gt; Settings &gt; API &gt; Settings).</p>
 */
public final class HermesIbkrPublisherMain {

    static final int DEFAULT_CLIENT_ID = 90;

    private HermesIbkrPublisherMain() {}

    public static void main(String[] args) throws Exception {
        List<String> a = Arrays.asList(args);
        boolean once = a.contains("--once");
        boolean dryRun = a.contains("--dry-run");
        boolean live = a.contains("--live");
        int interval = intArg(a, "--interval", 60);

        IbkrConnectionConfig base = IbkrConnectionConfig.fromEnvironment(!live).orElseGet(
            () -> new IbkrConnectionConfig("127.0.0.1",
                live ? IbkrConnectionConfig.DEFAULT_LIVE_PORT : IbkrConnectionConfig.DEFAULT_PAPER_PORT, 1, null));
        int clientId = parseInt(System.getenv("HERMES_IBKR_CLIENT_ID"), DEFAULT_CLIENT_ID);
        IbkrConnectionConfig config = new IbkrConnectionConfig(base.host(), base.port(), clientId, base.accountId());

        HermesIbkrPublisher.Sender sender = dryRun
            ? (path, body) -> {
                System.out.println("DRY RUN POST " + path + "\n" + body);
                return new HermesIbkrPublisher.Sender.Response(200, "{\"dry_run\":true}");
            }
            : new HttpHermesSender(System.getenv("HERMES_WEB_URL"), System.getenv("HERMES_WEB_TOKEN"));

        Path stateDir = Path.of(System.getenv().getOrDefault("HERMES_IBKR_STATE_DIR", "data/runtime"));
        EClientIbkrGatewayClient client = new EClientIbkrGatewayClient(config);
        String label = config.host() + ":" + config.port();

        try (HermesIbkrPublisher publisher = new HermesIbkrPublisher(client, sender,
                dryRun ? stateDir.resolve("dry-run") : stateDir, Clock.systemUTC(), label)) {
            if (once) {
                HermesIbkrPublisher.CycleResult r = publisher.runOnce();
                System.out.println(r);
                client.disconnect();
                System.exit(r.error() == null ? 0 : 1);
            }
            publisher.start(Duration.ofSeconds(interval));
            CountDownLatch forever = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                publisher.close();
                client.disconnect();
                forever.countDown();
            }));
            forever.await();
        }
    }

    private static int intArg(List<String> args, String name, int def) {
        int i = args.indexOf(name);
        return i >= 0 && i + 1 < args.size() ? parseInt(args.get(i + 1), def) : def;
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
