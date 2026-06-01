# Story 17.12: Drift actif post-broker uniquement (prop-shop)

Status: review

## Story

As a Martin,
I want drift signals computed only when broker execution events exist,
So that FR-15 does not fire misleading alerts on stub runs (PS-GR14).

## Acceptance Criteria

1. **AC1 — Stub gate:** BACKTEST / PAPER_STUB only → `HOLD`, `dataSource: INSUFFICIENT`.
2. **AC2 — Min observation:** Broker drift requires ≥14 days or ≥20 trades before metrics apply.
3. **AC3 — Broker evaluation:** PAPER_OANDA / LIVE with sufficient data → FR-15 recommendations with `dataSource: BROKER`.
4. **AC4 — Composite:** 1 red dimension → `REVIEW_PARAMS`; 2+ → `PAUSE`.
5. **AC5 — Summary:** `GET /control/summary` populates `signals.drift[]`.
6. **AC6 — Tests:** `DriftEngineTest` with/without broker events.

## Tasks / Subtasks

- [x] Task 1: DriftEngine + DriftThresholds (AC: 2–4) — includes Story 17.5 core
- [x] Task 2: DriftSignalService + ControlSummaryService wiring (AC: 1, 5)
- [x] Task 3: Config + docs
  - [x] `data/runtime/drift-thresholds.json`
- [x] Task 4: Tests
- [x] Task 5: Verify build

## Dev Agent Record

### File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/DriftRecommendation.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/DriftThresholds.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/DriftMetricSignal.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/DriftEvaluation.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/DriftEngine.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/DriftSignalService.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BacktestRunMetrics.java`
- `data/runtime/drift-thresholds.json`
- `docs/testing.md`

## Change Log

- 2026-05-30: Story 17.12 implemented — broker-gated drift engine wired to control summary
