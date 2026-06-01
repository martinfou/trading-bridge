# Story 15.8: Runbook opérationnel paper → promote (prop-shop)

Status: review

## Story

As a Martin,
I want a documented operational ritual for the 30-day paper observation period,
So that promotion is a deliberate decision, not an accidental click.

## Acceptance Criteria

1. **AC1 — Runbook:** `docs/prop-shop-runbook.md` covers daily review, gate status, reconciliation alerts, promote/kill decision.
2. **AC2 — API:** `GET /api/strategies/{id}/promote-readiness` exposes elapsed paper days, gates, reconciliation status.
3. **AC3 — PAPER_STUB:** Runbook states stub is dev-only and excluded from LIVE path.
4. **AC4 — Tests:** API test for readiness endpoint structure.

## Tasks / Subtasks

- [x] Task 1: PromoteReadinessService (AC: 2)
  - [x] Gates mirror PromoteService; paper elapsed days; reconciliation scan
- [x] Task 2: HTTP route (AC: 2, 4)
  - [x] `GET /api/strategies/{id}/promote-readiness`
- [x] Task 3: Runbook doc (AC: 1, 3)
  - [x] `docs/prop-shop-runbook.md`
- [x] Task 4: Tests + docs
  - [x] `PromoteReadinessServiceTest`, `ControlPlaneServerTest.promoteReadiness_*`
- [x] Task 5: Verify build
  - [x] `mvn test -pl trading-runtime -am -Dtest=PromoteReadinessServiceTest,ControlPlaneServerTest`

## Dev Agent Record

### Agent Model Used

Composer

### File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/PromoteReadinessService.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/PromoteReadinessServiceTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlPlaneServerTest.java`
- `docs/prop-shop-runbook.md`
- `docs/testing.md`
- `_bmad-output/implementation-artifacts/15-8-prop-shop-runbook.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 15.8 implemented — operational runbook and promote-readiness API
