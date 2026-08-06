package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory run metadata tracked by {@link RunManager}. */
public final class RunRecord {

    public enum Status {
        CREATED, RUNNING, PAUSED, COMPLETED, FAILED, ARCHIVED, RETIRED
    }

    private final String runId;
    private final String strategyId;
    private final String symbol;
    private final RunMode mode;
    private final Map<String, Object> configSnapshot;
    private final String configHash;
    private final AtomicReference<RunState> state;

    RunRecord(String runId, String strategyId, String symbol, RunMode mode, RunConfigSnapshot configSnapshot) {
        this.runId = runId;
        this.strategyId = strategyId;
        this.symbol = symbol;
        this.mode = mode;
        this.configSnapshot = configSnapshot.toMap();
        this.configHash = configSnapshot.hash();
        this.state = new AtomicReference<>(new RunState(
            Status.CREATED, Instant.now(), null, null, null, null, 0, null
        ));
    }

    RunRecord(
        String runId,
        String strategyId,
        String symbol,
        RunMode mode,
        Instant startedAt,
        Map<String, Object> configSnapshot,
        String configHash,
        Status status,
        Instant completedAt,
        String errorMessage,
        Map<String, Object> endedPayload,
        Instant lastEventAt,
        int restartCount,
        Instant lastRestartAt
    ) {
        this.runId = runId;
        this.strategyId = strategyId;
        this.symbol = symbol;
        this.mode = mode;
        this.configSnapshot = configSnapshot;
        this.configHash = configHash;
        this.state = new AtomicReference<>(new RunState(
            status, startedAt, completedAt, errorMessage, endedPayload, lastEventAt, restartCount, lastRestartAt
        ));
    }

    public String runId() {
        return runId;
    }

    public String strategyId() {
        return strategyId;
    }

    public String symbol() {
        return symbol;
    }

    public RunMode mode() {
        return mode;
    }

    public Instant startedAt() {
        return state.get().startedAt();
    }

    public Map<String, Object> configSnapshot() {
        return configSnapshot;
    }

    public String configHash() {
        return configHash;
    }

    public Status status() {
        return state.get().status();
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(state.get().completedAt());
    }

    public Optional<String> errorMessage() {
        return Optional.ofNullable(state.get().errorMessage());
    }

    public Optional<Map<String, Object>> endedPayload() {
        return Optional.ofNullable(state.get().endedPayload());
    }

    public Optional<Instant> lastEventAt() {
        return Optional.ofNullable(state.get().lastEventAt());
    }

    void noteEventAt(Instant timestamp) {
        state.updateAndGet(s -> s.withEventAt(timestamp));
    }

    void markCreated() {
        state.updateAndGet(s -> s.withStatus(Status.CREATED));
    }

    void markRunning() {
        state.updateAndGet(s -> s.withStatusAndStartedAt(Status.RUNNING, Instant.now()));
    }

    void markPaused() {
        state.updateAndGet(s -> s.withStatus(Status.PAUSED));
    }

    void markCompleted(Map<String, Object> payload) {
        state.updateAndGet(s -> s.withCompletedAtAndPayload(Status.COMPLETED, Instant.now(), payload));
    }

    void markFailed(String message) {
        state.updateAndGet(s -> s.withCompletedAtAndError(Status.FAILED, Instant.now(), message));
    }

    void markArchived() {
        state.updateAndGet(s -> {
            if (s.completedAt() == null) {
                return s.withCompletedAtAndError(Status.ARCHIVED, Instant.now(), s.errorMessage());
            }
            return s.withStatus(Status.ARCHIVED);
        });
    }

    /**
     * Marks the run as RETIRED — gracefully decommissioned by an operator,
     * as opposed to ARCHIVED (evicted by age) or FAILED (error-driven).
     */
    void markRetired(String reason) {
        state.updateAndGet(s -> {
            if (s.completedAt() == null) {
                return s.withCompletedAtAndError(Status.RETIRED, Instant.now(), reason);
            }
            return s.withCompletedAtAndError(Status.RETIRED, s.completedAt(), reason);
        });
    }

    public int restartCount() {
        return state.get().restartCount();
    }

    public Optional<Instant> lastRestartAt() {
        return Optional.ofNullable(state.get().lastRestartAt());
    }

    public void incrementRestartCount(Instant timestamp) {
        state.updateAndGet(s -> s.withRestartCount(s.restartCount() + 1, timestamp));
    }

    public void resetRestartCount() {
        state.updateAndGet(s -> s.withRestartCount(0, null));
    }

    public void setRestartCount(int count, Instant timestamp) {
        state.updateAndGet(s -> s.withRestartCount(count, timestamp));
    }

    boolean isTerminal() {
        Status current = state.get().status();
        return current == Status.COMPLETED || current == Status.FAILED
            || current == Status.ARCHIVED || current == Status.RETIRED;
    }
}
