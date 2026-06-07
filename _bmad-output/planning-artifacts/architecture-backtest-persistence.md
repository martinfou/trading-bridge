---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
inputDocuments:
  - "file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-backtest-persistence-2026-06-06/prd.md"
  - "file:///Volumes/T7/src/trading-bridge/_bmad-output/project-context.md"
workflowType: 'architecture'
project_name: 'Trading Bridge'
user_name: 'Martin Fournier'
date: '2026-06-06'
lastStep: 8
status: 'complete'
completedAt: '2026-06-06'
---

# Architecture Decision Document

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

## Project Context Analysis

### Requirements Overview

**Functional Requirements:**
- **FR-1: Automatic Save:** Automatically save every completed backtest run (from CLI or Control Plane) to the database.
- **FR-2: SQLite Schema:** Define the `backtest_runs` table with columns for run metadata, metrics, serialized parameter JSON, serialized equity curve JSON, and the computed parameter hash.
- **FR-3 & FR-4: CLI & API Queries:** CLI flags (`--query`, `--symbol`, `--sort-by`) and REST endpoint `GET /api/backtests` to retrieve and filter runs.
- **FR-5 & FR-6: Desktop App View:** Electron GUI to search, filter, and drill down into runs.
- **FR-7, FR-8, FR-9: Advanced Analytics:** Parameter sensitivity heatmaps, Pareto Frontier charts, and live performance drift checks comparing live runs to historical backtest baselines.

**Non-Functional Requirements:**
- **Concurrency & WAL:** SQLite WAL (Write-Ahead Logging) to allow concurrent writes (from CLI/runtime) and reads (from API/GUI).
- **Fast Pagination & Querying:** Performance of <200ms response time under 10,000 runs.
- **Data Completeness:** Parameter hashes must be deterministic (sorted key JSON) to allow configuration grouping.

**Scale & Complexity:**
- Primary domain: API, Backend (Java 21, SQLite, REST) and GUI (Vue 3, TypeScript, Vite).
- Complexity level: Medium-High (due to advanced analytics calculations like heatmaps, Pareto Frontier, and drift calculations).
- Estimated architectural components: 4 components (Persistence Coordinator, CLI Query Engine, REST API Controller, GUI Dashboard & Charts Component).

### Technical Constraints & Dependencies
- Must reuse the existing SQLite connection/path from `RuntimeDataPaths.defaultEventStorePath()`.
- Acyclic compilation must be preserved.
- No new external frameworks (no Spring, no Lombok).

### Cross-Cutting Concerns Identified
- **Serialization Performance:** Serializing large double arrays (`equityCurve`) to JSON strings efficiently.
- **Database Schema Migration:** Creating/updating the `backtest_runs` table automatically if it doesn't exist.
- **Parameter Serialization Consistency:** Ensuring the JSON parameters are sorted deterministically so identical configurations yield identical hashes.

## Starter Template Evaluation

### Primary Technology Domain
Integrated Backend/Frontend features within the existing **Trading Bridge** monorepo:
- **Backend:** Java 21, Maven, JUnit 5, SLF4J, and Jackson.
- **Database:** SQLite (Write-Ahead Logging enabled, using `events.db` file).
- **Frontend/GUI:** Electron desktop app in `desktop/` (Vue 3 + Vite + TypeScript).

### Starter Options Considered
1. **Existing Monorepo Architecture ("Trading Bridge"):** [Selected]
   - *Rationale:* Seamless integration, zero network overhead (runs in-memory during backtests), reuses the existing `events.db` instance, and directly compiles with Maven/npm.
   - *Initial Command:* None required (brownfield project).

### Architectural Decisions Provided by Starter:

**Language & Runtime:**
- Java 21 (JDK 21) for execution and persistence logic.
- Node.js & TypeScript for GUI charts and filters.

**Styling Solution:**
- Vanilla CSS + Tailwind/custom styling inside the Vue 3 Electron application.

**Build Tooling:**
- Maven for the Java backend modules.
- Vite and npm packaging in the `desktop/` module.

**Testing Framework:**
- JUnit 5 for backend Java persistence tests.

**Code Organization:**
- **Database Tables & Queries:** Table setup and queries go into `trading-backtest` or a persistence coordinator inside `trading-runtime`.
- **API & REST Controller:** `trading-runtime` HTTP endpoints.
- **Command Line Interface:** `trading-examples` (`RunBacktest`).
- **Frontend Views:** `desktop/src/` for Vue views and charting logic.

## Core Architectural Decisions

### Decision Priority Analysis

**Critical Decisions (Block Implementation):**
- **Database Choice:** SQLite reusing the database path managed by `RuntimeDataPaths.defaultEventStorePath()` (`events.db`). WAL mode enabled.
- **Data Serialization:** Input parameters and equity curves are serialized as JSON strings via Jackson and stored in standard `TEXT` fields.
- **Parameter Hashing:** Deterministic hashing. Keys are sorted alphabetically, serialized to JSON, and hashed using SHA-256 to uniquely identify parameter sets.

