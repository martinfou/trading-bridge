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

    void setCompletedAt(Instant completedAt) {
        state.updateAndGet(s -> s.withCompletedAtAndPayload(s.status(), completedAt, s.endedPayload()));
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

    // ── Staging API (Story 48.2 — persist-before-publish) ──────────────────────────────────
    //
    // These compute the TARGET state of a transition WITHOUT installing it, so the caller can
    // persist that state to the store BEFORE publishing it to memory. The invariant being
    // protected: a terminal status must never be visible in memory before it is durable in the
    // store, otherwise a reader (or a crash between the two steps) can observe a run that is
    // terminal in memory but still RUNNING in the database.

    /**
     * The state {@link #markCompleted(Map)} would install, computed without installing it. The
     * caller must persist this state (via {@link #withState(RunState)}) and only then
     * {@link #publish(RunState)} it.
     */
    RunState stagedCompleted(Map<String, Object> payload) {
        return state.get().withCompletedAtAndPayload(Status.COMPLETED, Instant.now(), payload);
    }

    /**
     * The state {@link #markFailed(String)} would install, computed without installing it. The
     * caller must persist it before {@link #publish(RunState) publishing} it.
     */
    RunState stagedFailed(String message) {
        return state.get().withCompletedAtAndError(Status.FAILED, Instant.now(), message);
    }

    /**
     * A new {@link RunRecord} sharing this record's identity (runId, strategyId, symbol, mode,
     * config snapshot and hash) but carrying {@code target} as its state. Persist this view to the
     * store BEFORE publishing {@code target}, so the store never lags behind memory.
     */
    RunRecord withState(RunState target) {
        return new RunRecord(
            runId, strategyId, symbol, mode,
            target.startedAt(), configSnapshot, configHash,
            target.status(), target.completedAt(), target.errorMessage(), target.endedPayload(),
            target.lastEventAt(), target.restartCount(), target.lastRestartAt());
    }

    /**
     * Publishes a staged state to memory. Must be called only AFTER the same state has been
     * persisted via {@link #withState(RunState)}, preserving the persist-before-publish invariant.
     */
    void publish(RunState target) {
        state.set(target);
    }
}
