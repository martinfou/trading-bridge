package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDataPathsTest {

    @Test
    void defaultEventStorePath_usesRepoDataRuntimeWhenAvailable() {
        Path repoRoot = EventStoreConfig.findRepoRoot();
        if (repoRoot == null) {
            return;
        }
        assertEquals(
            repoRoot.resolve("data/runtime/events.db").toAbsolutePath().normalize(),
            RuntimeDataPaths.defaultEventStorePath());
    }

    @Test
    void defaultDataDirectory_isParentOfEventStore() {
        Path eventStore = RuntimeDataPaths.defaultEventStorePath();
        Path dataDir = RuntimeDataPaths.defaultDataDirectory();
        assertEquals(eventStore.getParent(), dataDir);
    }

    @Test
    void ensureDataDirectories_createsConfiguredParent(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("nested/runtime/events.db");
        EventStoreConfig config = EventStoreConfig.withDbPath(db);
        config.ensureParentDirectories();
        assertTrue(Files.isDirectory(db.getParent()));
    }
}
