package com.martinfou.trading.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for {@link SqliteEventStore} database location.
 */
public final class EventStoreConfig {

    private final Path dbPath;

    private EventStoreConfig(Path dbPath) {
        this.dbPath = dbPath.toAbsolutePath().normalize();
    }

    public static EventStoreConfig defaults() {
        return withDbPath(RuntimeDataPaths.defaultEventStorePath());
    }

    /** Same as {@link #defaults()} but allows tests to override env via system properties if needed. */
    public static EventStoreConfig fromRuntimeEnvironment() {
        return defaults();
    }

    public static EventStoreConfig withDbPath(Path dbPath) {
        if (dbPath == null) {
            throw new IllegalArgumentException("dbPath must not be null");
        }
        return new EventStoreConfig(dbPath);
    }

    public Path dbPath() {
        return dbPath;
    }

    /** Ensures parent directories exist for the configured database file. */
    public void ensureParentDirectories() throws IOException {
        Path parent = dbPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    static Path findRepoRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path pom = dir.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            try {
                String content = Files.readString(pom);
                if (content.contains("<artifactId>trading-bridge</artifactId>")
                    && content.contains("<packaging>pom</packaging>")) {
                    return dir;
                }
            } catch (IOException ignored) {
                // try parent
            }
        }
        return null;
    }
}
