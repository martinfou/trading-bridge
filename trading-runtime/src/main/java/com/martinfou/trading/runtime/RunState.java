package com.martinfou.trading.runtime;

import java.time.Instant;
import java.util.Map;

public record RunState(
    RunRecord.Status status,
    Instant startedAt,
    Instant completedAt,
    String errorMessage,
    Map<String, Object> endedPayload,
    Instant lastEventAt,
    int restartCount,
    Instant lastRestartAt
) {
    public RunState(RunRecord.Status status) {
        this(status, null, null, null, null, null, 0, null);
    }

    public RunState withStatus(RunRecord.Status newStatus) {
        return new RunState(newStatus, startedAt, completedAt, errorMessage, endedPayload, lastEventAt, restartCount, lastRestartAt);
    }

    public RunState withStatusAndStartedAt(RunRecord.Status newStatus, Instant newStartedAt) {
        return new RunState(newStatus, newStartedAt, completedAt, errorMessage, endedPayload, lastEventAt, restartCount, lastRestartAt);
    }

    public RunState withCompletedAtAndPayload(RunRecord.Status newStatus, Instant newCompletedAt, Map<String, Object> newPayload) {
        return new RunState(newStatus, startedAt, newCompletedAt, errorMessage, newPayload, lastEventAt, restartCount, lastRestartAt);
    }

    public RunState withCompletedAtAndError(RunRecord.Status newStatus, Instant newCompletedAt, String newErrorMessage) {
        return new RunState(newStatus, startedAt, newCompletedAt, newErrorMessage, endedPayload, lastEventAt, restartCount, lastRestartAt);
    }

    public RunState withEventAt(Instant newEventAt) {
        return new RunState(status, startedAt, completedAt, errorMessage, endedPayload, newEventAt, restartCount, lastRestartAt);
    }

    public RunState withRestartCount(int newRestartCount, Instant newLastRestartAt) {
        return new RunState(status, startedAt, completedAt, errorMessage, endedPayload, lastEventAt, newRestartCount, newLastRestartAt);
    }
}
