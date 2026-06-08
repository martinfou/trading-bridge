# Story 26.1: SQLite Backtest Schema

Status: ready-for-dev

## Story

As a developer,
I want to create the SQLite schema and repository classes for backtest runs,
so that backtest performance results and metadata can be saved to the database.

## Acceptance Criteria

1. **Given** the `trading-backtest` module
   **When** I create `SqliteBacktestRunStore` in package `com.martinfou.trading.backtest.persistence`
   **Then** the class compiles successfully and is isolated from any compile-time dependency on `trading-runtime`.

2. **Given** a database connection or file path
   **When** `SqliteBacktestRunStore` initializes
   **Then** it ensures that the `backtest_runs` table is created in the database with the following columns:
     - `run_id` (TEXT PRIMARY KEY)
     - `strategy_id` (TEXT NOT NULL)
     - `symbol` (TEXT NOT NULL)
     - `period_start` (TEXT NOT NULL, ISO 8601 UTC)
     - `period_end` (TEXT NOT NULL, ISO 8601 UTC)
     - `parameters` (TEXT NOT NULL, JSON format)
     - `parameter_hash` (TEXT NOT NULL)
     - `initial_capital` (REAL NOT NULL)
     - `final_equity` (REAL NOT NULL)
     - `total_pnl` (REAL NOT NULL)
     - `total_return_pct` (REAL NOT NULL)
     - `total_trades` (INTEGER NOT NULL)
     - `winning_trades` (INTEGER NOT NULL)
     - `losing_trades` (INTEGER NOT NULL)
     - `win_rate_pct` (REAL NOT NULL)
     - `max_drawdown_pct` (REAL NOT NULL)
     - `avg_trade_pnl` (REAL NOT NULL)
     - `sharpe_ratio` (REAL NOT NULL)
     - `sortino_ratio` (REAL NOT NULL)
     - `profit_factor` (REAL NOT NULL)
     - `calmar_ratio` (REAL NOT NULL)
     - `total_commission` (REAL NOT NULL)
     - `total_slippage` (REAL NOT NULL)
     - `equity_curve` (TEXT NOT NULL, JSON format)
     - `created_at` (TEXT NOT NULL, ISO 8601 UTC)
   **And** it creates an index `idx_backtest_runs_strategy_hash` on `(strategy_id, parameter_hash)`.

3. **Given** the `SqliteBacktestRunStore`
   **When** I run CRUD operations
   **Then** I can insert a run, query a run by `run_id`, list runs matching filters (symbol, strategyId, minSharpe, minProfitFactor, limit, offset), and count total runs.

## Tasks / Subtasks

- [ ] **Task 1: DTO and Schema Definition (AC 1, AC 2)**
  - [ ] Define `BacktestRunDetails` record/class to hold all fields (including equity curve and parameters).
  - [ ] Define `BacktestRunSummary` record/class to hold list fields (excluding heavy fields like equity curve).
  - [ ] Create `SqliteBacktestRunStore` with DDL scripts for table initialization and index creation.
- [ ] **Task 2: Database Operations & Queries (AC 3)**
  - [ ] Implement `insert(BacktestRunDetails run)`
  - [ ] Implement `get(String runId)` returning `Optional<BacktestRunDetails>`
  - [ ] Implement `list(BacktestQueryFilters filters)` returning `List<BacktestRunSummary>`
  - [ ] Implement query filters mapping to dynamic SQL statements (symbol, strategyId, minSharpe, minProfitFactor, sorting, limit).
- [ ] **Task 3: Integration Tests (AC 1, 2, 3)**
  - [ ] Create `SqliteBacktestRunStoreTest` in `trading-backtest` tests.
  - [ ] Use an in-memory or temporary SQLite database to verify all DDL setup, insertion, and query filtering behaviors.

## Dev Notes

- Re-use the `org.xerial:sqlite-jdbc` library dependency.
- Make sure to enable WAL mode where the connection is instantiated.
- Ensure proper resource handling (close statements, result sets, and connections).
- Keep SQL columns exactly matching the lowercase `snake_case` naming conventions from the PRD.
