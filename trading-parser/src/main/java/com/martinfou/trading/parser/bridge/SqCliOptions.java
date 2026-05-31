package com.martinfou.trading.parser.bridge;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Options for {@link SqCliRunner} (story 21-4).
 *
 * @param sqHomeOverride explicit SQ install dir ; null → {@code SQ_HOME} env
 * @param dryRun         log command only, do not spawn process
 * @param timeout        max wait ; null → no timeout
 */
public record SqCliOptions(
    Path sqHomeOverride,
    boolean dryRun,
    Duration timeout
) {
    public static SqCliOptions defaults() {
        return new SqCliOptions(null, false, Duration.ofMinutes(30));
    }

    public static SqCliOptions forDryRun() {
        return new SqCliOptions(null, true, null);
    }
}
