package com.martinfou.trading.parser.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqCliRunnerTest {

    @Test
    void dryRun_doesNotExecuteBinary(@TempDir Path sqHome) throws Exception {
        SqCliOptions options = new SqCliOptions(sqHome, true, null);
        SqCliRunResult result = new SqCliRunner().run(options, List.of("-symbol", "action=list"));

        assertTrue(result.dryRun());
        assertTrue(result.success());
        assertEquals(sqHome.resolve("sqcli").toString(), result.command().getFirst());
        assertEquals(List.of("-symbol", "action=list"), result.command().subList(1, result.command().size()));
        assertFalse(Files.exists(sqHome.resolve("sqcli")));
    }

    @Test
    void run_invokesFakeSqCliBinary(@TempDir Path sqHome) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            !System.getProperty("os.name").toLowerCase().contains("win"),
            "Skip Unix shell script execution on Windows"
        );

        Path binary = sqHome.resolve("sqcli");
        Files.writeString(binary, """
            #!/bin/sh
            echo "args:$*"
            exit 0
            """);
        binary.toFile().setExecutable(true);

        SqCliOptions options = new SqCliOptions(sqHome, false, Duration.ofSeconds(10));
        SqCliRunResult result = new SqCliRunner().run(options, List.of("-symbol", "action=list"));

        assertFalse(result.dryRun());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("args:-symbol action=list"));
    }

    @Test
    void resolveSqHome_invalidOverride_throws() {
        assertThrows(SqCliNotFoundException.class, () -> SqCliPaths.resolveSqHome(Path.of("/nonexistent/path/12345")));
    }

    @Test
    void sqCliBinary_missingExecutable_throws(@TempDir Path sqHome) {
        assertThrows(SqCliNotFoundException.class, () -> SqCliPaths.sqCliBinary(sqHome));
    }

    @Test
    void dryRun_prefersExistingBinaryName(@TempDir Path sqHome) throws Exception {
        Files.writeString(sqHome.resolve("sqcli.exe"), "fake");
        SqCliOptions options = new SqCliOptions(sqHome, true, null);
        SqCliRunResult result = new SqCliRunner().run(options, List.of("-symbol", "action=list"));
        assertEquals(sqHome.resolve("sqcli.exe").toString(), result.command().getFirst());
    }

    @Test
    void run_nonZeroExitCode(@TempDir Path sqHome) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            !System.getProperty("os.name").toLowerCase().contains("win"),
            "Skip Unix shell script execution on Windows"
        );

        Path binary = sqHome.resolve("sqcli");
        Files.writeString(binary, """
            #!/bin/sh
            exit 42
            """);
        binary.toFile().setExecutable(true);

        SqCliRunResult result = new SqCliRunner().run(
            new SqCliOptions(sqHome, false, Duration.ofSeconds(10)),
            List.of("-symbol", "action=list")
        );

        assertEquals(42, result.exitCode());
        assertFalse(result.success());
    }

    @Test
    void cliBinaryPath_fallsBackToSqcli(@TempDir Path sqHome) {
        assertEquals(sqHome.resolve("sqcli"), SqCliPaths.cliBinaryPath(sqHome));
    }

    @Test
    void buildCommand_preservesArgs() {
        Path path = Path.of("/sq/sqcli");
        List<String> command = SqCliRunner.buildCommand(path, List.of("-data", "action=update"));
        assertEquals(List.of(path.toString(), "-data", "action=update"), command);
    }
}
