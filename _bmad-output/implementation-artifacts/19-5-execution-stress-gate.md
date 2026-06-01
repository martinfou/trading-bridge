# Story 19.5: Stress test exécution spread/slippage dégradés (prop-shop)

Status: review

## Story

As a Martin,
I want stress gates with degraded spread and slippage,
So that strategies fail promote before live if fragile to execution costs (PS-GR6 stress).

## Acceptance Criteria

1. **AC1 — Cost model:** Uses shared `BacktestExecutionCost` from Story 13.9 with stress multipliers.
2. **AC2 — Module:** `ExecutionStressValidationModule` gate `execution_stress` in promote SPI.
3. **AC3 — Thresholds:** Pass/fail on stressed max DD and min return vs config.
4. **AC4 — Events:** `OPERATOR_ACTION` with `validationType: EXECUTION_STRESS`, baseline vs stress costs.
5. **AC5 — CI:** Deterministic sample-bars scenario in unit tests.
6. **AC6 — Config:** `data/runtime/execution-stress.json` + promote validation flag.
7. **AC7 — Tests:** `ExecutionStressConfigTest`, `ExecutionStressValidationModuleTest`.

## Tasks / Subtasks

- [x] Task 1: ExecutionStressConfig + stress cost profile (AC: 1)
- [x] Task 2: ValidationBacktestRunner shared with holdout path (AC: 1)
- [x] Task 3: ExecutionStressValidationModule + ValidationModules wiring (AC: 2–4)
- [x] Task 4: Tests + docs (AC: 5–7)
- [x] Task 5: Verify build

## Dev Agent Record

### File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ExecutionStressConfig.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ExecutionStressValidationModule.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ValidationBacktestRunner.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ValidationBarLoader.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ValidationModules.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/HoldoutBacktestRunner.java`
- `data/runtime/execution-stress.json`
- `docs/testing.md`

## Change Log

- 2026-05-30: Story 19.5 implemented — execution stress validation gate for promote pipeline
