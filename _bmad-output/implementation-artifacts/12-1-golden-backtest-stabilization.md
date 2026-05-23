# Story 12.1: Golden Backtest & Build Stabilization

Status: ready-for-dev

<!-- Validation optional: bmad-create-story validate before bmad-dev-story -->

## Story

As a developer,
I want a golden backtest integration test and a reliable `mvn clean install`,
so that Epic 12 consolidation changes can be validated without regressions.

## Acceptance Criteria

1. **AC1 — Golden test:** Integration test loads EUR_USD H1 2012 via `HistoricalDataLoader`, runs `LondonOpenRangeBreakoutStrategy`, and asserts bar count (~8760), trade count (~63), and total PnL within ±1% of recorded baseline.
2. **AC2 — Build:** `mvn clean install` succeeds from repo root on current JDK/Maven versions.
3. **AC3 — Data prerequisite:** Test skips gracefully with clear message if `data/historical/` files missing (CI may use fixture subset or `@EnabledIf`).
4. **AC4 — Baseline doc:** Baseline values documented in test class or `docs/testing.md` with commit hash when captured.
5. **AC5 — Stale target:** README or AGENTS.md notes that `Unresolved compilation problem` in tests often requires `mvn clean install`.

## Tasks / Subtasks

- [ ] Task 1: Capture baseline (AC: 1, 4)
  - [ ] Run prop backtest on EUR_USD 2012; record bars, trades, PnL, max drawdown
  - [ ] Store constants in test or golden JSON resource
- [ ] Task 2: Add integration test (AC: 1, 3)
  - [ ] Module: `trading-backtest` or `trading-examples` (prefer backtest if no circular deps)
  - [ ] Use `HistoricalDataLoader.load(symbol, H1, year)`
  - [ ] Assert metrics within tolerance
- [ ] Task 3: Verify full reactor build (AC: 2)
  - [ ] Run `mvn clean install`; fix any failures unrelated to consolidation scope
- [ ] Task 4: Document clean requirement (AC: 5)
  - [ ] One paragraph in AGENTS.md troubleshooting section

## Dev Notes

- **Reference command (pre-consolidation):**
  ```bash
  mvn exec:java -pl trading-examples \
    -Dexec.mainClass="com.martinfou.trading.examples.RunPropBacktest" \
    -Dexec.args="LondonOpenRangeBreakout EUR_USD 2012"
  ```
- **Known baseline (2026-05-23):** ~8760 bars, ~63 trades, ~+16.44% return — re-verify at implementation time.
- **Data paths:** `data/historical/dukascopy/` or `data/historical/bars/` — gitignored; local dev must have 2012 EUR_USD H1.
- **Do not** refactor loaders or CLI in this story — only test + build stability.

### Project Structure Notes

- Golden tests belong with backtest engine: `trading-backtest/src/test/java/...`
- Depends on `trading-strategies` (prop) and `trading-data` (HistoricalDataLoader) — confirm test-scoped deps in POM.

### References

- [Source: _bmad-output/planning-artifacts/sprint-12-consolidation-plan.md]
- [Source: _bmad-output/planning-artifacts/epics.md#Epic-12]
- [Source: AGENTS.md — backtest commands]
- [Source: trading-data/.../HistoricalDataLoader.java]
- [Source: trading-strategies/.../prop/LondonOpenRangeBreakoutStrategy.java]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
