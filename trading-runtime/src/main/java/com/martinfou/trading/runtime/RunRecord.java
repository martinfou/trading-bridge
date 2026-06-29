package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** In-memory run metadata tracked by {@link RunManager}. */
public final class RunRecord {

    public enum Status {
        CREATED, RUNNING, PAUSED, COMPLETED, FAILED, ARCHIVED, RETIRED
    }

    private final String runId;
    private final String strategyId;
    private final String symbol;
    private final RunMode mode;
    private volatile Instant startedAt;
    private final Map<String, Object> configSnapshot;
    private final String configHash;
    private volatile Status status;
    private volatile Instant completedAt;
    private volatile String errorMessage;
    private volatile Map<String, Object> endedPayload;
    private volatile Instant lastEventAt;
    private volatile int restartCount;
    private volatile Instant lastRestartAt;

    RunRecord(String runId, String strategyId, String symbol, RunMode mode, RunConfigSnapshot configSnapshot) {
        this.runId = runId;
        this.strategyId = strategyId;
        this.symbol = symbol;
        this.mode = mode;
        this.startedAt = Instant.now();
        this.configSnapshot = configSnapshot.toMap();
        this.configHash = configSnapshot.hash();
        this.status = Status.CREATED;
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
        this.startedAt = startedAt;
        this.configSnapshot = configSnapshot;
        this.configHash = configHash;
        this.status = status;
        this.completedAt = completedAt;
        this.errorMessage = errorMessage;
        this.endedPayload = endedPayload;
        this.lastEventAt = lastEventAt;
        this.restartCount = restartCount;
        this.lastRestartAt = lastRestartAt;
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
        return startedAt;
    }

    public Map<String, Object> configSnapshot() {
        return configSnapshot;
    }

    public String configHash() {
        return configHash;
    }

    public Status status() {
        return status;
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    public Optional<String> errorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    public Optional<Map<String, Object>> endedPayload() {
        return Optional.ofNullable(endedPayload);
    }

    public Optional<Instant> lastEventAt() {
        return Optional.ofNullable(lastEventAt);
    }

    void noteEventAt(Instant timestamp) {
        this.lastEventAt = timestamp;
    }

    void markRunning() {
        this.status = Status.RUNNING;
        this.startedAt = Instant.now();
    }

    void markPaused() {
        this.status = Status.PAUSED;
    }

    void markCompleted(Map<String, Object> payload) {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
        this.endedPayload = payload;
    }

    void markFailed(String message) {
        this.status = Status.FAILED;
        this.completedAt = Instant.now();
        this.errorMessage = message;
    }

    void markArchived() {
        this.status = Status.ARCHIVED;
        if (this.completedAt == null) {
            this.completedAt = Instant.now();
        }
    }

    /**
     * Marks the run as RETIRED — gracefully decommissioned by an operator,
     * as opposed to ARCHIVED (evicted by age) or FAILED (error-driven).
     */
    void markRetired(String reason) {
        this.status = Status.RETIRED;
        this.errorMessage = reason;
        if (this.completedAt == null) {
            this.completedAt = Instant.now();
        }
    }

    public int restartCount() {
        return restartCount;
    }

    public Optional<Instant> lastRestartAt() {
        return Optional.ofNullable(lastRestartAt);
    }

    public void incrementRestartCount(Instant timestamp) {
        this.restartCount++;
        this.lastRestartAt = timestamp;
    }

    public void resetRestartCount() {
        this.restartCount = 0;
        this.lastRestartAt = null;
    }

    public void setRestartCount(int count, Instant timestamp) {
        this.restartCount = count;
        this.lastRestartAt = timestamp;
    }

    boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED
            || status == Status.ARCHIVED || status == Status.RETIRED;
    }
}
