# Story 26.3: CLI Query & REST API Endpoints

Status: done

## Story

As a developer/trader,
I want to query historical backtests via both the CLI and a REST API,
so that I can filter, inspect, and retrieve run records programmatically or through the terminal.

## Acceptance Criteria

1. **Given** the `RunBacktest` CLI in `trading-examples`
   **When** I run with the `--query` flag
   **Then** it queries the database and prints an ASCII table of the results.
   **And** it supports the following filters:
     - `--symbol <SYMBOL>`
     - `--strategy <STRATEGY_ID>`
     - `--min-profit-factor <VALUE>`
     - `--min-sharpe <VALUE>`
     - `--sort-by <sharpe | profit_factor | pnl | drawdown>`
     - `--limit <N>`

2. **Given** the Control Plane running in `trading-runtime`
   **When** I make a `GET /api/backtests` request
   **Then** it accepts query parameters (`symbol`, `strategyId`, `minSharpe`, `minProfitFactor`, `sortBy`, `limit`) and returns a JSON array of matching `BacktestRunSummary` records (with HTTP status 200).

3. **Given** a valid `runId`
   **When** I make a `GET /api/backtests/{runId}` request
   **Then** it returns the detailed `BacktestRunDetails` JSON including the `equityCurve` and `parameters` (with HTTP status 200).
   **And** returns HTTP 404 if the run ID does not exist.

## Tasks / Subtasks

- [ ] **Task 1: CLI Flags & Print Table (AC 1)**
  - [ ] Update `RunBacktest` in `trading-examples` to parse CLI flags: `--query`, `--symbol`, `--strategy`, `--min-profit-factor`, `--min-sharpe`, `--sort-by`, `--limit`.
  - [ ] Implement ASCII table formatting utility to output clean columns to the console.
- [ ] **Task 2: REST Controllers Setup (AC 2, AC 3)**
  - [ ] Implement `BacktestController` (or extend existing endpoint routers) in `trading-runtime`.
  - [ ] Register REST endpoints: `GET /api/backtests` and `GET /api/backtests/{runId}`.
- [ ] **Task 3: Integration Tests (AC 1, 2, 3)**
  - [ ] Add unit/integration tests for `BacktestController` routing and parameter binding.
  - [ ] Add unit/mock tests for CLI query execution and table rendering.

## Dev Notes

- Ensure `GET /api/backtests` is highly performant (e.g. loads summaries, not the full equity curve string) to meet the NFR <200ms page load target.
- Do not mix business logic with endpoint routing. Delegate database calls to `SqliteBacktestRunStore` via the persistence service.
