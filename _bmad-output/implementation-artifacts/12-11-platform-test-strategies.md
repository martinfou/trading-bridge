# Story 12.11 — Platform Test Strategies

**Status:** done  
**Epic:** 12 — Platform Consolidation  
**Date:** 2026-05-30

## Summary

Deterministic scripted strategies (`TestStrategies`, `TestBars`) and parameterized platform tests (`PlatformRobustnessTest`) exercising BACKTEST vs PAPER parity and accounting invariants. Complements `BacktestEngineContractTest` (Story 12.10). Landed in commit `e1c7839`.

## Acceptance criteria

- [x] Deterministic test strategies without catalog or random data
- [x] Bar fixtures with fixed UTC timestamps
- [x] Parameterized normal + edge scenarios
- [x] BACKTEST vs PAPER parity via `RunContext`
- [x] Accounting invariants (`totalPnl`, `finalEquity`, costs)
- [x] Documented in `docs/testing.md`
- [x] `PlatformRobustnessTest` + `BacktestEngineContractTest` green

## Files

| Path | Role |
|------|------|
| `trading-backtest/.../TestStrategies.java` | Scripted strategy factories |
| `trading-backtest/.../TestBars.java` | OHLC / flat bar fixtures |
| `trading-backtest/.../PlatformRobustnessTest.java` | Parameterized platform scenarios |
| `trading-backtest/.../BacktestEngineContractTest.java` | Engine micro-contracts (12.10) |
| `docs/testing.md` | Operator docs |

### Review Findings

- [x] [Review][Patch] Weak `smaCrossover_producesTrades` scenario [`PlatformRobustnessTest.java:100`] — fixed: `TestBars.uptrend` + assert `totalTrades > 0`.
- [x] [Review][Patch] Missing fill-price assertion on `stopSell_fillsOnBreakdown` [`PlatformRobustnessTest.java:159`] — fixed: entry at 1.0995 (`max(close, stop)` engine semantics).
- [x] [Review][Patch] Document PAPER stub parity caveat [`PlatformRobustnessTest.java:19`] — fixed: class javadoc.

- [x] [Review][Defer] No `RunContext` + `executionCost` scenario in platform suite — covered by `BacktestEngineContractTest` and Story 13.9 paths.
- [x] [Review][Defer] `TestStrategies.java` size (~690 lines) — acceptable for test-only helpers until reuse outside module.
