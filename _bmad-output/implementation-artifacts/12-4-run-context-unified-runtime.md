# Story 12.4: RunContext & Unified Runtime

Status: done

## Story

As a platform developer,
I want a single `RunContext` that drives backtest (and later paper/live) from `StrategyCatalog`,
so that all execution modes share one code path and the golden backtest remains the trust contract.

## Acceptance Criteria

1. **AC1 — RunContext:** `com.martinfou.trading.backtest.RunContext` (or `com.martinfou.trading.core.runtime.RunContext` if cleaner) encapsulates:
  - `strategyId`, `symbol`, `mode` enum (`BACKTEST`, `PAPER`, `LIVE` — only `BACKTEST` implemented this story)
  - `List<Bar> bars`, `double initialCapital`
  - Factory: `RunContext.backtest(String strategyId, String symbol, List<Bar> bars, double capital)` resolves strategy via `StrategyCatalog.create(id, symbol)`
2. **AC2 — Unified run API:** `RunResult run()` delegates to `BacktestEngine` today; returns existing `BacktestResult` wrapped or directly (no duplicate metrics logic).
3. **AC3 — RunBacktest integration:** `RunBacktest.runStrategy(...)` and strategy-id CLI path use `RunContext` internally (thin wrapper, no behavior change).
4. **AC4 — Golden unchanged:** `GoldenBacktestTest` passes with same baseline (8760 bars, 63 trades, +16.44%, $16,439.51 PnL).
5. **AC5 — Tests:** `RunContextTest` — create from catalog id, empty bars → zero trades; LondonOpenRangeBreakout smoke matches direct `BacktestEngine` call.

## Tasks / Subtasks

- Task 1: Add `RunMode` enum + `RunContext` record/class in `trading-backtest` (depends on `trading-strategies` for catalog — add module dep if missing)
- Task 2: Wire `RunBacktest` to use `RunContext.backtest(...).run()`
- Task 3: `RunContextTest` + verify golden
- Task 4: `mvn clean install` green

## Dev Notes

### Architecture (brainstorming 2026-05-23)

```
StrategyCatalog.create(id, sym)
        ↓
   RunContext (mode, bars, capital)
        ↓
   BacktestEngine → BacktestResult   [12.4]
   PaperExecutor  → RunResult       [12.6]
   LiveExecutor   → RunResult       [Epic 4]
```

### Module dependency

- `trading-backtest` currently depends on `trading-core` only.
- **Decision:** Add `trading-strategies` as compile dependency to `trading-backtest` OR keep catalog resolution in `trading-examples` and pass `Strategy` into `RunContext`.
- **Preferred (minimal cycle risk):** `RunContext.forStrategy(Strategy strategy, RunMode mode, List<Bar> bars, double capital)` in `trading-backtest`; catalog resolution stays in `RunBacktest`. Optional overload `RunContext.backtest(String id, String symbol, ...)` in `trading-examples` adapter if needed.

### Scope boundaries

- No JSONL events (12.5)
- No paper runner (12.6)
- No HTTP control plane (Epic 13)
- No changes to `Strategy.getPendingOrders()` contract (deferred 12-7)

### Previous story (12.3)

- `StrategyCatalog.create(id, symbol)` is the lookup API
- `RunBacktest.runStrategy(Strategy, List<Bar>, double)` is the shared helper for `RunPropBacktest --all`

### References

- [Source: _bmad-output/brainstorming/brainstorming-session-2026-05-23-1800.md]
- [Source: _bmad-output/implementation-artifacts/12-3-unified-backtest-cli-strategy-catalog.md]
- [Source: trading-backtest/.../BacktestEngine.java]
- [Source: trading-examples/.../RunBacktest.java]

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- Added `RunMode` enum and `RunContext` record with `backtest()` and `forStrategy()` factories
- `trading-backtest` depends on `trading-strategies` (compile) for catalog resolution
- `RunBacktest` and `GoldenBacktestTest` wired through `RunContext`
- `BacktestEngine` handles empty bar lists (null period bounds, skip close on last bar)
- `RunContextTest`: empty bars → 0 trades; catalog path matches direct engine

### File List

- trading-backtest/src/main/java/com/martinfou/trading/backtest/RunMode.java (new)
- trading-backtest/src/main/java/com/martinfou/trading/backtest/RunContext.java (new)
- trading-backtest/src/main/java/com/martinfou/trading/backtest/BacktestEngine.java (empty bars guard)
- trading-backtest/src/test/java/com/martinfou/trading/backtest/RunContextTest.java (new)
- trading-backtest/src/test/java/com/martinfou/trading/backtest/GoldenBacktestTest.java (modified)
- trading-backtest/pom.xml (trading-strategies compile dep)
- trading-examples/src/main/java/com/martinfou/trading/examples/RunBacktest.java (modified)

## Change Log

- 2026-05-23: Story spec created from party-mode brainstorming (pipeline 12.4)
- 2026-05-23: Story 12.4 implemented — RunContext + RunMode, RunBacktest integration

