# Story 16.5: Exécution LIVE sur worker local (prop-shop)

Status: review

## Story

As a Martin,
I want LIVE orders executed on the worker node, not the hub,
So that the hub remains a passive observer (PS-GR2).

## Acceptance Criteria

1. **AC1 — LIVE runs:** `mode: LIVE` + `executionLabel: LIVE_OANDA` routes MARKET orders through `Broker` on worker thread.
2. **AC2 — Event journal:** ORDER_SUBMITTED / FILL / REJECT persisted to event store; hub does not submit orders (ADR-13-07).
3. **AC3 — Credentials:** LIVE requires OANDA env credentials (same as PAPER_OANDA).
4. **AC4 — Shared executor:** PAPER_OANDA and LIVE share `BrokerRunExecutor` (refactored from `PaperOandaRunExecutor`).
5. **AC5 — Tests:** `BrokerRunExecutorTest`, `RunManagerTest.startRun_liveOanda_routesOrdersThroughBrokerOnWorker`.

## Tasks / Subtasks

- [x] Task 1: BrokerRunExecutor (AC: 4)
  - [x] Generalized broker bar loop for PAPER and LIVE RunMode
- [x] Task 2: RunManager LIVE path (AC: 1, 2, 3)
  - [x] `BrokerFactory` + injectable factory for tests
  - [x] Remove LIVE mode block; validate LIVE_OANDA + credentials
- [x] Task 3: Tests (AC: 5)
  - [x] `BrokerRunExecutorTest` LIVE + PAPER cases
  - [x] `RunManagerTest` with FakeBroker factory
- [x] Task 4: Docs + verify
  - [x] `docs/testing.md` LIVE section
  - [x] `mvn test -pl trading-runtime -am`

## Dev Notes

- Bar-replay LIVE is the MVP worker path (same loop as PAPER_OANDA); streaming live bars is follow-up.
- `RunContext` LIVE in backtest module remains unsupported by design.

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- Extracted `BrokerRunExecutor` from `PaperOandaRunExecutor`
- LIVE runs execute on virtual-thread worker; control plane only appends events

### File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerRunExecutor.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerFactory.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/BrokerRunExecutorTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/RunManagerTest.java`
- `docs/testing.md`
- `_bmad-output/implementation-artifacts/16-5-live-worker-execution.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 16.5 implemented — LIVE worker-local broker execution via BrokerRunExecutor
