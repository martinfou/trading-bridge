package com.martinfou.trading.parser.bridge;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Options for {@link SqNightlyPipeline} (story 21-6).
 *
 * @param repoRoot        repository root
 * @param sqHomeOverride  override {@code SQ_HOME} ; null → env
 * @param dryRun          dry-run sqcli jobs only
 * @param skipMutex       skip job mutex (tests only)
 * @param timeout         sqcli timeout ; null → default in {@link SqJobRunner}
 * @param exportDir       copy {@code *.xml} from here into pending ; null → {@code SQ_EXPORT_DIR} env
 * @param skipExport      do not copy exports even when {@code exportDir} is set
 * @param skipJobs        skip sqcli maintenance jobs
 * @param skipInbox       skip inbox drain
 * @param inboxOptions    options forwarded to {@link SqInboxProcessor}
 */
public record SqNightlyOptions(
    Path repoRoot,
    Path sqHomeOverride,
    boolean dryRun,
    boolean skipMutex,
    Duration timeout,
    Path exportDir,
    boolean skipExport,
    boolean skipJobs,
    boolean skipInbox,
    SqInboxOptions inboxOptions
) {
    public static final String JOB_UPDATE_DATA = "update-data";
    public static final String JOB_LIST_DATABANKS = "list-databanks";

    public SqNightlyOptions {
        if (repoRoot == null) {
            throw new IllegalArgumentException("repoRoot required");
        }
        if (inboxOptions == null) {
            inboxOptions = new SqInboxOptions(repoRoot, null, SqInboxOptions.DEFAULT_CAPITAL,
                SqInboxOptions.DEFAULT_SYNTHETIC_BARS, java.util.List.of());
        }
    }

    SqJobOptions toJobOptions() {
        return new SqJobOptions(repoRoot, sqHomeOverride, dryRun, skipMutex, timeout);
    }
}
