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
            log.error("Failed to scan compiled/: " + e.getMessage(), e);
            return WeeklyDeployResult.skipped("Failed to scan compiled/: " + e.getMessage());
        }
        if (bundle == null) {
            return WeeklyDeployResult.skipped("No compiled bundle — nothing to deploy");
        }

        CompileManifest manifest;
        try {
            manifest = CompileManifestIO.read(bundle.manifestPath());
        } catch (IOException e) {
            return failBundle(bundle.bundleDir(), null, null, "Failed to read manifest: " + e.getMessage(), e);
        }
        if (manifest == null) {
            return failBundle(bundle.bundleDir(), null, null, "Manifest is null", new IOException("Manifest is null"));
        }

        Path fn = bundle.bundleDir().getFileName();
        String defaultWeekId = fn != null ? fn.toString() : "unknown";
        String weekId = manifest.weekId() != null ? manifest.weekId().trim() : "";
        if (weekId.isBlank()) {
            weekId = defaultWeekId;
        }

        // Security check: prevent Path Traversal
        if (weekId.contains("..") || weekId.contains("/") || weekId.contains("\\")) {
            return failBundle(bundle.bundleDir(), defaultWeekId, null, "Invalid weekId detected: " + weekId, new IllegalArgumentException("Path traversal attempt"));
        }

        String correlationId = manifest.correlationId() != null && !manifest.correlationId().isBlank()
            ? manifest.correlationId()
            : UUID.randomUUID().toString();

        List<CompileManifest.StrategyEntry> brokerStrategies = brokerStrategies(manifest);
        if (isNoTradeWeek(manifest) || brokerStrategies.isEmpty()) {
            log.info("NoTradeWeek or no broker strategies for {} — skipping broker (T8 or empty strategies)", weekId);
            try {
                moveBundle(bundle.bundleDir(), WeeklyBuilderPaths.deployed(repoRoot).resolve(weekId));
                return WeeklyDeployResult.noTradeWeek(weekId, correlationId);
            } catch (IOException e) {
                return failBundle(bundle.bundleDir(), weekId, correlationId, "NoTradeWeek move failed: " + e.getMessage(), e);
            }
        }

        try {
            controlPlane.health();
        } catch (Exception e) {
            return failBundle(bundle.bundleDir(), weekId, correlationId, "Control plane unreachable: " + e.getMessage(), e);
        }

        double lotSize = manifest.resolvedLotSize();
        double capital = manifest.resolvedCapital();
        List<String> startedRunIds = new ArrayList<>();

        try {
            for (CompileManifest.StrategyEntry entry : brokerStrategies) {
                var response = controlPlane.startPaperRun(entry.strategyId(), entry.pair(), lotSize, capital);
                if (response == null || !response.has("runId")) {
                    throw new IOException("Missing 'runId' in control plane response: " + response);
                }
                String runId = response.get("runId").asText();
                startedRunIds.add(runId);
                log.info("Started run {} for {} ({})", runId, entry.strategyId(), entry.pair());
            }
            moveBundle(bundle.bundleDir(), WeeklyBuilderPaths.deployed(repoRoot).resolve(weekId));
            return WeeklyDeployResult.deployed(weekId, correlationId, startedRunIds);
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Weekly deploy failed for {} after {} runs started: {}", weekId, startedRunIds.size(), message, e);
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
                log.error("Failed to move bundle to failed/: {}", io.getMessage(), io);
            }
            return WeeklyDeployResult.failed(weekId, correlationId, message);
        }
    }

    static boolean isNoTradeWeek(CompileManifest manifest) {
        if (manifest.strategies() == null || manifest.strategies().isEmpty()) {
            return true;
        }
        return manifest.strategies().stream()
            .filter(s -> s != null)
            .allMatch(s -> isNoTradeTemplate(s.templateId()));
    }

    static List<CompileManifest.StrategyEntry> brokerStrategies(CompileManifest manifest) {
        if (manifest.strategies() == null) {
            return List.of();
        }
        return manifest.strategies().stream()
            .filter(s -> s != null && !isNoTradeTemplate(s.templateId()))
            .toList();
    }

    static boolean isNoTradeTemplate(String templateId) {
        return templateId != null
            && WeeklyBuilderPaths.NO_TRADE_TEMPLATE.equalsIgnoreCase(templateId.trim());
    }

    private WeeklyDeployResult failBundle(Path bundleDir, String weekId, String correlationId, String error, Throwable t) {
        String resolvedWeek = weekId;
        if (resolvedWeek == null || resolvedWeek.isBlank()) {
            if (bundleDir != null) {
                Path fn = bundleDir.getFileName();
                resolvedWeek = fn != null ? fn.toString() : "unknown";
            } else {
                resolvedWeek = "unknown";
            }
        }
        String resolvedCorrelation = correlationId != null ? correlationId : UUID.randomUUID().toString();
        Map<String, Object> reason = Map.of(
            "weekId", resolvedWeek,
            "correlationId", resolvedCorrelation,
            "error", error);
        log.error("Recording deploy failure for weekId " + resolvedWeek + ": " + error, t);
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
            log.error("Failed to record deploy failure: {}", e.getMessage(), e);
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
        Path parent = targetDir.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path backup = null;
        if (Files.exists(targetDir)) {
            backup = targetDir.resolveSibling(targetDir.getFileName().toString() + ".bak_" + UUID.randomUUID());
            Files.move(targetDir, backup);
        }
        boolean success = false;
        try {
            try {
                Files.move(sourceDir, targetDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                copyRecursively(sourceDir, targetDir);
                deleteRecursively(sourceDir);
            }
            success = true;
        } finally {
            if (backup != null) {
                if (success) {
                    deleteRecursively(backup);
                } else {
                    if (Files.exists(targetDir)) {
                        deleteRecursively(targetDir);
                    }
                    Files.move(backup, targetDir);
                }
            }
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
            // Sort by depth descending so child paths are always deleted before parent paths
            List<Path> paths = stream.sorted((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount())).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