**Important Decisions (Shape Architecture):**
- **Analytics Computation Location:** Computations for parameter heatmaps, Pareto Frontiers, and live vs. backtest drift checks are executed on the Java backend to keep the API payload and frontend lightweight.
- **Database Migrations:** Executed dynamically upon application startup (`CREATE TABLE IF NOT EXISTS`).

**Deferred Decisions (Post-MVP):**
- Storing individual trade execution arrays in the SQLite DB (remains out of scope; only summary metrics and equity curves are persisted).

### Data Architecture
- SQLite `backtest_runs` table containing run metadata, metrics, and JSON fields for parameters and equity curve.
- Deterministic hashing via SHA-256 for configuration groupings.

### API & Communication Patterns
- REST endpoints in `ControlPlaneMain` (`trading-runtime`):
  - `GET /api/backtests` (filtered listing)
  - `GET /api/backtests/{run_id}` (detailed view with equity curve)
  - `GET /api/backtests/analytics/heatmap` (heatmap matrix)
  - `GET /api/backtests/analytics/pareto` (risk-reward points)
- CLI Flags integrated into `RunBacktest`.

### Frontend Architecture
- Electron App (Vue 3) dashboard showing history list, detailed charts (using `Chart.js`), and parameter analysis heatmaps/scatter plots.

### Infrastructure & Deployment
- File-based SQLite on the running instance, zero external deployment steps required.
- SQLite WAL mode is enabled on the database helper.

### Decision Impact Analysis

**Implementation Sequence:**
1. Implement helper class in `trading-backtest` or `trading-runtime` to initialize table and compute parameter hashes.
2. Update backtest engine execution hooks to automatically save backtest runs upon completion.
3. Expose REST endpoints in the runtime control plane.
4. Implement CLI query features.
5. Build the Electron Vue 3 views and charting.

## Implementation Patterns & Consistency Rules

### Pattern Categories Defined

**Critical Conflict Points Identified:**
5 zones of potential inconsistency identified for database column naming, REST parameter mapping, JSON serialization formats, serialization standards, and concurrency limits.

### Naming Patterns

**Database Naming Conventions:**
- **Table Name:** `backtest_runs` (lowercase, plural, snake_case).
- **Column Names:** snake_case (e.g., `run_id`, `strategy_id`, `sharpe_ratio`, `parameter_hash`, `equity_curve`).

**API Naming Conventions:**
- **Endpoint:** `/api/backtests` (plural resource, lowercase).
- **Route Parameter:** `/api/backtests/{runId}` (camelCase path variables).
- **Query Parameters:** camelCase (e.g., `?strategyId=SmaCrossover&minSharpe=1.5`).

**Code Naming Conventions (Java):**
- **Persistence Store Class:** `SqliteBacktestRunStore` to perform low-level SQL operations.
- **Service Class:** `BacktestPersistenceService` to orchestrate hash computation, JSON parsing, and CLI/API integration.
- **DTOs:** `BacktestRunSummary` (for list view) and `BacktestRunDetails` (for chart view).

### Structure Patterns
- **SQL Init Script:** Embed DDL statements directly in `SqliteBacktestRunStore` as static strings (`CREATE TABLE IF NOT EXISTS...`).
- **CLI Queries:** All parsing of `--query` options reside inside `RunBacktest` CLI parser class.

### Format Patterns
- **JSON Serialization:** camelCase keys for parameter maps and responses.
- **Temporal Standard:** Use `java.time.Instant` everywhere on backend, serialized as ISO-8601 UTC string (`YYYY-MM-DDTHH:MM:SSZ`).
- **Numbers:** Double precision (`REAL`) stored for all financial metrics (PnL, Drawdown, Sharpe).

### Communication & Logging Patterns
- **Events & Hooks:** The backtest engine raises a completion event or invokes a callback hook: `BacktestPersistenceService.saveResult(BacktestResult result, Map<String, Object> parameters)`.
- **Logging Style:** 
  - Successful save: `[BacktestPersistence] Saved run {} for strategy {} (hash: {})` (INFO)
  - Error: `[BacktestPersistence] Failed to persist backtest run {}: {}` (ERROR + stacktrace)

### Process Patterns (Concurrency & Writes)
- **SQLite Concurrency Rule:** Because SQLite allows multiple readers but a single writer, all write operations (`INSERT`) into `backtest_runs` are sequentially executed within synchronized blocks or a single-writer thread executor.

## Project Structure & Boundaries

### Complete Project Directory Structure
The files and directories to be created/modified are organized as follows:

