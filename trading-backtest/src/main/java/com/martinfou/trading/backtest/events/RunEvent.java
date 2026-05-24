package com.martinfou.trading.backtest.events;

import com.martinfou.trading.backtest.RunMode;

import java.time.Instant;
import java.util.Map;

/**
 * Versioned JSONL event emitted during a strategy run (schema v1).
 */
public record RunEvent(
    int schemaVersion,
    RunEventType type,
    Instant timestamp,
    String runId,
    String strategyId,
    String symbol,
    String mode,
    Map<String, Object> payload
) {

    public static final int SCHEMA_VERSION = 1;

    public static RunEvent started(
        String runId,
        String strategyId,
        String symbol,
        RunMode mode,
        Map<String, Object> payload
    ) {
        return new RunEvent(
            SCHEMA_VERSION,
            RunEventType.RUN_STARTED,
            Instant.now(),
            runId,
            strategyId,
            symbol,
            mode.name(),
            Map.copyOf(payload));
    }

    public static RunEvent ended(
        String runId,
        String strategyId,
        String symbol,
        RunMode mode,
        Map<String, Object> payload
    ) {
        return new RunEvent(
            SCHEMA_VERSION,
            RunEventType.RUN_ENDED,
            Instant.now(),
            runId,
            strategyId,
            symbol,
            mode.name(),
            Map.copyOf(payload));
    }

    public static RunEvent error(
        String runId,
        String strategyId,
        String symbol,
        RunMode mode,
        String message
    ) {
        return new RunEvent(
            SCHEMA_VERSION,
            RunEventType.ERROR,
            Instant.now(),
            runId,
            strategyId,
            symbol,
            mode.name(),
            Map.of("message", message));
    }
}
