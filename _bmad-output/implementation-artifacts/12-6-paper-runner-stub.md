# Story 12.6: Paper Runner Stub

Status: done

## Story

As a quant developer,
I want to run the same `StrategyCatalog` strategy in paper mode with simulated fills and the same event stream as backtest,
so that I can validate the backtest → paper path before live broker integration.

## Acceptance Criteria

1. **AC1 — PaperExecutor:** `com.martinfou.trading.backtest.paper.PaperExecutor` (or `PaperRunContext`) implements paper fills:
  - Same bar-open fill semantics as `BacktestEngine` for `MARKET` orders (reuse or delegate fill logic)
  - No network calls; no OANDA/IBKR
  - Input: `Strategy`, `List<Bar>`, `initialCapital` (replay historical bars as pseudo-live, bar-by-bar)
2. **AC2 — RunContext mode:** `RunContext.paper(...)` sets `RunMode.PAPER`; `run()` uses `PaperExecutor` instead of `BacktestEngine`.
3. **AC3 — Event parity:** Paper run emits same `RunEvent` types as backtest (`RUN_STARTED`, `RUN_ENDED`); `mode` field = `"PAPER"`. Payload fields compatible with 12.5 schema.
4. **AC4 — CLI:** `RunBacktest --paper` modifier (or `RunPaper` thin wrapper delegating to shared runner):
  ```bash
   RunBacktest LondonOpenRangeBreakout EUR_USD 2012 --paper [--json]
  ```
   Human summary printed like backtest; `--json` emits JSONL.
5. **AC5 — Tests:** `PaperExecutorTest` — known strategy on small synthetic bar list produces ≥1 trade; event stream has `mode=PAPER`; results numerically match `BacktestEngine` on same bars (paper = backtest for stub).

## Tasks / Subtasks

- [x] Task 1: Extract shared fill/simulation helper from `BacktestEngine` if needed (minimal — prefer PaperExecutor wraps BacktestEngine initially)
- [x] Task 2: `RunMode.PAPER` + `RunContext.paper(...)`
- [x] Task 3: CLI `--paper` flag in `RunBacktest`
- [x] Task 4: Tests + docs snippet in `docs/README.md` ("Paper mode (stub)")
- [x] Task 5: `mvn clean install` green; golden backtest unchanged

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- `PaperExecutor` delegates to `BacktestEngine` (stub per spec)
- `RunContext.paper(...)` factory + PAPER branch in `run()`
- `RunBacktest --paper` flag (combinable with `--json`)
- `PaperExecutorTest`: trade count parity + PAPER events
- Golden backtest unchanged; `mvn clean install` green

### File List

- trading-backtest/src/main/java/com/martinfou/trading/backtest/paper/PaperExecutor.java (new)
- trading-backtest/src/main/java/com/martinfou/trading/backtest/RunContext.java (modified)
- trading-backtest/src/test/java/com/martinfou/trading/backtest/paper/PaperExecutorTest.java (new)
- trading-examples/src/main/java/com/martinfou/trading/examples/RunBacktest.java (modified)
- docs/README.md (modified)
- docs/testing.md (modified)

## Change Log

- 2026-05-23: Story 12.6 implemented — PaperExecutor stub + --paper CLI
- 2026-05-23: Code review 12.3–12.6 — patches: null-safe RunEvent error, StrategyCatalog sync

### Review Findings

- [x] [Review][Patch] RunEvent ERROR avec message null — corrigé dans RunContext
- [x] [Review][Patch] StrategyCatalog register/reset thread safety — register/put synchronized
- [x] [Review][Defer] Paper stub = backtest fills — documenté, Epic 4 pour live paper
- [x] [Review][Defer] Couplage backtest→strategies — Epic 13.1 refactor
- [x] [Review][Defer] SLF4J pollue stdout --json — Epic 13 control plane
