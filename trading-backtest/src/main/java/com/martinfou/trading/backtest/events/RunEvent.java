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

    public static RunEvent operatorAction(
        String runId,
        String strategyId,
        String symbol,
        RunMode mode,
        String action,
        String actor,
        String reason,
        Instant timestamp
    ) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("action", action);
        payload.put("actor", actor);
        payload.put("reason", reason);
        return new RunEvent(
            SCHEMA_VERSION,
            RunEventType.OPERATOR_ACTION,
            timestamp,
            runId,
            strategyId,
            symbol,
            mode.name(),
            Map.copyOf(payload));
    }

    public static RunEvent reconciliationAlert(
        String runId,
        String strategyId,
        String symbol,
        RunMode mode,
        Map<String, Object> payload,
        Instant timestamp
    ) {
        return new RunEvent(
            SCHEMA_VERSION,
            RunEventType.RECONCILIATION_ALERT,
            timestamp,
            runId,
            strategyId,
            symbol,
            mode.name(),
            Map.copyOf(payload));
    }

    public static RunEvent heartbeat(
        String runId,
        String strategyId,
        String symbol,
        RunMode mode,
        Map<String, Object> payload,
        Instant timestamp
    ) {
        return new RunEvent(
            SCHEMA_VERSION,
            RunEventType.HEARTBEAT,
            timestamp,
            runId,
            strategyId,
            symbol,
            mode.name(),
            Map.copyOf(payload));
    }

    /** Weekly builder pipeline step (Epic 22). */
    public static RunEvent weeklyBuilderEvent(
        String correlationId,
        String step,
        String status,
        String weekId,
        Map<String, Object> extras,
        Instant timestamp
    ) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("correlationId", correlationId);
        payload.put("step", step);
        payload.put("status", status);
        if (weekId != null && !weekId.isBlank()) {
            payload.put("weekId", weekId);
        }
        if (extras != null && !extras.isEmpty()) {
            payload.putAll(extras);
        }
        return new RunEvent(
            SCHEMA_VERSION,
            RunEventType.WEEKLY_BUILDER_EVENT,
            timestamp,
            "weekly-builder",
            "weekly-builder",
            "",
            "BRIDGE",
            Map.copyOf(payload));
    }

    /** SQ hot-folder file processed by the bridge (story 21-7). */
    public static RunEvent sqExportReceived(
        String fileName,
        String disposition,
        String manifestId,
        Instant timestamp
    ) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("fileName", fileName);
        payload.put("disposition", disposition);
        if (manifestId != null && !manifestId.isBlank()) {
            payload.put("manifestId", manifestId);
        }
        return new RunEvent(
            SCHEMA_VERSION,
            RunEventType.SQ_EXPORT_RECEIVED,
            timestamp,
            "sq-bridge",
            "sq-inbox",
            "",
            "BRIDGE",
            Map.copyOf(payload));
    }
}
