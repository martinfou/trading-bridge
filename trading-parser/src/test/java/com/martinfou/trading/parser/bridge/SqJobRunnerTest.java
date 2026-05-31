package com.martinfou.trading.parser.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqJobRunnerTest {

    @Test
    void runScript_dryRun_usesRegistryArgs(@TempDir Path repo) throws Exception {
        installRegistry(repo);
        SqJobOptions options = new SqJobOptions(repo, repo, true, true, null);

        SqCliRunResult result = new SqJobRunner().runScript(options, "test-job");

        assertTrue(result.dryRun());
        assertEquals(List.of("-symbol", "action=list"), result.command().subList(1, result.command().size()));
    }

    @Test
    void runScript_dryRun_skipsMutexWithoutNoLock(@TempDir Path repo) throws Exception {
        installRegistry(repo);
        try (SqJobMutex lock = SqJobMutex.acquire(SqJobPaths.lockFile(repo))) {
            SqJobOptions options = new SqJobOptions(repo, repo, true, false, null);
            SqCliRunResult result = new SqJobRunner().runScript(options, "test-job");
            assertTrue(result.dryRun());
        }
    }

    @Test
    void runWithMutex_blocksWhenLocked(@TempDir Path repo) throws Exception {
        installRegistry(repo);
        Path sqHome = repo.resolve("sq");
        Files.createDirectories(sqHome);
        Path binary = sqHome.resolve("sqcli");
        Files.writeString(binary, """
            #!/bin/sh
            sleep 2
            exit 0
            """);
        binary.toFile().setExecutable(true);

        SqJobOptions options = new SqJobOptions(
            repo, sqHome, false, false, Duration.ofSeconds(10)
        );

        try (SqJobMutex lock = SqJobMutex.acquire(SqJobPaths.lockFile(repo))) {
            assertThrows(SqJobBusyException.class, () ->
                new SqJobRunner().runScript(options, "test-job")
            );
        }
    }

    @Test
    void loadRegistry_listsFromRepo(@TempDir Path repo) throws Exception {
        installRegistry(repo);
        SqJobScriptRegistry registry = new SqJobRunner().loadRegistry(
            new SqJobOptions(repo, null, true, true, null)
        );
        assertFalse(registry.all().isEmpty());
    }

    private static void installRegistry(Path repo) throws Exception {
        Path registry = SqJobPaths.registryFile(repo);
        Files.createDirectories(registry.getParent());
        Files.copy(
            SqJobRunnerTest.class.getResourceAsStream("/sq-cli/registry-test.json"),
            registry
        );
    }
}
