package com.martinfou.trading.parser.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Resolves StrategyQuant installation paths for CLI (story 21-4). */
public final class SqCliPaths {

    public static final String SQ_HOME_ENV = "SQ_HOME";
    private static final List<String> CLI_BINARY_NAMES = List.of("sqcli", "sqcli.exe");

    private SqCliPaths() {}

    public static Path resolveSqHome() throws SqCliNotFoundException {
        String env = System.getenv(SQ_HOME_ENV);
        if (env != null && !env.isBlank()) {
            Path home = Path.of(env.trim()).toAbsolutePath().normalize();
            if (Files.isDirectory(home)) {
                return home;
            }
            throw new SqCliNotFoundException("SQ_HOME is not a directory: " + home);
        }
        throw new SqCliNotFoundException(
            "SQ_HOME environment variable is not set. See docs/contributing.md § StrategyQuant X sur Mac."
        );
    }

    public static Path resolveSqHome(Path override) throws SqCliNotFoundException {
        if (override != null) {
            Path home = override.toAbsolutePath().normalize();
            if (!Files.isDirectory(home)) {
                throw new SqCliNotFoundException("SQ_HOME override is not a directory: " + home);
            }
            return home;
        }
        return resolveSqHome();
    }

    public static Path sqCliBinary(Path sqHome) throws SqCliNotFoundException {
        for (String name : CLI_BINARY_NAMES) {
            Path candidate = sqHome.resolve(name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        throw new SqCliNotFoundException(
            "sqcli binary not found under " + sqHome + " (expected sqcli or sqcli.exe, executable)"
        );
    }

    /** Resolves CLI path for dry-run display (executable not required). */
    public static Path cliBinaryPath(Path sqHome) {
        for (String name : CLI_BINARY_NAMES) {
            Path candidate = sqHome.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return sqHome.resolve(CLI_BINARY_NAMES.getFirst());
    }
}
