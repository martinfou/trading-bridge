package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** In-memory run metadata tracked by {@link RunManager}. */
public final class RunRecord {

    public enum Status {
        RUNNING, COMPLETED, FAILED
    }

    private final String runId;
    private final String strategyId;
    private final String symbol;
    private final RunMode mode;
    private final Instant startedAt;
    private volatile Status status;
    private volatile Instant completedAt;
    private volatile String errorMessage;
    private volatile Map<String, Object> endedPayload;

    RunRecord(String runId, String strategyId, String symbol, RunMode mode) {
        this.runId = runId;
        this.strategyId = strategyId;
        this.symbol = symbol;
        this.mode = mode;
        this.startedAt = Instant.now();
        this.status = Status.RUNNING;
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
}
