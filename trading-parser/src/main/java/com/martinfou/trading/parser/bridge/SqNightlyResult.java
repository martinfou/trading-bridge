package com.martinfou.trading.parser.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Summary of one {@link SqNightlyPipeline} run (story 21-6).
 */
public record SqNightlyResult(
    Map<String, Integer> jobExitCodes,
    int filesExported,
    SqInboxBatchResult inbox
) {

    public SqNightlyResult {
        jobExitCodes = Map.copyOf(new LinkedHashMap<>(jobExitCodes));
        if (inbox == null) {
            inbox = SqInboxBatchResult.empty();
        }
    }

    public int pipelineExitCode() {
        boolean jobFailed = jobExitCodes.values().stream().anyMatch(code -> code != 0);
        return jobFailed || inbox.exitCode() != 0 ? 1 : 0;
    }
}
