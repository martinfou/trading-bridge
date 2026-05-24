package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;

final class EventStoreValidation {

    private EventStoreValidation() {}

    static void requireRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be null or blank");
        }
    }

    static void requireEvent(RunEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
    }

    static void requireLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
