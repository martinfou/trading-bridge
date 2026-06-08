# Story 26.2: Backtest Persistence Service & Auto-Save

Status: done

## Story

As a developer,
I want to implement a service that serializes backtest details and automatically saves completed backtests,
so that every successful run is immediately captured in history.

## Acceptance Criteria

1. **Given** a `BacktestResult` and a Map of parameters
   **When** `BacktestPersistenceService` processes them
   **Then** it computes a deterministic parameter hash by sorting the parameter keys alphabetically, serializing the sorted map to JSON, and applying a SHA-256 hash.

2. **Given** a finished backtest
   **When** the backtest engine successfully completes a simulation (via CLI or HTTP)
   **Then** the auto-save hook in `BacktestPersistenceService` is invoked to persist the run to the SQLite database.
   **And** any error during save does not crash the backtest process itself (fails gracefully with an ERROR log).

3. **Given** an equity curve array of double values
   **When** saving the result
   **Then** it is serialized to a standard JSON array string (e.g. `[10000.0, 10050.0, ...]`) using Jackson.

## Tasks / Subtasks

- [ ] **Task 1: Deterministic Hashing & Serialization (AC 1, AC 3)**
  - [ ] Implement `BacktestPersistenceService` in `com.martinfou.trading.backtest.persistence`.
  - [ ] Implement `computeParameterHash(Map<String, Object> parameters)` using SHA-256.
  - [ ] Implement JSON conversion for parameters and equity curve array using Jackson.
- [ ] **Task 2: Auto-Save Wiring in Engine (AC 2)**
  - [ ] Identify where backtest runs finish (e.g. in `BacktestEngine` or CLI launcher / run managers).
  - [ ] Wire the call to `BacktestPersistenceService.saveResult(...)` so it is automatically triggered when a run completes.
  - [ ] Wrap the save call in a try-catch block to ensure any persistence failure (e.g. database lock) is logged but does not disrupt the backtest lifecycle.
- [ ] **Task 3: Unit Tests (AC 1, 2, 3)**
  - [ ] Create `BacktestPersistenceServiceTest`.
  - [ ] Assert that identical configurations yield identical parameter hashes, while different configurations yield different hashes.
  - [ ] Verify serialization format matches the expected schema.

## Dev Notes

- Avoid hardcoded database credentials. Reuse the database connection/path or inject it.
- Log success messages: `[BacktestPersistence] Saved run {} for strategy {} (hash: {})` at INFO level.
- Log failure messages: `[BacktestPersistence] Failed to persist backtest run {}: {}` at ERROR level with stacktrace.
