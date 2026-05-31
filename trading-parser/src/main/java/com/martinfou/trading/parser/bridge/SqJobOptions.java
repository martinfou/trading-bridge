package com.martinfou.trading.parser.bridge;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Options for {@link SqJobRunner} (story 21-5).
 *
 * @param repoRoot   repository root containing {@code data/sq-cli/}
 * @param sqHomeOverride override {@code SQ_HOME} ; null → env
 * @param dryRun     delegate dry-run to {@link SqCliRunner}
 * @param skipMutex  skip file lock (tests only)
 * @param timeout    sqcli timeout ; null → no timeout when dry-run
 */
public record SqJobOptions(
    Path repoRoot,
    Path sqHomeOverride,
    boolean dryRun,
    boolean skipMutex,
    Duration timeout
) {
    public SqJobOptions {
        if (repoRoot == null) {
            throw new IllegalArgumentException("repoRoot required");
        }
    }

    SqCliOptions toCliOptions() {
        return new SqCliOptions(sqHomeOverride, dryRun, dryRun ? null : timeout);
    }
}
