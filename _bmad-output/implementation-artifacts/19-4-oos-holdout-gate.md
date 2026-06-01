# Story 19.4: Holdout OOS verrouillé (prop-shop)

Status: review

## Story

As a Martin,
I want a final OOS holdout never used during parameter search,
So that prop-shop validation avoids leakage beyond purged WFA (PS-GR5).

## Acceptance Criteria

1. **AC1 — Split:** Trailing holdout window split from full bar series (`BarHoldoutSplit`).
2. **AC2 — Frozen params:** Holdout backtest uses same strategy/config as promote source run.
3. **AC3 — Gate:** `OosHoldoutValidationModule` implements `ValidationModule`; gate name `oos_holdout`.
4. **AC4 — Events:** Pass/fail journaled as `OPERATOR_ACTION` with `validationType: OOS_HOLDOUT`, holdout period, metrics.
5. **AC5 — Promote block:** Failure blocks PAPER promote when validation modules enabled.
6. **AC6 — Config:** `data/runtime/oos-holdout.json` + `validationModuleEnabled` in promote gates.
7. **AC7 — Tests:** `BarHoldoutSplitTest`, `OosHoldoutValidationModuleTest`.

## Tasks / Subtasks

- [x] Task 1: Holdout split + runner (AC: 1, 2)
- [x] Task 2: OosHoldoutValidationModule + ValidationContext (AC: 3, 4)
- [x] Task 3: Promote pipeline wiring (AC: 5, 6)
- [x] Task 4: Tests + docs
- [x] Task 5: Verify build

## Dev Agent Record

### File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/OosHoldoutConfig.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BarHoldoutSplit.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/HoldoutBacktestRunner.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/OosHoldoutValidationModule.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ValidationContext.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ValidationModules.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ValidationModule.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/PromoteGates.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/PromoteService.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunConfigSnapshot.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneMain.java`
- `data/runtime/oos-holdout.json`
- `docs/testing.md`

## Change Log

- 2026-05-30: Story 19.4 implemented — locked OOS holdout validation module for promote gates
