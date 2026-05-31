# Story 13.9: Interface RunLifecycle et refactor RunManager

Status: review

<!-- Ultimate context engine analysis completed — PRD Epic 13 Story 13.1 (party mode round 2–3) -->

## Story

As a developer,
I want `RunManager` to implement a frozen `RunLifecycle` interface with lifecycle-only responsibilities,
so that promote, gap detection, and deployment logic do not churn the same class and future Salle de contrôle APIs stay stable.

## Acceptance Criteria

1. **AC1 — RunLifecycle interface:** New public interface in `com.martinfou.trading.runtime` with methods: `register(RunConfigSnapshot)`, `start(String runId)`, `stop(String runId)`, `pause(String runId)`, `resume(String runId)`, `archive(String runId)`, `get(String runId)`, `list(RunStatus filter)` where `filter` null means all. Optional `RunTransitionListener` callback interface for post-transition hooks (no promote/gap logic in listener).

2. **AC2 — RunManager implements RunLifecycle:** `RunManager` is the sole implementation. Existing `startRun(StartRunRequest)` remains as a **convenience method** (register + start in one call) used by `ControlPlaneServer` — must delegate internally to lifecycle methods without duplicating execution logic.

3. **AC3 — RunRecord status model extended:** Add lifecycle states needed by interface: at minimum `CREATED`, `RUNNING`, `COMPLETED`, `FAILED`, `PAUSED`, `ARCHIVED`. Transitions enforced in `RunManager` (illegal transition throws `IllegalStateException`). `COMPLETED`/`FAILED` behaviour unchanged for existing backtests.

4. **AC4 — No promote/gap/deployment in RunManager:** `RunManager` must not import or call `PromoteService`, `DeploymentStore`, or `EventGapDetector`. `PromoteService` continues to use `RunManager` only via `getRun`, `latestCompletedRun`, and public lifecycle queries — verify `PromoteServiceTest` and `ControlPlaneServerTest` still pass unchanged API surface.

5. **AC5 — Characterisation tests:** Add `RunLifecycleTest` (or extend `RunManagerTest`) covering: happy-path BACKTEST via `startRun`, illegal pause on COMPLETED run, `list(RUNNING)` filter, listener invoked on transition. **All existing** `RunManagerTest`, `PromoteServiceTest`, `ControlPlaneServerTest`, `BroadcastingEventStoreTest`, `GoldenBacktestTest` (if affected) pass.

6. **AC6 — Build:** `mvn clean test -pl trading-runtime -am` green from repo root.

## Tasks / Subtasks

- [x] Task 1 (AC1): Create `RunLifecycle.java`, `RunTransitionListener.java`, `RunStatus` filter enum or reuse `RunRecord.Status`
- [x] Task 2 (AC3, AC2): Extend `RunRecord` statuses + transition guards in `RunManager`
- [x] Task 3 (AC2): Refactor `startRun` to `register` + `start`; keep HTTP contract unchanged
- [x] Task 4 (AC4): Audit `RunManager` for cross-concern imports; document boundary in class Javadoc
- [x] Task 5 (AC5, AC6): Tests + full module build

## Dev Notes

### Why this story now

Party mode (2026-05-24) identified `trading-runtime` file churn as the top risk. Old Epic 13 stories **13-1..13-5 are done** (decouple, EventStore, HTTP, WS, promote). PRD Epic 13 Story **13.1** is the **next critical path** before Salle de contrôle (`GET /control/summary`) and distributed sync. This story **freezes the lifecycle contract** before Epic 17 Phase A and further `ControlPlaneServer` routes.

### References

- [Source: _bmad-output/planning-artifacts/epics.md — Story 13.1]
- [Source: _bmad-output/planning-artifacts/adr-13-distributed-platform.md — ADR-13-10, phased RunManager]

## Dev Agent Record

### Agent Model Used

Composer

### Debug Log References

- `stop(RUNNING)` throws until Epic 16 adds cooperative cancellation (documented in RunManager).

### Completion Notes List

- Introduced `RunLifecycle`, `RunTransitionListener`, `RunTransition` enum.
- `RunRecord.Status` extended: CREATED, PAUSED, ARCHIVED; register leaves runs in CREATED until start.
- `startRun` delegates to `register` + `start`; bars loaded at start from snapshot fields.
- `RunLifecycleTest` (8 cases) + all existing runtime tests pass via `mvn clean test -pl trading-runtime -am`.

### File List

- trading-runtime/src/main/java/com/martinfou/trading/runtime/RunLifecycle.java (new)
- trading-runtime/src/main/java/com/martinfou/trading/runtime/RunTransitionListener.java (new)
- trading-runtime/src/main/java/com/martinfou/trading/runtime/RunTransition.java (new)
- trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java (modified)
- trading-runtime/src/main/java/com/martinfou/trading/runtime/RunRecord.java (modified)
- trading-runtime/src/test/java/com/martinfou/trading/runtime/RunLifecycleTest.java (new)

## Change Log

- 2026-05-24: Story 13.9 implemented — RunLifecycle interface and RunManager refactor.
