package com.martinfou.trading.parser.bridge;

import java.util.List;

/** Outcome of a {@link SqCliRunner} invocation (story 21-4). */
public record SqCliRunResult(
    List<String> command,
    int exitCode,
    String stdout,
    /** Empty when {@link SqCliRunner} merges stderr into stdout via {@code redirectErrorStream}. */
    String stderr,
    boolean dryRun,
    long elapsedMs
) {
    public boolean success() {
        return dryRun || exitCode == 0;
    }

    static SqCliRunResult dryRun(List<String> command) {
        return new SqCliRunResult(command, 0, "", "", true, 0);
    }
}
