---
title: SQLite Backtest Results Persistence
status: final
created: 2026-06-06
updated: 2026-06-06
---

# PRD: SQLite Backtest Results Persistence

## 0. Document Purpose
This Product Requirement Document (PRD) defines the functional requirements for persisting and querying backtest simulation results using a local SQLite database. It is intended for software developers implementing the feature and Martin Fournier for validation. It builds upon the existing `trading-core`, `trading-backtest`, and `trading-runtime` architectures.

## 1. Vision
Enable automated storage of every backtest run in a structured, local SQLite database. This allows traders to analyze, compare, and filter strategy performance across different symbols (pairs) and parameter configurations without running redundant simulations or parsing log files.

## 2. Target User

### 2.1 Primary Persona
- **Martin (Algorithmic Trader & Developer)**: Runs multiple backtests for various strategy configurations (generated or imported) and needs a unified, easily filterable view of which strategies perform best on which pairs.

### 2.2 Jobs To Be Done (JTBD)
- **Automatic Persistence**: Persist backtest performance statistics and input parameters automatically upon backtest completion.
- **Performance Filtering**: Query historical backtests using performance thresholds (e.g., Sharpe Ratio > 1.5, Profit Factor > 1.2).
- **GUI Consultation**: Review and filter backtest history directly within the Electron desktop interface.

### 2.3 Key User Journeys (UJ)

- **UJ-1. Saving Backtests on Run**
  - **Persona + Context**: Martin runs a backtest via CLI or Control Plane.
  - **Entry State**: SQLite database initialized.
  - **Path**: Martin runs a backtest for `SmaCrossover` on `EUR_USD` for 2012. The run completes.
  - **Climax**: The backtest results (including parameters like fast/slow period) are stored in SQLite, and a console log confirms: `Backtest run saved to SQLite (run_id: bt_20260606_184522_abc123)`.
  - **Resolution**: The run is available for future filtering.

- **UJ-2. Filtering Runs in the GUI**
  - **Persona + Context**: Martin wants to compare performance of various parameter sets on `EUR_USD`.
  - **Entry State**: Multiple runs with different parameters have been saved.
  - **Path**: Martin opens the desktop app, navigates to the "Backtest History" view, filters by `Symbol: EUR_USD`, and sorts by `Sharpe Ratio`.
  - **Climax**: The app displays a table showing the strategies, their parameters, and key metrics.
  - **Resolution**: Martin identifies that the `SmaCrossover` with periods 15/45 is the most profitable.

## 3. Glossary
- **Backtest Run** - A unique historical simulation execution of a trading strategy, identified by a unique `run_id`.
- **Backtest Database** - The local SQLite database file storing run results.
- **Strategy Parameters** - The configuration parameters used by the strategy (e.g. SMA periods), stored as a JSON string.
- **Backtest Metric** - A calculated performance metric (e.g. Sharpe Ratio, Profit Factor).

## 4. Features

### 4.1 Automatic Backtest Results Persistence
**Description:** On successful completion of any backtest (CLI or runtime), the metrics and parameters are saved to the existing SQLite database (`events.db`), sharing the database path managed by `RuntimeDataPaths.defaultEventStorePath()`.

**Functional Requirements:**

#### FR-1: Automatic Save
Upon completion of a backtest, the system persists the `BacktestResult` and strategy parameters to the existing SQLite database.
**Consequences (testable):**
- A new row is inserted into the `backtest_runs` table in the shared database.
- Failed or interrupted runs do not write incomplete data.

#### FR-2: SQLite Schema with Parameter Support
The SQL schema stores the execution metadata, performance metrics, and input parameters.
**Schema Specifications:**
- `run_id` (TEXT PRIMARY KEY)
- `strategy_id` (TEXT)
- `symbol` (TEXT)
- `period_start` (TEXT, ISO 8601 UTC)
- `period_end` (TEXT, ISO 8601 UTC)
- `parameters` (TEXT, JSON string of parameters, e.g. `{"fastPeriod":20,"slowPeriod":50}`)
- `parameter_hash` (TEXT, deterministic cryptographic hash like MD5 or SHA-256 of sorted parameters to uniquely identify configuration runs)
- `initial_capital` (REAL)
- `final_equity` (REAL)
- `total_pnl` (REAL)
- `total_return_pct` (REAL)
- `total_trades` (INTEGER)
- `winning_trades` (INTEGER)
- `losing_trades` (INTEGER)
- `win_rate_pct` (REAL)
- `max_drawdown_pct` (REAL)
- `avg_trade_pnl` (REAL)
- `sharpe_ratio` (REAL)
- `sortino_ratio` (REAL)
- `profit_factor` (REAL)
- `calmar_ratio` (REAL)
- `total_commission` (REAL)
- `total_slippage` (REAL)
- `equity_curve` (TEXT, JSON array of equity curve values over time, e.g. `[10000.0, 10050.0, 10020.0]`)
- `created_at` (TEXT, insertion time in UTC)
**Consequences (testable):**
- Writing a backtest result with strategy parameters and equity curve maps successfully to the SQLite columns.

---

