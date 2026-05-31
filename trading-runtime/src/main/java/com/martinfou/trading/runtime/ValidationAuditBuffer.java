package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stages validation audit events until promote succeeds (code review P1). */
final class ValidationAuditBuffer {

    private final boolean journalImmediately;
    private final List<Map<String, Object>> staged = new ArrayList<>();

    ValidationAuditBuffer(boolean journalImmediately) {
        this.journalImmediately = journalImmediately;
    }

    boolean journalImmediately() {
        return journalImmediately;
    }

    void stageOrJournal(RunRecord run, EventStore eventStore, Map<String, Object> payload) {
        if (eventStore == null) {
            return;
        }
        if (journalImmediately) {
            append(run, eventStore, payload);
        } else {
            staged.add(Map.copyOf(payload));
        }
    }

    void flush(RunRecord run, EventStore eventStore) {
        if (eventStore == null) {
            staged.clear();
            return;
        }
        for (Map<String, Object> payload : staged) {
            append(run, eventStore, payload);
        }
        staged.clear();
    }

    private static void append(RunRecord run, EventStore eventStore, Map<String, Object> payload) {
        RunEvent event = new RunEvent(
            RunEvent.SCHEMA_VERSION,
            RunEventType.OPERATOR_ACTION,
            Instant.now(),
            run.runId(),
            run.strategyId(),
            run.symbol(),
            run.mode().name(),
            Map.copyOf(payload));
        eventStore.append(run.runId(), event);
    }
}
