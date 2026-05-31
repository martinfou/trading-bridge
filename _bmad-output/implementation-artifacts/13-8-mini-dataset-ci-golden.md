# Story 13.8: Mini-dataset CI pour golden E2E (prop-shop)

Status: review

## Story

As a Martin,
I want a committed EUR_USD H1 subset so golden backtest runs in CI without local data,
So that prop-shop backtest trust is proven on every build (PS-GR9).

## Acceptance Criteria

1. **AC1 — CI dataset:** `data/ci/EUR_USD_H1_subset.csv` committed (744 bars, January 2012 H1, documented provenance).
2. **AC2 — Always-on test:** `GoldenBacktestTest.londonOpenRangeBreakout_ciSubset_matchesMiniGoldenBaseline` runs without skip when CI file present; asserts baseline metrics within tolerance.
3. **AC3 — Full year optional:** Existing full-year golden test still skips when `data/historical/` absent.
4. **AC4 — Contract tests unchanged:** `BacktestEngineContractTest` + `PlatformRobustnessTest` still run on every CI build.
5. **AC5 — Docs:** `docs/testing.md` documents mini-dataset metrics and regeneration script.

## Tasks / Subtasks

- [x] Task 1: Generate and commit CI subset CSV (AC: 1)
  - [x] `data/ci/EUR_USD_H1_subset.csv` from Dukascopy Jan 2012
  - [x] `data/ci/README.md` + `scripts/generate-ci-golden-subset.sh`
- [x] Task 2: Extend GoldenBacktestTest (AC: 2, 3)
  - [x] CI baseline constants + return↔PnL sanity check
  - [x] New test method; full-year test unchanged with assume
- [x] Task 3: Documentation (AC: 5)
  - [x] Update `docs/testing.md` CI vs full-year sections
- [x] Task 4: Verify build (AC: 4)
  - [x] `mvn test -pl trading-examples -Dtest=GoldenBacktestTest`
  - [x] `mvn test -pl trading-backtest -Dtest=BacktestEngineContractTest,PlatformRobustnessTest`

## Dev Notes

- **CI baseline (2026-05-30):** 744 bars, 3 trades, 1.8153285714287921% return, $1815.33 PnL, 0.03% max DD, $100k capital.
- **Re-capture CI baseline:**
  ```bash
  ./scripts/generate-ci-golden-subset.sh
  mvn exec:java -pl trading-examples \
    -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
    -Dexec.args="LondonOpenRangeBreakout data/ci/EUR_USD_H1_subset.csv EUR_USD --json"
  ```
- Source: first 744 rows of `eurusd-h1-bid-2012-01-01-2012-12-31.csv` (not `.bars` binary — Dukascopy CSV preferred by `HistoricalDataLoader`).

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- Added committed CI subset (744 H1 bars, Jan 2012 EUR_USD)
- `GoldenBacktestTest` now has always-on CI test + optional full-year test
- Regeneration script for maintainers with local full 2012 download

### File List

- `data/ci/EUR_USD_H1_subset.csv`
- `data/ci/README.md`
- `scripts/generate-ci-golden-subset.sh`
- `trading-examples/src/test/java/com/martinfou/trading/examples/GoldenBacktestTest.java`
- `docs/testing.md`
- `_bmad-output/implementation-artifacts/13-8-mini-dataset-ci-golden.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 13.8 implemented — CI golden mini-dataset + always-on test
