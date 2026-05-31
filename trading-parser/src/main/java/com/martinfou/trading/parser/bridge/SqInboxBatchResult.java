package com.martinfou.trading.parser.bridge;

/**
 * Aggregate counts from one {@link SqInboxProcessor} batch run (story 21-6).
 */
public record SqInboxBatchResult(int processed, int passed, int failed, int dlq) {

    public static SqInboxBatchResult empty() {
        return new SqInboxBatchResult(0, 0, 0, 0);
    }

    public int exitCode() {
        return failed + dlq > 0 ? 1 : 0;
    }
}