### 4.2 CLI Consultation and Filtering
**Description:** Users can query the SQLite database via CLI flags.

**Functional Requirements:**

#### FR-3: CLI Query Options
The `RunBacktest` CLI supports `--query` along with filters:
- `--query`: Execute query mode.
- `--symbol <SYMBOL>`: Filter by asset symbol.
- `--strategy <STRATEGY_ID>`: Filter by strategy ID.
- `--min-profit-factor <VALUE>`: Filter by minimum profit factor.
- `--min-sharpe <VALUE>`: Filter by minimum Sharpe Ratio.
- `--sort-by <METRIC>`: Sort by `sharpe`, `profit_factor`, `pnl`, or `drawdown`.
- `--limit <N>`: Limit results.
**Consequences (testables):**
- Running `RunBacktest --query --symbol EUR_USD --sort-by sharpe` prints an ASCII table of matching runs sorted by Sharpe ratio.

---

### 4.3 REST API Endpoints
**Description:** Expose endpoints from the Control Plane (`trading-runtime`) to retrieve backtest history.

**Functional Requirements:**

#### FR-4: Query HTTP Endpoint
Expose `GET /api/backtests` returning JSON data of matching runs.
**Consequences (testable):**
- Returning a list of runs filtered by query parameters with HTTP status 200.

---

### 4.4 Desktop App GUI Interface (MVP)
**Description:** A Vue 3 frontend view to search, filter, and inspect backtest runs.

**Functional Requirements:**

#### FR-5: Backtest History View
Provide a view in the Electron app showing backtest runs in a table.
**Consequences (testable):**
- Displays columns: Date, Strategy, Symbol, Parameters, Net Profit, Sharpe Ratio, Profit Factor, Max Drawdown.
- Clicking a row shows detail parameters, metrics, and renders the equity chart and drawdown chart.

#### FR-6: Desktop Filtering & Sorting
Interactive UI filters for Symbol, Strategy, Minimum Sharpe, Minimum Profit Factor, and sorting options.
**Consequences (testable):**
- Typing in the Symbol input or updating the filters dynamically updates the table results.

---

### 4.5 Advanced Analytics & Risk Controls

**Description:** Provide advanced quantitative features to assist traders in robustness evaluation, risk checking, and portfolio configuration.

#### FR-7: Parameter Sensitivity Heatmap
The system must support aggregating multiple historical runs of a single strategy (utilizing parameter keys/values) to visualize parameter robustness.
**Consequences (testable):**
- The Desktop GUI provides a heatmap view plotting variable parameter pairs (e.g., fast period vs. slow period) against target metrics (e.g., Profit Factor or Sharpe Ratio) to distinguish robust parameter regions from isolated, overfitted peaks.

#### FR-8: Live vs. Backtest Drift Monitoring
The system must support correlating live trading execution performance with the corresponding backtest runs via the matching `parameter_hash`.
**Consequences (testable):**
- The system checks if active/live performance metrics (e.g., current drawdown, rolling win rate) significantly deviate from the historical backtest baseline distribution. If a live run exceeds the backtest's maximum drawdown limit or underperforms significantly, it triggers a drift flag.

#### FR-9: Pareto Frontier Scatter Plot
The system must generate a scatter plot of runs for a strategy, mapping risk against reward.
**Consequences (testable):**
- The Desktop GUI plots Max Drawdown on the X-axis and Total Return or Sharpe Ratio on the Y-axis. The GUI visually highlights the "Pareto Frontier" (configurations providing the highest return for each level of risk).

---

## 5. Non-Goals (Explicit)
- **Persisting individual trade logs to DB in v1**: To keep the schema simple, only the summary metrics, parameters, and equity curve are stored in SQLite. Individual trade lists are not stored in SQLite tables for v1.
- **Modifying or deleting runs from the GUI**: The GUI is read-only for v1 (the DB is append-only).

## 6. MVP Scope

### 6.1 In Scope
- Automatic SQLite database creation and schema setup.
- Writing results, parameters, and the equity curve (as JSON) upon backtest completion.
- CLI query commands (`--query`).
- Control Plane REST API endpoints.
- Electron Vue 3 Desktop interface for viewing, filtering, sorting, and charting backtest history (reproducing the equity/drawdown curves).
- Parameter sensitivity heatmap, Pareto Frontier scatter plot, and live performance drift metrics/checks.

### 6.2 Out of Scope for MVP
- Storing individual trade lists or transaction history.
- Editing/deleting backtest records.

## 7. Success Metrics
- **SM-1 (Data Integrity)**: 100% of successfully completed backtests are saved with correct parameters, performance metrics, and the full equity curve.
- **SM-2 (Observability)**: GUI page loads backtest history containing up to 10,000 records in less than 200ms.

## 8. Open Questions
1. **How should we handle Concurrent Writes?**
   - *Proposed Solution*: SQLite WAL mode enabled to support concurrent reading and writing from CLI and Control Plane.

## 9. Assumptions Index
- **Assumption 1**: Database path defaults to `data/runtime/events.db` (managed by RuntimeDataPaths).
- **Assumption 2**: Parameters and equity curve values are stored as serialized JSON text fields.
