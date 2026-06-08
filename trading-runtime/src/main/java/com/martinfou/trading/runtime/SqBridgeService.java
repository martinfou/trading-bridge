package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.parser.bridge.SqBridgePaths;
import com.martinfou.trading.parser.bridge.SqCliNotFoundException;
import com.martinfou.trading.parser.bridge.SqCliOptions;
import com.martinfou.trading.parser.bridge.SqCliPaths;
import com.martinfou.trading.parser.bridge.SqCliRunner;
import com.martinfou.trading.parser.bridge.SqCliRunResult;
import com.martinfou.trading.parser.bridge.SqInboxBatchResult;
import com.martinfou.trading.parser.bridge.SqInboxOptions;
import com.martinfou.trading.parser.bridge.SqInboxPaths;
import com.martinfou.trading.parser.bridge.SqInboxProcessor;
import com.martinfou.trading.parser.bridge.SqInboxProgressListener;
import com.martinfou.trading.parser.bridge.SqJobBusyException;
import com.martinfou.trading.parser.bridge.SqJobMutex;
import com.martinfou.trading.parser.bridge.SqJobPaths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Control-plane integration for the StrategyQuant hot-folder bridge (story 21-7).
 */
public final class SqBridgeService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqBridgeService.class);

    static final Duration PROBE_TTL = Duration.ofSeconds(60);
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);
    static final List<String> PROBE_ARGS = List.of("-symbol", "action=list");

    private final EventStore eventStore;
    private final Path repoRoot;
    private final Clock clock;
    private final ExecutorService executor;
    private final SqInboxProcessor inboxProcessor;
    private final SqCliRunner cliRunner;

    private final AtomicBoolean inboxProcessing = new AtomicBoolean(false);
    private volatile ProbeSnapshot probeSnapshot = ProbeSnapshot.unconfigured();
    private volatile InboxRunSnapshot lastInboxRun;

    public SqBridgeService(EventStore eventStore) {
        this(
            eventStore,
            SqBridgePaths.resolveRepoRoot(),
            Clock.systemUTC(),
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "sq-bridge-inbox");
                t.setDaemon(true);
                return t;
            }),
            new SqInboxProcessor(),
            new SqCliRunner()
        );
    }

    /** Package-private for tests with a custom repo root. */
    SqBridgeService(EventStore eventStore, Path repoRoot) {
        this(
            eventStore,
            repoRoot,
            Clock.systemUTC(),
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "sq-bridge-inbox");
                t.setDaemon(true);
                return t;
            }),
            new SqInboxProcessor(),
            new SqCliRunner()
        );
    }

    SqBridgeService(
        EventStore eventStore,
        Path repoRoot,
        Clock clock,
        ExecutorService executor,
        SqInboxProcessor inboxProcessor,
        SqCliRunner cliRunner
    ) {
        this.eventStore = eventStore;
        this.repoRoot = repoRoot;
        this.clock = clock;
        this.executor = executor;
        this.inboxProcessor = inboxProcessor;
        this.cliRunner = cliRunner;
    }

    public Map<String, Object> status() {
        refreshProbeIfNeeded();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("sqHomeConfigured", probeSnapshot.sqHomeConfigured());
        json.put("sqcliReachable", probeSnapshot.sqcliReachable());
        json.put("inboxPendingCount", countPendingXml());
        json.put("lastProbeAt", probeSnapshot.lastProbeAt() != null
            ? probeSnapshot.lastProbeAt().toString() : null);
        json.put("inboxProcessing", inboxProcessing.get());
        if (lastInboxRun != null) {
            json.put("lastInboxRun", lastInboxRun.toMap());
        }
        return json;
    }

    public ProcessInboxResponse processInboxAsync() {
        if (!inboxProcessing.compareAndSet(false, true)) {
            return ProcessInboxResponse.rejected("Inbox processing already running");
        }
        executor.submit(this::runInboxWorker);
        return ProcessInboxResponse.started();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void runInboxWorker() {
        Instant startedAt = clock.instant();
        try (SqJobMutex ignored = SqJobMutex.acquire(SqJobPaths.lockFile(repoRoot))) {
            SqInboxOptions options = new SqInboxOptions(repoRoot, null, SqInboxOptions.DEFAULT_CAPITAL,
                SqInboxOptions.DEFAULT_SYNTHETIC_BARS, List.of());
            SqInboxProgressListener listener = (fileName, disposition, manifestId) -> {
                RunEvent event = RunEvent.sqExportReceived(fileName, disposition, manifestId, clock.instant());
                eventStore.append("sq-bridge", event);
            };
            SqInboxBatchResult result = inboxProcessor.runBatch(options, listener);
            lastInboxRun = InboxRunSnapshot.completed(startedAt, clock.instant(), result, null);
            log.info("SQ inbox processed: {}", result);
        } catch (SqJobBusyException e) {
            lastInboxRun = InboxRunSnapshot.failed(startedAt, clock.instant(), e.getMessage());
            log.warn("SQ inbox skipped: {}", e.getMessage());
        } catch (IOException e) {
            lastInboxRun = InboxRunSnapshot.failed(startedAt, clock.instant(), e.getMessage());
            log.warn("SQ inbox failed: {}", e.getMessage());
        } catch (Exception e) {
            lastInboxRun = InboxRunSnapshot.failed(startedAt, clock.instant(), e.getMessage());
            log.warn("SQ inbox error: {}", e.getMessage());
        } finally {
            inboxProcessing.set(false);
        }
    }

    private void refreshProbeIfNeeded() {
        Instant now = clock.instant();
        if (probeSnapshot.lastProbeAt() != null
            && Duration.between(probeSnapshot.lastProbeAt(), now).compareTo(PROBE_TTL) < 0) {
            return;
        }
        probeSnapshot = probe(now);
    }

    ProbeSnapshot probe(Instant now) {
        if (!sqHomeConfigured()) {
            return ProbeSnapshot.unconfigured();
        }
        try {
            Path sqHome = resolveSqHomeDirectory();
            SqCliRunResult result = cliRunner.run(
                new SqCliOptions(sqHome, false, PROBE_TIMEOUT),
                PROBE_ARGS
            );
            return new ProbeSnapshot(true, result.exitCode() == 0, now);
        } catch (SqCliNotFoundException e) {
            return new ProbeSnapshot(true, false, now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeSnapshot(true, false, now);
        } catch (IOException e) {
            return new ProbeSnapshot(true, false, now);
        }
    }

    static boolean sqHomeConfigured() {
        String env = System.getenv(SqCliPaths.SQ_HOME_ENV);
        if (env == null || env.isBlank()) {
            env = System.getProperty("sq.bridge.sq.home");
        }
        if (env == null || env.isBlank()) {
            return false;
        }
        return Files.isDirectory(Path.of(env.trim()));
    }

    private static Path resolveSqHomeDirectory() throws SqCliNotFoundException {
        String env = System.getenv(SqCliPaths.SQ_HOME_ENV);
        if (env == null || env.isBlank()) {
            env = System.getProperty("sq.bridge.sq.home");
        }
        if (env == null || env.isBlank()) {
            throw new SqCliNotFoundException("SQ_HOME not configured");
        }
        return Path.of(env.trim());
    }

    private int countPendingXml() {
        Path pending = SqInboxPaths.pending(repoRoot);
        if (!Files.isDirectory(pending)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(pending)) {
            return (int) stream
                .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".xml"))
                .count();
        } catch (IOException e) {
            return 0;
        }
    }

    record ProbeSnapshot(boolean sqHomeConfigured, boolean sqcliReachable, Instant lastProbeAt) {
        static ProbeSnapshot unconfigured() {
            return new ProbeSnapshot(false, false, null);
        }
    }

    record InboxRunSnapshot(
        Instant startedAt,
        Instant completedAt,
        String status,
        SqInboxBatchResult result,
        String error
    ) {
        static InboxRunSnapshot completed(Instant startedAt, Instant completedAt, SqInboxBatchResult result, String error) {
            return new InboxRunSnapshot(startedAt, completedAt, "completed", result, error);
        }

        static InboxRunSnapshot failed(Instant startedAt, Instant completedAt, String error) {
            return new InboxRunSnapshot(startedAt, completedAt, "failed", SqInboxBatchResult.empty(), error);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("startedAt", startedAt.toString());
            map.put("completedAt", completedAt.toString());
            map.put("status", status);
            if (result != null) {
                map.put("processed", result.processed());
                map.put("passed", result.passed());
                map.put("failed", result.failed());
                map.put("dlq", result.dlq());
            }
            if (error != null && !error.isBlank()) {
                map.put("error", error);
            }
            return map;
        }
    }

    public record ProcessInboxResponse(boolean accepted, String message) {
        public static ProcessInboxResponse started() {
            return new ProcessInboxResponse(true, "Inbox processing started");
        }

        public static ProcessInboxResponse rejected(String message) {
            return new ProcessInboxResponse(false, message);
        }
    }
}
