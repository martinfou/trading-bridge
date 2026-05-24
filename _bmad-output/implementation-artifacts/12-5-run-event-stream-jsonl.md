# Story 12.5: RunEvent Stream (JSONL v1)

Status: done

## Story

As a platform integrator (TUI, Laravel, CI),
I want a versioned JSON Lines event stream emitted during every strategy run,
so that external surfaces can observe runs without parsing human stdout.

## Acceptance Criteria

1. **AC1 — Schema v1:** `com.martinfou.trading.backtest.events.RunEvent` record with fields:
  - `schemaVersion` (int, always `1`)
  - `type` enum: `RUN_STARTED`, `RUN_ENDED`, `BAR`, `ORDER_SUBMITTED`, `FILL`, `ERROR`
  - `timestamp` (ISO-8601 UTC `Instant`)
  - `runId` (UUID string, one per run)
  - `strategyId`, `symbol`, `mode` (string: `BACKTEST`)
  - `payload` (type-specific JSON object — use `Map<String, Object>` or typed records)
2. **AC2 — Emitter hook:** `RunContext` (or `BacktestEngine` wrapper) accepts optional `Consumer<RunEvent>` or `RunEventListener`; emits at minimum:
  - `RUN_STARTED` (strategyId, symbol, bar count, capital)
  - `RUN_ENDED` (totalTrades, totalReturnPct, finalEquity from `BacktestResult`)
  - `ERROR` on failure with message
  - `BAR` optional behind flag (default off — too verbose for golden); `ORDER_SUBMITTED`/`FILL` if engine exposes hooks without major refactor — **minimum viable: STARTED + ENDED + ERROR**
3. **AC3 — CLI `--json`:** `RunBacktest` flag `--json` writes one JSON object per line to **stdout** (events only); human summary goes to stderr or is suppressed when `--json` set.
  ```bash
   RunBacktest LondonOpenRangeBreakout EUR_USD 2012 --json
  ```
4. **AC4 — Serialization:** `RunEventJson` utility: `toJsonLine(RunEvent)` using Jackson (already in parent BOM) or manual JSON for zero new deps in module.
5. **AC5 — Tests:** `RunEventTest` — serialize/deserialize roundtrip; integration test runs backtest with listener, asserts `RUN_STARTED` then `RUN_ENDED`, `runId` matches, `schemaVersion==1`.

## Tasks / Subtasks

- [x] Task 1: Define `RunEvent`, `RunEventType`, payload records
- [x] Task 2: Integrate emitter into `RunContext.run()` (depends on 12.4)
- [x] Task 3: `RunBacktest --json` flag wiring
- [x] Task 4: Unit + smoke tests
- [x] Task 5: Document event schema in `docs/testing.md` (short section)

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- `RunEvent`, `RunEventType`, `RunEventJson` (Jackson + JSR310) in `trading-backtest/events`
- `RunContext` accepts optional `Consumer<RunEvent>`; emits STARTED/ENDED/ERROR
- `RunBacktest --json` strips flag, JSONL on stdout, status on stderr
- `RunEventTest`: roundtrip + integration listener test
- `mvn clean install` green

### File List

- trading-backtest/src/main/java/com/martinfou/trading/backtest/events/RunEventType.java (new)
- trading-backtest/src/main/java/com/martinfou/trading/backtest/events/RunEvent.java (new)
- trading-backtest/src/main/java/com/martinfou/trading/backtest/events/RunEventJson.java (new)
- trading-backtest/src/main/java/com/martinfou/trading/backtest/RunContext.java (modified)
- trading-backtest/src/test/java/com/martinfou/trading/backtest/events/RunEventTest.java (new)
- trading-backtest/pom.xml (jackson deps)
- trading-examples/src/main/java/com/martinfou/trading/examples/RunBacktest.java (modified)
- docs/testing.md (modified)

## Change Log

- 2026-05-23: Story spec created from party-mode brainstorming (pipeline 12.5)
- 2026-05-23: Story 12.5 implemented — RunEvent JSONL v1 + --json CLI
