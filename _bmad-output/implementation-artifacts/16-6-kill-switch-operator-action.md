# Story 16.6: Kill switch et OPERATOR_ACTION (prop-shop)

Status: review

## Story

As a Martin,
I want an emergency kill that stops new orders and logs the action,
So that I can halt trading with audit trail.

## Acceptance Criteria

1. **AC1 — API:** `POST /api/strategies/{id}/kill` with `{ actor, reason }` returns 202.
2. **AC2 — Eligibility:** Requires PAPER_OANDA or LIVE deployment; rejects PAPER_STUB.
3. **AC3 — Stop orders:** Kill flag blocks new broker submissions; REJECT with "Kill switch active".
4. **AC4 — Audit:** `OPERATOR_ACTION` RunEvent with action=KILL, actor, reason, UTC timestamp.
5. **AC5 — Evidence:** Kill action appears in `GET /api/runs/{runId}/export` JSONL.
6. **AC6 — Tests:** `ControlPlaneServerTest`, `BrokerRunExecutorTest`.

## Tasks / Subtasks

- [x] Task 1: Event type (AC: 4)
  - [x] `RunEventType.OPERATOR_ACTION`, `RunEvent.operatorAction(...)`
- [x] Task 2: Kill switch service (AC: 1, 2, 4)
  - [x] `KillSwitchRegistry`, `KillSwitchService`
- [x] Task 3: Worker enforcement (AC: 3)
  - [x] `BrokerRunExecutor` checks registry before `broker.submitOrder`
- [x] Task 4: Control plane (AC: 1, 5)
  - [x] `POST /api/strategies/{id}/kill` on `ControlPlaneServer`
- [x] Task 5: Tests + docs
  - [x] `ControlPlaneServerTest`, `BrokerRunExecutorTest`
- [x] Task 6: Verify build
  - [x] `mvn test -pl trading-runtime -am`

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- Strategy-level kill flag shared between control plane and worker via `KillSwitchRegistry`
- OPERATOR_ACTION appended to all RUNNING broker-backed runs at kill time

### File List

- `trading-backtest/src/main/java/com/martinfou/trading/backtest/events/RunEventType.java`
- `trading-backtest/src/main/java/com/martinfou/trading/backtest/events/RunEvent.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/KillSwitchRegistry.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/KillSwitchService.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerRunExecutor.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneMain.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlPlaneServerTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/BrokerRunExecutorTest.java`
- `docs/testing.md`
- `_bmad-output/implementation-artifacts/16-6-kill-switch-operator-action.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 16.6 implemented — kill switch API, OPERATOR_ACTION audit events
