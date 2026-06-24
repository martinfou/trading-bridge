# Story 26.7: Compare Persistent Backtests in GUI

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a trader,
I want to select and compare completed backtest runs from previous sessions or CLI runs in the Compare tab,
so that I can evaluate their performance metrics side-by-side without them disappearing when the control plane restarts.

## Acceptance Criteria

1. **AC1 — Unified Runs Listing:** The Compare tab's selection list in [CompareView.vue](file:///Volumes/T7/src/trading-bridge/desktop/src/views/CompareView.vue) lists all transient active runs as well as historical backtests persisted in the SQLite `backtest_runs` database table.
2. **AC2 — Persistent Detail Resolution:** Selecting a completed persistent backtest run in the Compare tab successfully fetches its details via `GET /api/runs/{runId}`. The backend must fall back to the SQLite store to retrieve completed backtests when they are not in the `RunManager` memory.
3. **AC3 — Persistent Trades Resolution:** Selecting a completed persistent backtest run successfully fetches its trades via `GET /api/runs/{runId}/trades` by replaying the events associated with that run from the `SqliteEventStore`.
4. **AC4 — Persistent Equity Curve Resolution:** Selecting a completed persistent backtest run successfully fetches its equity curve via `GET /api/runs/{runId}/equity-curve` by loading and parsing the persisted equity curve array from the SQLite database.
5. **AC5 — UI Data Compatibility:** The Compare tab renders the KPI table and the equity curve chart correctly for both active and persisted backtests.

## Tasks / Subtasks

