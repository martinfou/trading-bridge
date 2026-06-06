package com.martinfou.trading.intelligence.paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WeeklyBuilderPathsTest {

    @Test
    void ensureLayout_createsAllHotFolders(@TempDir Path repoRoot) throws Exception {
        WeeklyBuilderPaths.ensureLayout(repoRoot);

        assertTrue(Files.isDirectory(WeeklyBuilderPaths.intelRoot(repoRoot)));
        assertTrue(Files.isDirectory(WeeklyBuilderPaths.pending(repoRoot)));
        assertTrue(Files.isDirectory(WeeklyBuilderPaths.compiling(repoRoot)));
        assertTrue(Files.isDirectory(WeeklyBuilderPaths.compiled(repoRoot)));
        assertTrue(Files.isDirectory(WeeklyBuilderPaths.deployed(repoRoot)));
        assertTrue(Files.isDirectory(WeeklyBuilderPaths.failed(repoRoot)));
        assertTrue(Files.isDirectory(WeeklyBuilderPaths.dlq(repoRoot)));
        assertTrue(Files.isDirectory(WeeklyBuilderPaths.archive(repoRoot)));
    }

    @Test
    void builderRoot_resolvesUnderData(@TempDir Path repoRoot) {
        assertEquals(
            repoRoot.resolve("data/weekly-builder"),
            WeeklyBuilderPaths.builderRoot(repoRoot)
        );
    }
}
