package com.martinfou.trading.intelligence.compile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.martinfou.trading.intelligence.deploy.WeeklyPlanMarkdownWriter;
import com.martinfou.trading.intelligence.paths.WeeklyBuilderPaths;
import com.martinfou.trading.intelligence.plan.ReviewerStatus;
import com.martinfou.trading.intelligence.plan.WeeklyPlan;
import com.martinfou.trading.intelligence.plan.WeeklyPlanIO;
import com.martinfou.trading.intelligence.time.WeekBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Job 2: pending/ → codegen + compile → compiled/ (Epic 22.4). */
public final class WeeklyCompileWatcher {

    private static final Logger log = LoggerFactory.getLogger(WeeklyCompileWatcher.class);

    private final Path repoRoot;
    private final WeeklyStrategyCodeGenerator codeGenerator;
    private final CompileGate compileGate;
    private final Clock clock;
    private final ObjectMapper mapper;

    public WeeklyCompileWatcher(Path repoRoot) throws IOException {
        this(repoRoot, WeeklyStrategyCodeGenerator.loadDefault(), new CompileGate(repoRoot), Clock.systemUTC());
    }

    WeeklyCompileWatcher(
        Path repoRoot,
        WeeklyStrategyCodeGenerator codeGenerator,
        CompileGate compileGate,
        Clock clock
    ) {
        this.repoRoot = repoRoot;
        this.codeGenerator = codeGenerator;
        this.compileGate = compileGate;
        this.clock = clock;
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public Result runOnce() throws Exception {
        WeeklyBuilderPaths.ensureLayout(repoRoot);

        Optional<Path> stuckPlan = findStuckCompilingPlan();
        if (stuckPlan.isPresent()) {
            log.info("Resuming compile for stuck plan {}", stuckPlan.get().getFileName());
            return compileStuckPlan(stuckPlan.get());
        }

        Optional<Path> pendingPlan = findNextPendingPlan();
        if (pendingPlan.isEmpty()) {
            log.debug("No approved pending plans");
            return Result.idle();
        }

        Path planFile = pendingPlan.get();
        WeeklyPlan plan;
        try {
            plan = WeeklyPlanIO.read(planFile);
        } catch (Exception ex) {
            log.error("Failed to read pending plan {}: {}", planFile, ex.getMessage());
            moveToDlq(planFile, ex);
            return Result.compileFailed("unknown", "Malformed plan JSON: " + ex.getMessage());
        }
        if (plan.reviewerStatus() != ReviewerStatus.APPROVED) {
            log.warn("Skipping non-approved plan {}", planFile);
            return Result.skipped(plan.weekId(), "not approved");
        }

        Path compilingPlan = moveToCompiling(planFile);
        try {
            return compilePlan(plan, compilingPlan);
        } catch (Exception ex) {
            moveToFailed(plan, compilingPlan, ex.getMessage(), null);
            throw ex;
        }
    }

    private Result compileStuckPlan(Path compilingPlan) throws Exception {
        WeeklyPlan plan;
        try {
            plan = WeeklyPlanIO.read(compilingPlan);
        } catch (Exception ex) {
            log.error("Failed to read stuck plan {}: {}", compilingPlan, ex.getMessage());
            moveToDlq(compilingPlan, ex);
            return Result.compileFailed("unknown", "Malformed stuck plan JSON: " + ex.getMessage());
        }
        if (plan.reviewerStatus() != ReviewerStatus.APPROVED) {
            log.warn("Removing non-approved stuck plan {}", compilingPlan);
            Files.deleteIfExists(compilingPlan);
            return Result.skipped(plan.weekId(), "removed non-approved stuck plan");
        }
        try {
            return compilePlan(plan, compilingPlan);
        } catch (Exception ex) {
            moveToFailed(plan, compilingPlan, ex.getMessage(), null);
            throw ex;
        }
    }

    private Result compilePlan(WeeklyPlan plan, Path compilingPlan) throws Exception {
        Path generatedDir = repoRoot.resolve(
            "trading-strategies/src/main/java/com/martinfou/trading/strategies/llmweekly/generated");
        Path registrarFile = repoRoot.resolve(
            "trading-strategies/src/main/java/com/martinfou/trading/strategies/llmweekly/LlmWeeklyStrategyCatalogRegistrar.java");

        WeeklyStrategyCodeGenerator.GenerationResult generation =
            codeGenerator.generate(plan, generatedDir, registrarFile);

        CompileGate.Result compile = compileGate.compile();
        if (!compile.success()) {
            moveToFailed(plan, compilingPlan, compile.errorSnippet(), compile.output());
            return Result.compileFailed(plan.weekId(), compile.errorSnippet());
        }

        Instant compiledAt = clock.instant();
        List<CompileManifest.StrategyEntry> entries = generation.strategies().stream()
            .map(s -> new CompileManifest.StrategyEntry(s.strategyId(), s.className(), s.templateId(), s.pair()))
            .toList();

        LocalDate weekStart = WeekBounds.parseWeekStart(plan.weekId());
        Instant validFrom = WeekBounds.validFrom(weekStart);
        Instant validUntil = WeekBounds.validUntil(weekStart);
        var risk = plan.riskEnvelopeSnapshot();
        CompileManifest.RiskSnapshot riskSnapshot = new CompileManifest.RiskSnapshot(
            risk != null ? risk.maxLotSize() : 0.01,
            100_000.0);

        CompileManifest manifest = new CompileManifest(
            plan.weekId(),
            plan.weekId() + "-" + compiledAt.toEpochMilli(),
            compiledAt,
            validFrom,
            validUntil,
            entries,
            compilingPlan.getFileName().toString(),
            CompileManifest.ORIGIN_AI,
            riskSnapshot
        );

        Path compiledDir = WeeklyBuilderPaths.compiled(repoRoot).resolve(plan.weekId());
        Files.createDirectories(compiledDir);
        mapper.writerWithDefaultPrettyPrinter()
            .writeValue(compiledDir.resolve("manifest.json").toFile(), manifest);

        WeeklyPlanMarkdownWriter.write(repoRoot.resolve("deploy"), plan, manifest);

        Path compiledPlan = compiledDir.resolve(compilingPlan.getFileName());
        Files.move(compilingPlan, compiledPlan, StandardCopyOption.REPLACE_EXISTING);

        log.info("Compiled weekly plan {} with {} strategies", plan.weekId(), entries.size());
        return Result.success(plan.weekId(), compiledDir, manifest);
    }

    private Optional<Path> findStuckCompilingPlan() throws IOException {
        Path compiling = WeeklyBuilderPaths.compiling(repoRoot);
        if (!Files.isDirectory(compiling)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(compiling)) {
            return stream
                .filter(p -> p.getFileName().toString().startsWith(WeeklyBuilderPaths.PLAN_PREFIX))
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .findFirst();
        }
    }

    private Optional<Path> findNextPendingPlan() throws IOException {
        Path pending = WeeklyBuilderPaths.pending(repoRoot);
        if (!Files.isDirectory(pending)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(pending)) {
            return stream
                .filter(p -> p.getFileName().toString().startsWith(WeeklyBuilderPaths.PLAN_PREFIX))
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .filter(p -> !p.getFileName().toString().endsWith(".manifest.json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .findFirst();
        }
    }

    private Path moveToCompiling(Path planFile) throws IOException {
        Path target = WeeklyBuilderPaths.compiling(repoRoot).resolve(planFile.getFileName());
        return Files.move(planFile, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private void moveToDlq(Path planFile, Exception ex) {
        try {
            Path dlqDir = WeeklyBuilderPaths.dlq(repoRoot);
            Files.createDirectories(dlqDir);
            Path target = dlqDir.resolve(planFile.getFileName());
            Files.move(planFile, target, StandardCopyOption.REPLACE_EXISTING);
            Path reasonFile = dlqDir.resolve(planFile.getFileName().toString() + ".reason.json");
            var reason = new java.util.LinkedHashMap<String, Object>();
            reason.put("file", planFile.getFileName().toString());
            reason.put("error", ex.getMessage());
            reason.put("at", clock.instant().toString());
            mapper.writerWithDefaultPrettyPrinter().writeValue(reasonFile.toFile(), reason);
            log.warn("Moved malformed plan to DLQ: {}", target);
        } catch (IOException ioEx) {
            log.error("Failed to move malformed plan to DLQ", ioEx);
        }
    }

    private void moveToFailed(WeeklyPlan plan, Path compilingPlan, String detail, String mavenOutput) throws IOException {
        Path failedDir = WeeklyBuilderPaths.failed(repoRoot).resolve(plan.weekId());
        Files.createDirectories(failedDir);
        if (Files.exists(compilingPlan)) {
            Files.move(compilingPlan, failedDir.resolve(compilingPlan.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        var reason = new LinkedHashMap<String, Object>();
        reason.put("weekId", plan.weekId());
        reason.put("detail", detail);
        reason.put("at", clock.instant().toString());
        if (mavenOutput != null) {
            reason.put("mavenOutput", mavenOutput);
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(failedDir.resolve("reason.json").toFile(), reason);
        log.error("Weekly compile failed for {}: {}", plan.weekId(), detail);
    }

    public record Result(Status status, String weekId, Path outputDir, CompileManifest manifest, String message) {
        public enum Status {
            SUCCESS, IDLE, BUSY, SKIPPED, COMPILE_FAILED
        }

        static Result success(String weekId, Path outputDir, CompileManifest manifest) {
            return new Result(Status.SUCCESS, weekId, outputDir, manifest, "compiled");
        }

        static Result idle() {
            return new Result(Status.IDLE, null, null, null, "no pending plans");
        }

        static Result busy() {
            return new Result(Status.BUSY, null, null, null, "compiling folder busy");
        }

        static Result skipped(String weekId, String message) {
            return new Result(Status.SKIPPED, weekId, null, null, message);
        }

        static Result compileFailed(String weekId, String message) {
            return new Result(Status.COMPILE_FAILED, weekId, null, null, message);
        }
    }
}
