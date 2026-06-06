package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.intelligence.RepoRoots;
import com.martinfou.trading.intelligence.compile.CompileManifest;
import com.martinfou.trading.intelligence.compile.CompileManifestIO;
import com.martinfou.trading.intelligence.compile.WeeklyCompileWatcher;
import com.martinfou.trading.intelligence.deploy.ControlPlaneHttpClient;
import com.martinfou.trading.intelligence.deploy.WeeklyDeployResult;
import com.martinfou.trading.intelligence.deploy.WeeklyDeployWatcher;
import com.martinfou.trading.intelligence.ingest.IngestPipeline;
import com.martinfou.trading.intelligence.job.WeeklyPlanJob;
import com.martinfou.trading.intelligence.llm.HttpDeepSeekClient;
import com.martinfou.trading.intelligence.paths.WeeklyBuilderPaths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** Control-plane integration for Epic 22 weekly strategy builder hot-folder pipeline. */
public final class WeeklyBuilderService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WeeklyBuilderService.class);

    private final EventStore eventStore;
    private final Path repoRoot;
    private final Clock clock;
    private final ExecutorService executor;
    private final String controlPlaneUrl;
    private final ControlPlaneHttpClient controlPlaneClient;

    private final AtomicBoolean planProcessing = new AtomicBoolean(false);
    private final AtomicBoolean compileProcessing = new AtomicBoolean(false);
    private final AtomicBoolean deployProcessing = new AtomicBoolean(false);

    private volatile JobRunSnapshot lastPlanRun;
    private volatile JobRunSnapshot lastCompileRun;
    private volatile JobRunSnapshot lastDeployRun;

    public WeeklyBuilderService(EventStore eventStore) {
        this(
            eventStore,
            RepoRoots.findRepoRoot(),
            Clock.systemUTC(),
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "weekly-builder");
                t.setDaemon(true);
                return t;
            }),
            resolveControlPlaneUrl(),
            ControlPlaneHttpClient.fromEnvironment()
        );
    }

    WeeklyBuilderService(EventStore eventStore, Path repoRoot) {
        this(
            eventStore,
            repoRoot,
            Clock.systemUTC(),
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "weekly-builder");
                t.setDaemon(true);
                return t;
            }),
            resolveControlPlaneUrl(),
            ControlPlaneHttpClient.fromEnvironment()
        );
    }

    WeeklyBuilderService(
        EventStore eventStore,
        Path repoRoot,
        Clock clock,
        ExecutorService executor,
        String controlPlaneUrl,
        ControlPlaneHttpClient controlPlaneClient
    ) {
        this.eventStore = eventStore;
        this.repoRoot = repoRoot;
        this.clock = clock;
        this.executor = executor;
        this.controlPlaneUrl = controlPlaneUrl;
        this.controlPlaneClient = controlPlaneClient;
    }

    public Map<String, Object> status() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("pendingCount", countPendingPlans(WeeklyBuilderPaths.pending(repoRoot)));
        json.put("compilingCount", countCompilingPlans(WeeklyBuilderPaths.compiling(repoRoot)));
        json.put("compiledBundleCount", countSubdirectories(WeeklyBuilderPaths.compiled(repoRoot)));
        // Legacy alias — was counting .gitkeep as 1; keep key for TUI compatibility
        json.put("compiledCount", json.get("compiledBundleCount"));
        json.put("deployedBundleCount", countSubdirectories(WeeklyBuilderPaths.deployed(repoRoot)));
        json.put("failedBundleCount", countFailedBundles(WeeklyBuilderPaths.failed(repoRoot)));
        json.put("dlqCount", countDlqPlans(WeeklyBuilderPaths.dlq(repoRoot)));
        json.put("planProcessing", planProcessing.get());
        json.put("compileProcessing", compileProcessing.get());
        json.put("deployProcessing", deployProcessing.get());
        json.put("controlPlaneUrl", controlPlaneUrl);

        readCompiledManifest().ifPresent(manifest -> {
            json.put("lastWeekId", manifest.weekId());
            if (manifest.strategies() != null) {
                json.put("templates", manifest.strategies().stream()
                    .map(CompileManifest.StrategyEntry::templateId)
                    .toList());
            }
            if (manifest.validFrom() != null) {
                json.put("validFrom", manifest.validFrom().toString());
            }
            if (manifest.validUntil() != null) {
                json.put("validUntil", manifest.validUntil().toString());
            }
        });

        latestFailureReason().ifPresent(reason -> json.put("lastFailureReason", reason));

        if (lastPlanRun != null) {
            json.put("lastPlanRun", lastPlanRun.toMap());
        }
        if (lastCompileRun != null) {
            json.put("lastCompileRun", lastCompileRun.toMap());
        }
        if (lastDeployRun != null) {
            json.put("lastDeployRun", lastDeployRun.toMap());
        }
        return json;
    }

    public TriggerResponse triggerPlanAsync() {
        return triggerAsync("plan", planProcessing, this::runPlanWorker);
    }

    public TriggerResponse triggerCompileAsync() {
        return triggerAsync("compile", compileProcessing, this::runCompileWorker);
    }

    public TriggerResponse triggerDeployAsync() {
        return triggerAsync("deploy", deployProcessing, this::runDeployWorker);
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    private TriggerResponse triggerAsync(String step, AtomicBoolean flag, Runnable worker) {
        if (!flag.compareAndSet(false, true)) {
            return TriggerResponse.rejected(capitalize(step) + " job already running");
        }
        executor.submit(worker);
        return TriggerResponse.started(capitalize(step) + " job started");
    }

    private void runPlanWorker() {
        Instant startedAt = clock.instant();
        try {
            WeeklyPlanJob job = new WeeklyPlanJob(repoRoot, new IngestPipeline(), new HttpDeepSeekClient());
            WeeklyPlanJob.Result result = job.run();
            lastPlanRun = JobRunSnapshot.fromPlan(startedAt, clock.instant(), result);
            appendEvent("plan", mapPlanStatus(result.status()), result.weekId(), result.weekId(),
                Map.of("message", result.message()));
            log.info("Weekly plan job {}: {}", result.status(), result.message());
        } catch (Exception e) {
            lastPlanRun = JobRunSnapshot.failed(startedAt, clock.instant(), "plan", e.getMessage());
            appendEvent("plan", "failed", null, null, Map.of("error", e.getMessage()));
            log.warn("Weekly plan job error: {}", e.getMessage());
        } finally {
            planProcessing.set(false);
        }
    }

    private void runCompileWorker() {
        Instant startedAt = clock.instant();
        try {
            WeeklyCompileWatcher watcher = new WeeklyCompileWatcher(repoRoot);
            WeeklyCompileWatcher.Result result = watcher.runOnce();
            lastCompileRun = JobRunSnapshot.fromCompile(startedAt, clock.instant(), result);
            appendEvent("compile", mapCompileStatus(result.status()), result.weekId(), result.weekId(),
                Map.of("message", result.message()));
            log.info("Weekly compile job {}: {}", result.status(), result.message());
        } catch (Exception e) {
            lastCompileRun = JobRunSnapshot.failed(startedAt, clock.instant(), "compile", e.getMessage());
            appendEvent("compile", "failed", null, null, Map.of("error", e.getMessage()));
            log.warn("Weekly compile job error: {}", e.getMessage());
        } finally {
            compileProcessing.set(false);
        }
    }

    private void runDeployWorker() {
        Instant startedAt = clock.instant();
        try {
            WeeklyDeployWatcher watcher = new WeeklyDeployWatcher(repoRoot, controlPlaneClient);
            WeeklyDeployResult result = watcher.run();
            lastDeployRun = JobRunSnapshot.fromDeploy(startedAt, clock.instant(), result);
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("noTradeWeek", result.noTradeWeek());
            if (result.runIds() != null && !result.runIds().isEmpty()) {
                extras.put("runIds", result.runIds());
            }
            if (result.error() != null) {
                extras.put("error", result.error());
            }
            appendEvent("deploy", result.success() ? "completed" : "failed",
                result.weekId(), result.correlationId(), extras);
            log.info("Weekly deploy {} for week {}", result.success() ? "OK" : "FAILED", result.weekId());
        } catch (Exception e) {
            lastDeployRun = JobRunSnapshot.failed(startedAt, clock.instant(), "deploy", e.getMessage());
            appendEvent("deploy", "failed", null, null, Map.of("error", e.getMessage()));
            log.warn("Weekly deploy job error: {}", e.getMessage());
        } finally {
            deployProcessing.set(false);
        }
    }

    private void appendEvent(
        String step,
        String status,
        String weekId,
        String correlationId,
        Map<String, Object> extras
    ) {
        String resolvedCorrelation = correlationId != null && !correlationId.isBlank()
            ? correlationId
            : "weekly-" + step + "-" + clock.instant().toEpochMilli();
        RunEvent event = RunEvent.weeklyBuilderEvent(
            resolvedCorrelation, step, status, weekId, extras, clock.instant());
        eventStore.append("weekly-builder", event);
    }

    private OptionalManifest readCompiledManifest() {
        try {
            var bundle = CompileManifestIO.findNextBundle(repoRoot);
            if (bundle.isEmpty()) {
                return OptionalManifest.empty();
            }
            return OptionalManifest.of(CompileManifestIO.read(bundle.get().manifestPath()));
        } catch (IOException e) {
            return OptionalManifest.empty();
        }
    }

    private static String mapPlanStatus(WeeklyPlanJob.Result.Status status) {
        return switch (status) {
            case APPROVED -> "completed";
            case REJECTED, CALENDAR_FAILED, INGEST_FAILED, DLQ, FAILED -> "failed";
        };
    }

    private static String mapCompileStatus(WeeklyCompileWatcher.Result.Status status) {
        return switch (status) {
            case SUCCESS -> "completed";
            case IDLE, BUSY, SKIPPED -> "skipped";
            case COMPILE_FAILED -> "failed";
        };
    }

    private java.util.Optional<String> latestFailureReason() {
        Path failedRoot = WeeklyBuilderPaths.failed(repoRoot);
        if (!Files.isDirectory(failedRoot)) {
            return java.util.Optional.empty();
        }
        try (Stream<Path> entries = Files.list(failedRoot)) {
            java.util.Optional<String> rootReason = entries
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().startsWith("reason-"))
                .map(this::readFailureDetail)
                .filter(s -> s != null && !s.isBlank())
                .findFirst();
            if (rootReason.isPresent()) {
                return rootReason;
            }
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
        return readBundleFailureReason(failedRoot);
    }

    private java.util.Optional<String> readBundleFailureReason(Path failedRoot) {
        try (Stream<Path> bundles = Files.list(failedRoot)) {
            return bundles
                .filter(Files::isDirectory)
                .map(dir -> dir.resolve(WeeklyBuilderPaths.REASON_FILE))
                .filter(Files::isRegularFile)
                .map(this::readFailureDetail)
                .filter(s -> s != null && !s.isBlank())
                .findFirst();
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    private String readFailureDetail(Path path) {
        try {
            String raw = Files.readString(path);
            if (raw.contains("\"detail\"")) {
                int start = raw.indexOf("\"detail\"");
                int colon = raw.indexOf(':', start);
                int quoteStart = raw.indexOf('"', colon + 1);
                int quoteEnd = raw.indexOf('"', quoteStart + 1);
                if (quoteStart >= 0 && quoteEnd > quoteStart) {
                    return raw.substring(quoteStart + 1, quoteEnd);
                }
            }
            return raw.lines().limit(3).reduce((a, b) -> a + " " + b).orElse("");
        } catch (IOException e) {
            return null;
        }
    }

    private static int countPendingPlans(Path dir) {
        return countPlanFiles(dir, WeeklyBuilderPaths.PLAN_PREFIX);
    }

    private static int countCompilingPlans(Path dir) {
        return countPlanFiles(dir, WeeklyBuilderPaths.PLAN_PREFIX);
    }

    private static int countDlqPlans(Path dir) {
        return countPlanFiles(dir, WeeklyBuilderPaths.PLAN_PREFIX);
    }

    private static int countPlanFiles(Path dir, String prefix) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith(prefix) && name.endsWith(".json"))
                .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static int countFailedBundles(Path failedRoot) {
        if (!Files.isDirectory(failedRoot)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(failedRoot)) {
            return (int) stream
                .filter(path -> Files.isDirectory(path)
                    || (Files.isRegularFile(path) && path.getFileName().toString().startsWith("reason-")))
                .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static int countSubdirectories(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream.filter(Files::isDirectory).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String resolveControlPlaneUrl() {
        String url = System.getenv("CONTROL_PLANE_URL");
        if (url == null || url.isBlank()) {
            url = "http://localhost:" + System.getenv().getOrDefault("CONTROL_PLANE_PORT", "8080");
        }
        return url;
    }

    private static String capitalize(String step) {
        if (step == null || step.isBlank()) {
            return "Job";
        }
        return step.substring(0, 1).toUpperCase() + step.substring(1);
    }

    record JobRunSnapshot(
        Instant startedAt,
        Instant completedAt,
        String step,
        String status,
        String message,
        String weekId,
        String correlationId
    ) {
        static JobRunSnapshot fromPlan(Instant startedAt, Instant completedAt, WeeklyPlanJob.Result result) {
            return new JobRunSnapshot(
                startedAt,
                completedAt,
                "plan",
                mapPlanStatus(result.status()),
                result.message(),
                result.weekId(),
                result.weekId());
        }

        static JobRunSnapshot fromCompile(Instant startedAt, Instant completedAt, WeeklyCompileWatcher.Result result) {
            return new JobRunSnapshot(
                startedAt,
                completedAt,
                "compile",
                mapCompileStatus(result.status()),
                result.message(),
                result.weekId(),
                result.weekId());
        }

        static JobRunSnapshot fromDeploy(Instant startedAt, Instant completedAt, WeeklyDeployResult result) {
            String status = result.success() ? "completed" : "failed";
            String message = result.success()
                ? (result.noTradeWeek() ? "NoTradeWeek" : "Deployed " + result.runIds().size() + " run(s)")
                : result.error();
            return new JobRunSnapshot(
                startedAt,
                completedAt,
                "deploy",
                status,
                message,
                result.weekId(),
                result.correlationId());
        }

        static JobRunSnapshot failed(Instant startedAt, Instant completedAt, String step, String error) {
            return new JobRunSnapshot(startedAt, completedAt, step, "failed", error, null, null);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("startedAt", startedAt.toString());
            map.put("completedAt", completedAt.toString());
            map.put("step", step);
            map.put("status", status);
            if (message != null) {
                map.put("message", message);
            }
            if (weekId != null) {
                map.put("weekId", weekId);
            }
            if (correlationId != null) {
                map.put("correlationId", correlationId);
            }
            return map;
        }
    }

    public record TriggerResponse(boolean accepted, String message) {
        public static TriggerResponse started(String message) {
            return new TriggerResponse(true, message);
        }

        public static TriggerResponse rejected(String message) {
            return new TriggerResponse(false, message);
        }
    }

    private record OptionalManifest(CompileManifest manifest) {
        static OptionalManifest empty() {
            return new OptionalManifest(null);
        }

        static OptionalManifest of(CompileManifest manifest) {
            return new OptionalManifest(manifest);
        }

        void ifPresent(java.util.function.Consumer<CompileManifest> consumer) {
            if (manifest != null) {
                consumer.accept(manifest);
            }
        }
    }
}
