# Story 15.5: Seuils gates promote numériques (prop-shop)

Status: review

## Story

As a Martin,
I want documented numeric thresholds for promote gates,
So that prop-shop decisions are reproducible and auditable (PS-GR3, PS-GR4).

## Acceptance Criteria

1. **AC1 — Config:** `data/runtime/promote-gates.json` + `PromoteGateThresholds` with env override `TRADING_BRIDGE_PROMOTE_GATES`.
2. **AC2 — PAPER gates:** golden/mini-golden (when profile matches), `min_trades`, `max_drawdown_pct`, `min_return_pct`, optional `validation_module`.
3. **AC3 — LIVE gates:** requires PAPER deployment, `PAPER_OANDA` execution label (stub excluded), ≥30 calendar days on OANDA paper.
4. **AC4 — Structured reasons:** `GateCheckResult` includes optional `threshold` and `actual` numeric fields.
5. **AC5 — Docs:** `docs/testing.md` documents default thresholds and gate names.
6. **AC6 — Tests:** `PromoteGatesTest` / `PromoteServiceTest` assert numeric failure reasons.

## Tasks / Subtasks

- [x] Task 1: Threshold config (AC: 1)
  - [x] `PromoteGateThresholds` record + `data/runtime/promote-gates.json`
- [x] Task 2: Gate evaluator (AC: 2, 3, 4)
  - [x] `PromoteGates`, `GoldenBaselineProfiles`, `BacktestRunMetrics`
  - [x] `GateCheckResult` extended with threshold/actual
  - [x] `DeploymentRecord.executionLabel` (PAPER_STUB default)
- [x] Task 3: Run metrics (AC: 2)
  - [x] `maxDrawdownPct` in RUN_ENDED / `RunRecord` ended payload
  - [x] `BarSourceResolver` `ci` type for mini-golden runs
- [x] Task 4: Tests + docs (AC: 5, 6)
  - [x] `PromoteGatesTest`, `PromoteGateThresholdsTest`, extended `PromoteServiceTest`
  - [x] `docs/testing.md` promote gates section
- [x] Task 5: Verify build
  - [x] `mvn test -pl trading-runtime -am -Dtest=PromoteGatesTest,PromoteGateThresholdsTest,PromoteServiceTest,ControlPlaneServerTest`

## Dev Notes

- Default `maxDrawdownPct` = 15% (prop-shop band). Integration tests inject lenient thresholds where sample bars are used.
- `ValidationModule` SPI stubbed; disabled by default until Epic 19.
- Story 15.6 will formalize `ExecutionLabel` enum; this story uses string constants on `PromoteGates`.

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- Numeric promote gates with auditable threshold/actual fields on every metric gate
- LIVE blocked on PAPER_STUB and before 30 OANDA paper days
- Golden baseline gate activates for `barsSource.type=ci` or `year=2012`

### File List

- `data/runtime/promote-gates.json`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/PromoteGateThresholds.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/PromoteGates.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/GoldenBaselineProfiles.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BacktestRunMetrics.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ValidationModule.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/GateCheckResult.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/PromoteService.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/DeploymentRecord.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/SqliteDeploymentStore.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BarSourceResolver.java`
- `trading-backtest/src/main/java/com/martinfou/trading/backtest/RunContext.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/PromoteGatesTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/PromoteGateThresholdsTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/PromoteServiceTest.java`
- `docs/testing.md`
- `_bmad-output/implementation-artifacts/15-5-promote-gates-numeric-thresholds.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 15.5 implemented — documented numeric promote gates with structured 422 reasons
