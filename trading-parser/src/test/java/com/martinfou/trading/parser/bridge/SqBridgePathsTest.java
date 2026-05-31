package com.martinfou.trading.parser.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqBridgePathsTest {

    @Test
    void resolveRepoRoot_findsInboxLayout(@TempDir Path repo) throws Exception {
        Files.createDirectories(SqInboxPaths.pending(repo));
        Path nested = repo.resolve("trading-parser");
        Files.createDirectories(nested);

        Path previous = Path.of(System.getProperty("user.dir"));
        try {
            System.setProperty("user.dir", nested.toString());
            assertEquals(repo, SqBridgePaths.resolveRepoRoot());
        } finally {
            System.setProperty("user.dir", previous.toString());
        }
    }

    @Test
    void resolveRepoRoot_findsSqCliLayout(@TempDir Path repo) throws Exception {
        Files.createDirectories(SqJobPaths.scriptsDir(repo));
        Path nested = repo.resolve("module");
        Files.createDirectories(nested);

        Path previous = Path.of(System.getProperty("user.dir"));
        try {
            System.setProperty("user.dir", nested.toString());
            assertEquals(repo, SqBridgePaths.resolveRepoRoot());
        } finally {
            System.setProperty("user.dir", previous.toString());
        }
    }
}