- [x] Task 1: Unify runs retrieval in the Control Plane (AC: 1)
  - [x] Update `GET /api/runs` in [ControlPlaneServer.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java) to fetch completed backtests from [SqliteBacktestRunStore.java](file:///Volumes/T7/src/trading-bridge/trading-backtest/src/main/java/com/martinfou/trading/backtest/persistence/SqliteBacktestRunStore.java) and merge them with active runs from `RunManager`.
  - [x] Deduplicate runs by `runId` and sort them by date (completedAt or startedAt).
- [x] Task 2: Implement persistent fallback for run details (AC: 2)
  - [x] Update `GET /api/runs/{runId}` in [ControlPlaneServer.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java) to query `SqliteBacktestRunStore` when the run is not found in `RunManager`'s memory.
  - [x] Map the SQL `BacktestRunDetails` object into the expected `RunRecord` JSON format (with configuration snapshot and KPIs inside `"result"`).
- [x] Task 3: Implement persistent fallback for trades and equity curve (AC: 3, 4)
  - [x] Update `GET /api/runs/{runId}/trades` to fallback to replaying events via `eventStore.replayAll(runId)` if the run is not found in memory, reconstructing the trade list.
  - [x] Update `GET /api/runs/{runId}/equity-curve` to check `SqliteBacktestRunStore` and deserialize the JSON string in the `equity_curve` column into a numeric array if the run is not in memory.
- [x] Task 4: Verify Compare tab compatibility (AC: 5)
  - [x] Verify that selecting a mix of active/transient and persistent backtest runs displays correctly in the GUI Compare tab.
  - [x] Check console logs and ensure no 404 errors are thrown when selecting historical backtests.

### Review Findings

- [x] [Review][Patch] Thread Leak on Constructor Failures & Connection Leak on Close Errors [trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java:45-52]
- [x] [Review][Patch] Database Connection Sharing in Concurrent / Multi-Threaded Context [trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java]
- [x] [Review][Patch] Inefficient Sorting & Lexicographical Sorting optimization in /api/runs [trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java:637-649]
- [x] [Review][Patch] Incomplete Runs Sorting and Missing startedAt field in /api/runs [trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java:604-650]
- [x] [Review][Patch] NPE and ClassCastException in Parameter Mapping [trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java:692-730]
- [x] [Review][Patch] Test Database Path Pollution in ControlPlaneServerTest [trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlPlaneServerTest.java:45]
- [x] [Review][Patch] Unused Import in ControlPlaneServer.java [trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java]
- [x] [Review][Defer] Unbounded Database Queries in /api/runs [trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java:620] — deferred, pre-existing
- [x] [Review][Defer] Missing Historical Fallback for export, monte-carlo, bars, events endpoints [trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java] — deferred, outside direct scope
- [x] [Review][Defer] NullPointerException Risk in SqliteBacktestRunStore.insert() [trading-backtest/src/main/java/com/martinfou/trading/backtest/persistence/SqliteBacktestRunStore.java] — deferred, pre-existing

### Review Follow-ups (AI)


- [x] [AI-Review] Fix `/api/runs` sorting NPE by handling null `completedAt` correctly for active runs (AC1).
- [x] [AI-Review] Add 404 validation to `/api/runs/{runId}/trades` to throw `NotFoundException` if the run is not found in memory AND not found in the persistent store (AC3).
- [x] [AI-Review] Format the equity curve response from `/api/runs/{runId}/equity-curve` correctly (AC4/AC5).
- [x] [AI-Review] Fix database connection thrashing by making `SqliteBacktestRunStore` usage more efficient.
- [x] [AI-Review] Use a shared `static final` `ObjectMapper` instead of instantiating new mapper objects per request.
- [x] [AI-Review] Guard against unchecked casts and null pointer exceptions in JSON deserialization.
- [x] [AI-Review] Ensure test assertions delete created records even if tests fail, and use a test database path rather than production database path.

## Dev Notes

- **Backend Fallback:** The backend should transparently redirect queries to the SQLite store (located at the path resolved by `BacktestPersistenceService.resolveDefaultDbPath()`) if the requested `runId` is not present in `RunManager.runs`.
- **Database Schema Reference:** The `backtest_runs` schema and query parameters are located in [SqliteBacktestRunStore.java](file:///Volumes/T7/src/trading-bridge/trading-backtest/src/main/java/com/martinfou/trading/backtest/persistence/SqliteBacktestRunStore.java).
- **Jackson Deserialization:** The `equity_curve` in SQLite is stored as a JSON string (e.g. `"[10000.0, 10050.0]"`). Use `ObjectMapper` to parse it to a `List<Double>` before returning it in `/api/runs/{runId}/equity-curve`.

### References

- Backtest persistence specification: [_bmad-output/planning-artifacts/prds/prd-backtest-persistence-2026-06-06/prd.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/prds/prd-backtest-persistence-2026-06-06/prd.md)
- Event store database logic: [SqliteEventStore.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/SqliteEventStore.java)
- Control plane API client wrapper: [useControlPlane.ts](file:///Volumes/T7/src/trading-bridge/desktop/src/composables/useControlPlane.ts)

## Dev Agent Record

### Agent Model Used

Gemini 3.5 Flash

### Debug Log References

### Completion Notes List

- Defined the missing `org.slf4j.Logger` variable and the `toRunJsonFromDetails` mapping helper method in [ControlPlaneServer.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java) to resolve compilation failures and support persistent run loading.
- Added a new integration test `getRun_fallbackToPersistentDb` in [ControlPlaneServerTest.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlPlaneServerTest.java) to validate the database fallback mechanism for `/api/runs/{runId}` and `/api/runs/{runId}/equity-curve`.
- Verified 100% success of 175 tests in the `trading-runtime` module.

### File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlPlaneServerTest.java`

## Senior Developer Review (AI)

- **Review Outcome**: Changes Requested
- **Review Date**: 2026-06-16
- **Priorities**: 3 High, 4 Medium, 0 Low

### Action Items
- [x] Fix `/api/runs` sorting NPE when active runs have null `completedAt`.
- [x] Throw `NotFoundException` in `/api/runs/{runId}/trades` if the run does not exist anywhere.
- [x] Verify/format equity curve response for persistent runs to ensure UI compatibility.
- [x] Refactor `SqliteBacktestRunStore` usage to avoid opening/closing DB connection on every API call.
- [x] Use a single static final `ObjectMapper` instead of instantiating per-request.
- [x] Guard JSON parsing against unchecked class casts or null pointers.
- [x] Update tests to clean up DB records cleanly and avoid modifying production database.
