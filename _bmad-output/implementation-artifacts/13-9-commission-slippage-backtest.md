# Story 13.9: Commission et slippage configurables BacktestEngine (prop-shop)

Status: review

## Story

As a Martin,
I want configurable commission and slippage in backtests,
So that prop-shop gates can model realistic execution costs (PS-GR6).

## Acceptance Criteria

1. **AC1 — Run config:** `RunConfigSnapshot` and `StartRunRequest` accept optional `commissionPerTrade` and `slippagePct`; defaults are zero.
2. **AC2 — Engine wiring:** `RunLauncher` / `RunContext` apply costs to `BacktestEngine` for BACKTEST and PAPER modes.
3. **AC3 — Events:** `RUN_STARTED` includes `executionCost` when non-zero; `RUN_ENDED` includes `totalCommission` / `totalSlippage` when > 0.
4. **AC4 — Snapshot hash:** Config hash changes when cost params differ (immutable snapshot at launch).
5. **AC5 — Default parity:** Zero-cost runs match prior fill semantics (existing contract tests unchanged).
6. **AC6 — Tests:** Non-zero commission/slippage scenario covered in unit tests.

## Tasks / Subtasks

- [x] Task 1: Shared cost model (AC: 2, 5)
  - [x] `BacktestExecutionCost` record with `ZERO`, `configure(BacktestEngine)`, `toMap()`
- [x] Task 2: RunContext integration (AC: 2, 3, 5)
  - [x] `executionCost` field + overload on `forStrategy`
  - [x] BACKTEST/PAPER use `BacktestEngine` with cost profile
- [x] Task 3: Runtime snapshot & API (AC: 1, 4)
  - [x] `RunConfigSnapshot` + `StartRunRequest` cost fields
  - [x] `RunLauncher` passes `configSnapshot.executionCost()`
- [x] Task 4: Tests (AC: 6)
  - [x] `BacktestExecutionCostTest`, `RunContextTest`, `RunConfigSnapshotTest`, `RunManagerTest`
  - [x] Existing `BacktestEngineContractTest.commissionAndSlippage_reduceNetPnl` unchanged
- [x] Task 5: Verify build (AC: 5)
  - [x] `mvn test -pl trading-backtest -am -Dtest=BacktestExecutionCostTest,RunContextTest,BacktestEngineContractTest`
  - [x] `mvn test -pl trading-runtime -am -Dtest=RunConfigSnapshotTest,RunManagerTest,RunLifecycleTest`

## Dev Notes

- **Shared model:** `BacktestExecutionCost` is the foundation for Epic 19.5 stress execution gates.
- **HTTP API:** `POST /runs` JSON body accepts optional `commissionPerTrade` and `slippagePct` (Jackson deserializes into `StartRunRequest`).
- **PAPER parity:** PAPER mode now uses `BacktestEngine` with the same cost profile instead of a separate `PaperExecutor` path.

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- Introduced `BacktestExecutionCost` as shared execution-cost profile for backtest/paper runs
- Wired costs through runtime snapshot → launcher → RunContext → BacktestEngine
- RUN events and config hash capture cost params for audit/replay

### File List

- `trading-backtest/src/main/java/com/martinfou/trading/backtest/BacktestExecutionCost.java`
- `trading-backtest/src/main/java/com/martinfou/trading/backtest/RunContext.java`
- `trading-backtest/src/test/java/com/martinfou/trading/backtest/BacktestExecutionCostTest.java`
- `trading-backtest/src/test/java/com/martinfou/trading/backtest/RunContextTest.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunConfigSnapshot.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunLauncher.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/RunConfigSnapshotTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/RunManagerTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/RunLifecycleTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/PromoteServiceTest.java`
- `_bmad-output/implementation-artifacts/13-9-commission-slippage-backtest.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 13.9 implemented — configurable commission/slippage wired through runtime and events
