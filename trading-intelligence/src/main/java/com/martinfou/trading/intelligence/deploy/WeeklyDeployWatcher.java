package com.martinfou.trading.intelligence.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.martinfou.trading.intelligence.compile.CompileManifest;
import com.martinfou.trading.intelligence.compile.CompileManifestIO;
import com.martinfou.trading.intelligence.paths.WeeklyBuilderPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Job 3 — reads {@code compiled/{weekId}/manifest.json}, starts PAPER_OANDA runs atomically,
 * moves bundle to {@code deployed/} or {@code failed/}.
 */
public final class WeeklyDeployWatcher {

    private static final Logger log = LoggerFactory.getLogger(WeeklyDeployWatcher.class);

    private static final ObjectMapper REASON_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path repoRoot;
    private final ControlPlaneHttpClient controlPlane;

    public WeeklyDeployWatcher(Path repoRoot, ControlPlaneHttpClient controlPlane) {
        this.repoRoot = repoRoot;
        this.controlPlane = controlPlane;
    }

    public WeeklyDeployResult run() {
        CompileManifestIO.CompiledBundle bundle;
        try {
            bundle = CompileManifestIO.findNextBundle(repoRoot).orElse(null);
        } catch (IOException e) {
            return WeeklyDeployResult.skipped("Failed to scan compiled/: " + e.getMessage());
        }
        if (bundle == null) {
            return WeeklyDeployResult.skipped("No compiled bundle — nothing to deploy");
        }

        CompileManifest manifest;
        try {
            manifest = CompileManifestIO.read(bundle.manifestPath());
        } catch (IOException e) {
            return failBundle(bundle.bundleDir(), null, null, "Failed to read manifest: " + e.getMessage());
        }

        String weekId = manifest.weekId() != null ? manifest.weekId() : bundle.bundleDir().getFileName().toString();
        String correlationId = manifest.correlationId() != null && !manifest.correlationId().isBlank()
            ? manifest.correlationId()
            : UUID.randomUUID().toString();

        if (isNoTradeWeek(manifest)) {
            log.info("NoTradeWeek for {} — skipping broker (T8 or empty strategies)", weekId);
            try {
                moveBundle(bundle.bundleDir(), WeeklyBuilderPaths.deployed(repoRoot).resolve(weekId));
                return WeeklyDeployResult.noTradeWeek(weekId, correlationId);
            } catch (IOException e) {
                return failBundle(bundle.bundleDir(), weekId, correlationId, "NoTradeWeek move failed: " + e.getMessage());
            }
        }

        List<CompileManifest.StrategyEntry> brokerStrategies = brokerStrategies(manifest);
        if (brokerStrategies.isEmpty()) {
            log.info("No broker strategies in manifest for {} — treating as NoTradeWeek", weekId);
            try {
                moveBundle(bundle.bundleDir(), WeeklyBuilderPaths.deployed(repoRoot).resolve(weekId));
                return WeeklyDeployResult.noTradeWeek(weekId, correlationId);
            } catch (IOException e) {
                return failBundle(bundle.bundleDir(), weekId, correlationId, "NoTradeWeek move failed: " + e.getMessage());
            }
        }

        try {
            controlPlane.health();
        } catch (Exception e) {
            return failBundle(bundle.bundleDir(), weekId, correlationId, "Control plane unreachable: " + e.getMessage());
        }

        double lotSize = manifest.resolvedLotSize();
        double capital = manifest.resolvedCapital();
        List<String> startedRunIds = new ArrayList<>();

        try {
            for (CompileManifest.StrategyEntry entry : brokerStrategies) {
                var response = controlPlane.startPaperRun(entry.strategyId(), entry.pair(), lotSize, capital);
                String runId = response.get("runId").asText();
                startedRunIds.add(runId);
                log.info("Started PAPER_OANDA run {} for {} ({})", runId, entry.strategyId(), entry.pair());
            }
            moveBundle(bundle.bundleDir(), WeeklyBuilderPaths.deployed(repoRoot).resolve(weekId));
            return WeeklyDeployResult.deployed(weekId, correlationId, startedRunIds);
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Weekly deploy failed for {} after {} runs started: {}", weekId, startedRunIds.size(), message);
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("weekId", weekId);
            reason.put("correlationId", correlationId);
            reason.put("error", message);
            if (!startedRunIds.isEmpty()) {
                reason.put("partialRunIds", startedRunIds);
                reason.put("rollbackNote", "Partial runs may require manual stop — deploy aborted before move");
            }
            try {
                Path failedTarget = WeeklyBuilderPaths.failed(repoRoot).resolve(weekId);
                moveBundle(bundle.bundleDir(), failedTarget);
                REASON_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(failedTarget.resolve(WeeklyBuilderPaths.REASON_FILE).toFile(), reason);
            } catch (IOException io) {
                log.error("Failed to move bundle to failed/: {}", io.getMessage());
            }
            return WeeklyDeployResult.failed(weekId, correlationId, message);
        }
    }

    static boolean isNoTradeWeek(CompileManifest manifest) {
        if (manifest.strategies() == null || manifest.strategies().isEmpty()) {
            return true;
        }
        return manifest.strategies().stream().allMatch(s -> isNoTradeTemplate(s.templateId()));
    }

    static List<CompileManifest.StrategyEntry> brokerStrategies(CompileManifest manifest) {
        if (manifest.strategies() == null) {
            return List.of();
        }
        return manifest.strategies().stream()
            .filter(s -> !isNoTradeTemplate(s.templateId()))
            .toList();
    }

    static boolean isNoTradeTemplate(String templateId) {
        return templateId != null
            && WeeklyBuilderPaths.NO_TRADE_TEMPLATE.equalsIgnoreCase(templateId.trim());
    }

    private WeeklyDeployResult failBundle(Path bundleDir, String weekId, String correlationId, String error) {
        String resolvedWeek = weekId != null ? weekId : "unknown";
        String resolvedCorrelation = correlationId != null ? correlationId : UUID.randomUUID().toString();
        Map<String, Object> reason = Map.of(
            "weekId", resolvedWeek,
            "correlationId", resolvedCorrelation,
            "error", error);
        try {
            if (bundleDir != null && Files.exists(bundleDir)) {
                Path failedTarget = WeeklyBuilderPaths.failed(repoRoot).resolve(resolvedWeek);
                moveBundle(bundleDir, failedTarget);
                REASON_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(failedTarget.resolve(WeeklyBuilderPaths.REASON_FILE).toFile(), reason);
            } else {
                writeReason(resolvedWeek, reason);
            }
        } catch (IOException e) {
            log.error("Failed to record deploy failure: {}", e.getMessage());
        }
        return WeeklyDeployResult.failed(resolvedWeek, resolvedCorrelation, error);
    }

    private void writeReason(String weekId, Map<String, Object> reason) throws IOException {
        Path failedDir = WeeklyBuilderPaths.failed(repoRoot).resolve(weekId);
        Files.createDirectories(failedDir);
        Path reasonPath = failedDir.resolve(WeeklyBuilderPaths.REASON_FILE);
        REASON_MAPPER.writerWithDefaultPrettyPrinter().writeValue(reasonPath.toFile(), reason);
    }

    static void moveBundle(Path sourceDir, Path targetDir) throws IOException {
        Files.createDirectories(targetDir.getParent());
        if (Files.exists(targetDir)) {
            deleteRecursively(targetDir);
        }
        try {
            Files.move(sourceDir, targetDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            copyRecursively(sourceDir, targetDir);
            deleteRecursively(sourceDir);
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                Path dest = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.sorted((a, b) -> b.compareTo(a)).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
