# Story 16.7: Réconciliation broker ↔ journal (prop-shop)

Status: review

## Story

As a Martin,
I want broker positions reconciled against the event journal,
So that ghost fills are detected before prop-shop review (PS-GR8).

## Acceptance Criteria

1. **AC1 — Compare:** Broker `getPositions()` vs positions derived from journaled FILL events.
2. **AC2 — Alert:** Divergence emits `RECONCILIATION_ALERT` RunEvent with structured divergences.
3. **AC3 — Periodic:** Reconciliation runs after each bar in `BrokerRunExecutor` for broker-backed runs.
4. **AC4 — Skip stub:** `PAPER_STUB` / BACKTEST skipped (no reconciliation).
5. **AC5 — Tests:** `ReconciliationServiceTest` with `FakeBroker`.

## Tasks / Subtasks

- [x] Task 1: Event type + journal tracker (AC: 1, 2)
  - [x] `RunEventType.RECONCILIATION_ALERT`, `JournalPositions`
- [x] Task 2: ReconciliationService (AC: 1, 2, 4)
  - [x] Compare quantities, ghost fill / mismatch reasons
- [x] Task 3: Worker integration (AC: 3)
  - [x] Per-bar reconcile in `BrokerRunExecutor`
- [x] Task 4: Tests + docs
  - [x] `ReconciliationServiceTest`
- [x] Task 5: Verify build
  - [x] `mvn test -pl trading-runtime -am -Dtest=ReconciliationServiceTest`

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- Journal state rebuilt from FILL events only; compared to broker snapshot each bar
- Ghost fills at broker detected when position missing in journal

### File List

- `trading-backtest/src/main/java/com/martinfou/trading/backtest/events/RunEventType.java`
- `trading-backtest/src/main/java/com/martinfou/trading/backtest/events/RunEvent.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/JournalPositions.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ReconciliationService.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerRunExecutor.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/ReconciliationServiceTest.java`
- `docs/testing.md`
- `_bmad-output/implementation-artifacts/16-7-broker-journal-reconciliation.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 16.7 implemented — broker vs journal reconciliation with RECONCILIATION_ALERT