```
trading-bridge/
├── trading-backtest/
│   └── src/
│       ├── main/
│       │   └── java/
│       │       └── com/
│       │           └── martinfou/
│       │               └── trading/
│       │                   └── backtest/
│       │                       └── persistence/
│       │                           ├── SqliteBacktestRunStore.java    # Handles SQLite queries & schema creation
│       │                           ├── BacktestPersistenceService.java # Orchestrates hashing and serialization
│       │                           ├── BacktestRunSummary.java         # DTO for list queries (excludes equity curve)
│       │                           └── BacktestRunDetails.java         # DTO with full metrics & equity curve
│       └── test/
│           └── java/
│               └── com/
│                   └── martinfou/
│                       └── trading/
│                           └── backtest/
│                               └── persistence/
│                                   └── SqliteBacktestRunStoreTest.java # JUnit tests for DDL & CRUD
├── trading-runtime/
│   └── src/
│       └── main/
│           └── java/
│               └── com/
│                   └── martinfou/
│                       └── trading/
│                           └── runtime/
│                               └── controller/
│                                   └── BacktestController.java         # REST endpoints GET /api/backtests/*
├── trading-examples/
│   └── src/
│       └── main/
│           └── java/
│               └── com/
│                   └── martinfou/
│                       └── trading/
│                           └── examples/
│                               └── RunBacktest.java                    # Updated to support --query flags
└── desktop/
    └── src/
        ├── views/
        │   └── BacktestHistoryView.vue                                  # Main history board & search filters
        └── components/
            ├── BacktestDetailsPanel.vue                                # Sidebar or modal details drawer
            ├── BacktestEquityChart.vue                                 # Line chart reproducing equity curve/DD
            ├── ParameterSensitivityHeatmap.vue                         # Heatmap for parameter checks
            └── ParetoFrontierChart.vue                                 # Scatter plot for risk vs reward
```

### Architectural Boundaries

**API Boundaries:**
- **Entry Points:** `GET /api/backtests` (list), `GET /api/backtests/{runId}` (details), `GET /api/backtests/analytics/heatmap`, `GET /api/backtests/analytics/pareto`.
- **Query Filter Specs:** All list requests accept `symbol` (String), `strategyId` (String), `minSharpe` (Double), and `minProfitFactor` (Double).

**Component Boundaries:**
- `SqliteBacktestRunStore` encapsulates direct JDBC queries. No other classes write SQL queries.
- `BacktestPersistenceService` manages serialization mappings (Jackson JSON configuration).

**Data Boundaries:**
- SQLite database writes are handled sequentially to avoid locking.
- Sensitive environment variables (`OANDA_API_KEY`) remain isolated and are never written to the sqlite db.

## Architecture Validation Results

### Coherence Validation ✅

**Decision Compatibility:**
- All choices are fully compatible. Reusing the existing SQLite `events.db` in WAL mode allows the CLI and Control Plane to query and insert backtest runs concurrently without lock contention.
- Jackson serialization allows robust JSON mappings of parameters and equity curves.

**Pattern Consistency:**
- Lowercase snake_case naming conventions for the database maps perfectly to the low-level JDBC JDBC queries.
- REST paths are plural and query parameters use camelCase to align with Java naming standards.

**Structure Alignment:**
- The directory layout places persistence code strictly inside the `trading-backtest` module to respect the dependency boundaries of `trading-core`.

### Requirements Coverage Validation ✅

**Functional Requirements Coverage:**
- **FR-1 & FR-2 (Save & Schema):** Covered by `SqliteBacktestRunStore` and `BacktestPersistenceService` using the deterministic parameter hashing.
- **FR-3 & FR-4 (CLI & REST API):** Addressed via custom CLI flags in `RunBacktest` and controllers in `ControlPlaneMain`.
- **FR-5 & FR-6 (Desktop Views):** Addressed via Vue 3 charting views using `Chart.js`.
- **FR-7, FR-8, FR-9 (Analytics & Drift):** Calculated entirely in the Java backend and served via REST API to ensure lightweight payloads and fast response times.

**Non-Functional Requirements Coverage:**
- **Concurrency:** SQLite WAL mode handles concurrent reads/writes.
- **Performance:** Summary statistics query endpoint is separated from the detail endpoint (which loads the large equity curve string) to guarantee <200ms page load speeds.

### Implementation Readiness Validation ✅

**Decision, Structure, and Pattern Completeness:**
- All files, packages, variables, endpoints, and table schemas have been explicitly defined with concrete examples and conflict prevention rules.

### Gap Analysis Results
- **Critical Gaps:** None.
- **Important Gaps:** None.

### Architecture Completeness Checklist
- [x] Project context thoroughly analyzed
- [x] Scale and complexity assessed
- [x] Technical constraints identified
- [x] Cross-cutting concerns mapped
- [x] Critical decisions documented with versions
- [x] Technology stack fully specified
- [x] Integration patterns defined
- [x] Performance considerations addressed
- [x] Naming conventions established
- [x] Structure patterns defined
- [x] Communication patterns specified
- [x] Process patterns documented
- [x] Complete directory structure defined
- [x] Component boundaries established
- [x] Integration points mapped
- [x] Requirements to structure mapping complete

### Architecture Readiness Assessment
- **Overall Status:** READY FOR IMPLEMENTATION
- **Confidence Level:** High
- **Key Strengths:** Reuses existing SQL resources, optimizes database performance by separation of summary/detail queries, and calculates heavy analytics on the backend.
- **Areas for Future Enhancement:** None for MVP.

### Implementation Handoff
- **AI Agent Guidelines:** Follow the conventions exactly. Maintain the dependency hierarchy (do not import `trading-backtest` or `trading-runtime` packages into `trading-core`).
- **First Implementation Priority:** DDL creation inside `SqliteBacktestRunStore` and unit testing of database insertion.
