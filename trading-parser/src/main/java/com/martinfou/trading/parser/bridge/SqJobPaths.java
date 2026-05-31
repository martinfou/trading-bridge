package com.martinfou.trading.parser.bridge;

import java.nio.file.Path;

/** Paths for SQ CLI job scripts and mutex lock (story 21-5). */
public final class SqJobPaths {

    public static final String ROOT_DIR = "data/sq-cli";
    public static final String SCRIPTS_DIR = "scripts";
    public static final String REGISTRY_FILE = "registry.json";
    public static final String LOCK_FILE = ".sq-job.lock";

    private SqJobPaths() {}

    public static Path root(Path repoRoot) {
        return repoRoot.resolve(ROOT_DIR);
    }

    public static Path scriptsDir(Path repoRoot) {
        return root(repoRoot).resolve(SCRIPTS_DIR);
    }

    public static Path registryFile(Path repoRoot) {
        return scriptsDir(repoRoot).resolve(REGISTRY_FILE);
    }

    public static Path lockFile(Path repoRoot) {
        return root(repoRoot).resolve(LOCK_FILE);
    }
}
