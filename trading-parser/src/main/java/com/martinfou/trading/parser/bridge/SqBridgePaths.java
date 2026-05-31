package com.martinfou.trading.parser.bridge;

import java.nio.file.Files;
import java.nio.file.Path;

/** Shared path helpers for the StrategyQuant bridge (story 21-6). */
public final class SqBridgePaths {

    public static final String SQ_EXPORT_DIR_ENV = "SQ_EXPORT_DIR";

    private SqBridgePaths() {}

    /**
     * Walks up from {@code user.dir} looking for {@code data/sq-inbox/} or {@code data/sq-cli/}.
     */
    public static Path resolveRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 8; i++) {
            if (Files.isDirectory(dir.resolve(SqInboxPaths.ROOT_DIR))
                || Files.isDirectory(dir.resolve(SqJobPaths.ROOT_DIR))) {
                return dir;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }
}
