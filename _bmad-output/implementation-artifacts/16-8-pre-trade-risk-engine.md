# Story 16.8: Pre-trade risk guards via RiskEngine (prop-shop)

Status: review

## Story

As a Martin,
I want orders blocked before submission when risk limits would be breached,
So that prop-shop rules are enforced at execution time (PS-GR7).

## Acceptance Criteria

1. **AC1 — Limits config:** `maxPositionSize`, `maxOpenExposure` in `data/runtime/risk-limits.json`.
2. **AC2 — Pre-trade check:** `RiskEngine.checkPreTrade()` runs before `Broker.submitOrder` on PAPER_OANDA / LIVE.
3. **AC3 — Reject event:** Blocked orders emit `REJECT` with `rejectSource=RISK_ENGINE`, limit name, threshold, actual.
4. **AC4 — Stub skip:** PAPER_STUB uses backtest path (no broker pre-trade).
5. **AC5 — Tests:** `RiskEngineTest`.

## Tasks / Subtasks

- [x] Task 1: Risk config (AC: 1)
  - [x] `RiskLimits`, `data/runtime/risk-limits.json`, env `TRADING_BRIDGE_RISK_LIMITS`
- [x] Task 2: RiskEngine (AC: 2, 3)
  - [x] `checkPreTrade(order, openPositions)` with position size + exposure checks
- [x] Task 3: Broker integration (AC: 2, 4)
  - [x] `BrokerRunExecutor` calls risk before broker; `ordersRiskBlocked` in RUN_ENDED
- [x] Task 4: Tests + docs
  - [x] `RiskEngineTest`
- [x] Task 5: Verify build
  - [x] `mvn test -pl trading-runtime -am -Dtest=RiskEngineTest`

## Dev Notes

- Event type is `REJECT` (same as broker/kill rejects); payload distinguishes via `rejectSource=RISK_ENGINE`.
- Epic 17.10 extends `RiskEngine` with circuit breakers.

## Dev Agent Record

### Agent Model Used

Composer

### File List

- `data/runtime/risk-limits.json`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RiskLimits.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RiskCheckResult.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RiskEngine.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerRunExecutor.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/RiskEngineTest.java`
- `docs/testing.md`
- `_bmad-output/implementation-artifacts/16-8-pre-trade-risk-engine.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 16.8 implemented — pre-trade risk guards before broker submit
