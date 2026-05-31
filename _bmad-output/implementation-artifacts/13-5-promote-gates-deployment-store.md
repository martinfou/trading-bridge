# Story 13.5: Promote Gates + DeploymentStore

Status: done

## Story

As a platform operator,
I want automated gates before promoting a strategy from backtest to paper (and paper to live),
so that only vetted strategies advance in the pipeline with an auditable deployment record.

## Acceptance Criteria

1. **AC1 — DeploymentStore:** Persist `DeploymentRecord` per strategyId (mode, promotedAt, sourceRunId, checks[]). InMemory + SQLite implementations.
2. **AC2 — Promote gates → PAPER:** Requires completed BACKTEST run, min 1 trade, return ≥ -50%. Returns 422 with checks if failed.
3. **AC3 — Promote gates → LIVE:** Requires existing PAPER deployment + stub paper-duration gate (auto-pass until Epic 4).
4. **AC4 — POST /api/strategies/{id}/promote:** Body `{ targetMode, runId? }`; success returns `{ promoted: true, deployment, checks }`.
5. **AC5 — GET /api/strategies:** Includes `deployedMode` when set.
6. **AC6 — Tests + build:** `PromoteServiceTest`, `ControlPlaneServerTest` promote paths; `mvn clean install` green.

## Tasks / Subtasks

- [x] Task 1 (AC1): `DeploymentStore`, `InMemoryDeploymentStore`, `SqliteDeploymentStore`
- [x] Task 2 (AC2–AC3): `PromoteService`, `GateCheckResult`, gate evaluation
- [x] Task 3 (AC4–AC5): Control plane routes; `RunManager.latestCompletedRun`
- [x] Task 4 (AC6): Tests; `RuntimeStores` bundle includes deployment store

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes

- SQLite `deployments` table in same DB as events.
- LIVE promote requires PAPER deployment first; paper duration gate stubbed for Epic 4.

### File List

- trading-runtime/.../DeploymentRecord.java, GateCheckResult.java, DeploymentStore.java
- trading-runtime/.../InMemoryDeploymentStore.java, SqliteDeploymentStore.java
- trading-runtime/.../PromoteService.java
- trading-runtime/.../RunManager.java, RuntimeStores.java, ControlPlaneServer.java
- trading-runtime/src/test/.../PromoteServiceTest.java, ControlPlaneServerTest.java
