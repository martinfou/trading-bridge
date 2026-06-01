# Story 15.6: Modèle ExecutionLabel et contrat SM-2 (prop-shop)

Status: review

## Story

As a Martin,
I want a single execution label model on deployments and promote responses,
So that PAPER_STUB is never confused with real broker paper (PS-GR11, SM-2).

## Acceptance Criteria

1. **AC1 — Enum:** `ExecutionLabel` in `trading-runtime` with `BACKTEST`, `PAPER_STUB`, `PAPER_OANDA`, `LIVE_OANDA`, `LIVE_IBKR`.
2. **AC2 — API:** `GET /api/strategies/{id}/deployments` and strategy list expose `executionLabel`.
3. **AC3 — Runs/events:** Runs and `RUN_STARTED` payload include `executionLabel`.
4. **AC4 — LIVE gate:** Promote to LIVE from `PAPER_STUB` returns 422 with « stub does not count toward paper period ».
5. **AC5 — Evidence pack:** `GET /api/runs/{runId}/export` JSONL metadata line includes `executionLabel`.
6. **AC6 — Tests:** `PromoteServiceTest`, `ControlPlaneServerTest`, `ExecutionLabelTest`, `EvidencePackExporterTest`.

## Tasks / Subtasks

- [x] Task 1: ExecutionLabel enum (AC: 1)
  - [x] `ExecutionLabel.forRunMode`, `forPromotedMode`, `countsTowardPaperPeriod()`
- [x] Task 2: Deployment + gates (AC: 4)
  - [x] `DeploymentRecord.executionLabel` typed enum
  - [x] `PromoteGates` stub rejection messages
- [x] Task 3: API + events (AC: 2, 3)
  - [x] `GET /api/strategies/{id}/deployments`
  - [x] `executionLabel` on run JSON, strategies list, `RunLauncher` RUN_STARTED
- [x] Task 4: Evidence export (AC: 5)
  - [x] `EvidencePackExporter` + `GET /api/runs/{runId}/export`
- [x] Task 5: Tests + docs (AC: 6)
  - [x] New tests; `docs/testing.md` ExecutionLabel section
- [x] Task 6: Verify build
  - [x] `mvn test -pl trading-runtime -am -Dtest=ExecutionLabelTest,EvidencePackExporterTest,PromoteGatesTest,PromoteServiceTest,ControlPlaneServerTest`

## Dev Notes

- PAPER promote still sets `PAPER_STUB`; Epic 16.3 will set `PAPER_OANDA` when OANDA broker runs.
- Evidence export is minimal JSONL (metadata line + events); full Story 15.4 can extend with operator actions.

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- Single `ExecutionLabel` enum replaces string constants from Story 15.5
- API, events, deployments, and evidence export all expose the same canonical label
- LIVE from stub blocked with explicit SM-2 message

### File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ExecutionLabel.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/EvidencePackExporter.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/DeploymentRecord.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/PromoteGates.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/PromoteService.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunLauncher.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunEventMessages.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/SqliteDeploymentStore.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/ExecutionLabelTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/EvidencePackExporterTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlPlaneServerTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/PromoteGatesTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/PromoteServiceTest.java`
- `docs/testing.md`
- `_bmad-output/implementation-artifacts/15-6-execution-label-sm2.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 15.6 implemented — ExecutionLabel enum, API, events, evidence export, SM-2 stub gate
