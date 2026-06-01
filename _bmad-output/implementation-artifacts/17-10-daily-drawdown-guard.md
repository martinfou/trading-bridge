# Story 17.10: Daily drawdown guard via RiskEngine (prop-shop)

Status: review

## Story

As a Martin,
I want automatic pause when daily drawdown exceeds configured threshold,
So that prop-shop loss limits trigger without manual intervention (PS-GR7).

## Acceptance Criteria

1. **AC1 — RiskEngine:** `RiskEngine.checkDailyDrawdown()` tracks UTC-day peak equity and fails when drawdown exceeds `maxDailyDrawdownPct` (disabled when `<= 0`).
2. **AC2 — Pause:** Breach triggers run `PAUSED` via `RunManager` + `RunTransition.PAUSE`; reason `DAILY_DD_BREACH`.
3. **AC3 — Events:** `OPERATOR_ACTION` (action=`DAILY_DD_BREACH`, actor=`RISK_ENGINE`); subsequent orders blocked with `REJECT` (`rejectSource: RISK_ENGINE`, limit=`max_daily_drawdown_pct`).
4. **AC4 — Dashboard:** `GET /control/summary` run items expose `dailyDrawdownPct`, `maxDailyDrawdownPct`, `dailyDdBreached` when broker run is active.
5. **AC5 — Tests:** `RiskEngineTest` (unit + broker integration scenario).

## Tasks / Subtasks

- [x] Task 1: Daily drawdown tracker + RiskEngine extension (AC: 1)
  - [x] `DailyDrawdownTracker`, `RiskLimits.maxDailyDrawdownPct`, `data/runtime/risk-limits.json`
- [x] Task 2: BrokerRunExecutor integration (AC: 2–3)
  - [x] `RunRiskContext`, per-bar evaluation, order block, RUN_ENDED metrics
- [x] Task 3: RunManager + ControlSummaryService (AC: 2, 4)
  - [x] Pause on breach, metrics map, summary fields
- [x] Task 4: Tests + docs
  - [x] `RiskEngineTest.checkDailyDrawdown_*`, `brokerRunExecutor_dailyDrawdownPausesAndBlocksOrders`
- [x] Task 5: Verify build
  - [x] `mvn test -pl trading-runtime -am -Dtest=RiskEngineTest,ControlSummaryServiceTest,RunManagerTest`

## Dev Agent Record

### Agent Model Used

Composer

### File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/DailyDrawdownTracker.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/DailyDrawdownMetrics.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunRiskContext.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RiskEngine.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RiskLimits.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerRunExecutor.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/RiskEngineTest.java`
- `data/runtime/risk-limits.json`
- `docs/testing.md`
- `_bmad-output/implementation-artifacts/17-10-daily-drawdown-guard.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 17.10 implemented — daily drawdown guard via shared RiskEngine
