# Story 17.9: Dashboard prop-shop minimal (prop-shop)

Status: review

## Story

As a Martin,
I want a control room view showing broker runs, freshness, and execution labels,
So that I supervise paper/live without deep navigation (PS-GR11).

## Acceptance Criteria

1. **AC1 — Endpoint:** `GET /control/summary` (alias `/api/control/summary`) returns `schemaVersion: 1`.
2. **AC2 — Runs:** Each run exposes `executionLabel`, `lastEventAt`, `isStale`, `configSnapshot`, `gaps[]`, `latestEvent`.
3. **AC3 — Freshness:** Global `freshness.lastEventAt` and `secondsSinceLastEvent`.
4. **AC4 — Signals:** `signals.gaps[]` aggregates gap alerts; `signals.drift[]` empty stub (Phase A).
5. **AC5 — Sort:** Runs sorted by severity (stale / gaps first).
6. **AC6 — Tests:** `ControlSummaryServiceTest`, `ControlPlaneServerTest`.

## Tasks / Subtasks

- [x] Task 1: ControlSummaryService (AC: 1–5)
  - [x] Freshness, stale detection (120s default), gap signals
- [x] Task 2: HTTP route (AC: 1)
  - [x] `GET /control/summary` on ControlPlaneServer
- [x] Task 3: Tests + docs
  - [x] `ControlSummaryServiceTest`, `ControlPlaneServerTest.controlSummary_*`
- [x] Task 4: Verify build
  - [x] `mvn test -pl trading-runtime -am`

## Dev Agent Record

### Agent Model Used

Composer

### File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlSummaryServiceTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlPlaneServerTest.java`
- `docs/testing.md`
- `_bmad-output/implementation-artifacts/17-9-control-summary-dashboard.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 17.9 implemented — GET /control/summary for prop-shop control room
