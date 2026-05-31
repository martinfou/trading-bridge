package com.martinfou.trading.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves durable runtime data locations (ADR-13-11).
 *
 * <p>Precedence:
 * <ol>
 *   <li>{@code TRADING_BRIDGE_EVENT_STORE} — full path to the SQLite events database file</li>
 *   <li>{@code TRADING_BRIDGE_DATA_DIR} — directory; events stored at {@code events.db} inside it</li>
 *   <li>Repo {@code data/runtime/events.db} when the Maven root is discoverable from {@code user.dir}</li>
 *   <li>{@code ~/.trading-bridge/events.db}</li>
 * </ol>
 */
public final class RuntimeDataPaths {

    static final String ENV_EVENT_STORE = "TRADING_BRIDGE_EVENT_STORE";
    static final String ENV_DATA_DIR = "TRADING_BRIDGE_DATA_DIR";

    private static final Path HOME_FALLBACK = Path.of(
        System.getProperty("user.home"), ".trading-bridge", "events.db");

    private RuntimeDataPaths() {}

    public static Path defaultEventStorePath() {
        String explicitFile = System.getenv(ENV_EVENT_STORE);
        if (explicitFile != null && !explicitFile.isBlank()) {
            return Path.of(explicitFile).toAbsolutePath().normalize();
        }

        String dataDir = System.getenv(ENV_DATA_DIR);
        if (dataDir != null && !dataDir.isBlank()) {
            return Path.of(dataDir).toAbsolutePath().normalize().resolve("events.db");
        }

        Path repoRoot = EventStoreConfig.findRepoRoot();
        if (repoRoot != null) {
            return repoRoot.resolve("data/runtime/events.db").toAbsolutePath().normalize();
        }

        return HOME_FALLBACK.toAbsolutePath().normalize();
    }

    public static Path defaultDataDirectory() {
        Path eventStore = defaultEventStorePath();
        Path parent = eventStore.getParent();
        return parent != null ? parent : eventStore.toAbsolutePath().normalize();
    }

    /** Ensures {@code data/runtime/} (or configured data dir) exists. */
    public static void ensureDataDirectories() throws java.io.IOException {
        Path dataDir = defaultDataDirectory();
        Files.createDirectories(dataDir);
    }
}
