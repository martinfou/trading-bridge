package com.martinfou.trading.intelligence.paths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Hot-folder layout for Epic 22 weekly strategy builder (mirrors {@code SqInboxPaths}). */
public final class WeeklyBuilderPaths {

    public static final String BUILDER_ROOT = "data/weekly-builder";
    public static final String INTEL_ROOT = "data/weekly-intel";

    public static final String PENDING = "pending";
    public static final String COMPILING = "compiling";
    public static final String COMPILED = "compiled";
    public static final String DEPLOYED = "deployed";
    public static final String FAILED = "failed";
    public static final String DLQ = "dlq";
    public static final String ARCHIVE = "archive";

    public static final String PLAN_PREFIX = "weekly-plan-";
    public static final String BRIEF_PREFIX = "brief-";
    public static final String MANIFEST_FILE = "manifest.json";
    public static final String REASON_FILE = "reason.json";
    public static final String NO_TRADE_TEMPLATE = "T8";

    private WeeklyBuilderPaths() {}

    public static Path intelRoot(Path repoRoot) {
        return repoRoot.resolve(INTEL_ROOT);
    }

    public static Path builderRoot(Path repoRoot) {
        return repoRoot.resolve(BUILDER_ROOT);
    }

    public static Path pending(Path repoRoot) {
        return builderRoot(repoRoot).resolve(PENDING);
    }

    public static Path compiling(Path repoRoot) {
        return builderRoot(repoRoot).resolve(COMPILING);
    }

    public static Path compiled(Path repoRoot) {
        return builderRoot(repoRoot).resolve(COMPILED);
    }

    public static Path deployed(Path repoRoot) {
        return builderRoot(repoRoot).resolve(DEPLOYED);
    }

    public static Path failed(Path repoRoot) {
        return builderRoot(repoRoot).resolve(FAILED);
    }

    public static Path dlq(Path repoRoot) {
        return builderRoot(repoRoot).resolve(DLQ);
    }

    public static Path archive(Path repoRoot) {
        return builderRoot(repoRoot).resolve(ARCHIVE);
    }

    /** Creates intel + builder subdirectories if missing. */
    public static void ensureLayout(Path repoRoot) throws IOException {
        Files.createDirectories(intelRoot(repoRoot));
        Files.createDirectories(pending(repoRoot));
        Files.createDirectories(compiling(repoRoot));
        Files.createDirectories(compiled(repoRoot));
        Files.createDirectories(deployed(repoRoot));
        Files.createDirectories(failed(repoRoot));
        Files.createDirectories(dlq(repoRoot));
        Files.createDirectories(archive(repoRoot));
    }
}
