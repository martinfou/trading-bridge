package com.martinfou.trading.runtime;

import java.util.Map;

/** Context for pluggable promote validation modules (Epic 19). */
public final class ValidationContext {

    private final String strategyId;
    private final RunRecord backtestRun;
    private final EventStore eventStore;
    private final ValidationAuditBuffer auditBuffer;

    public ValidationContext(String strategyId, RunRecord backtestRun, EventStore eventStore) {
        this(strategyId, backtestRun, eventStore, new ValidationAuditBuffer(true));
    }

    ValidationContext(
        String strategyId,
        RunRecord backtestRun,
        EventStore eventStore,
        ValidationAuditBuffer auditBuffer
    ) {
        this.strategyId = strategyId;
        this.backtestRun = backtestRun;
        this.eventStore = eventStore;
        this.auditBuffer = auditBuffer != null ? auditBuffer : new ValidationAuditBuffer(true);
    }

    public String strategyId() {
        return strategyId;
    }

    public RunRecord backtestRun() {
        return backtestRun;
    }

    public EventStore eventStore() {
        return eventStore;
    }

    ValidationAuditBuffer auditBuffer() {
        return auditBuffer;
    }

    void journalOperatorAction(Map<String, Object> payload) {
        auditBuffer.stageOrJournal(backtestRun, eventStore, payload);
    }
}
