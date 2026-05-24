# Story 13.3: ControlPlane HTTP (Javalin) + health/strategies/runs

Status: done

## Story

As a platform developer,
I want an HTTP control plane that exposes health, strategy catalog, run submission, and event replay,
so that clients (TUI, Laravel, scripts) can drive backtests without the CLI and events are persisted for replay.

## Acceptance Criteria

1. **AC1 — Javalin server:** `ControlPlaneServer` in `trading-runtime` on configurable port (default 8080 via `CONTROL_PLANE_PORT`). `ControlPlaneMain` entry point.
2. **AC2 — GET /api/health:** Returns `{ status, version }`.
3. **AC3 — GET /api/strategies:** Lists `StrategyCatalog` entries (id, family, defaultSymbol).
4. **AC4 — POST /api/runs:** Accepts `{ strategyId, symbol, mode, barsSource, capital? }`; returns `202` with `{ runId, status: RUNNING }`; runs async via `RunManager`; persists events to `EventStore`.
5. **AC5 — GET /api/runs/{runId}:** Returns run metadata (status, timestamps, result payload when COMPLETED).
6. **AC6 — GET /api/runs/{runId}/events:** Paginated replay via `afterSequence` + `limit` query params; returns `{ items: [{ sequence, event }], nextAfterSequence }`.
7. **AC7 — Tests + build:** `ControlPlaneServerTest`, `RunManagerTest`; `mvn clean install` green.

## Tasks / Subtasks

- [x] Task 1 (AC1): Add Javalin dep; `ControlPlaneServer`, `ControlPlaneMain`
- [x] Task 2 (AC2–AC3): Health + strategies routes
- [x] Task 3 (AC4): `RunManager`, `RunLauncher`, `BarSourceResolver`, async execution
- [x] Task 4 (AC5–AC6): Run status + events routes; `StoredRunEvent` + `queryWithSequence`
- [x] Task 5 (AC7): Integration tests; full build

## Dev Notes

### API (MVP)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/health` | Liveness + version |
| GET | `/api/strategies` | Catalog list |
| POST | `/api/runs` | Start run (sample or year bars) |
| GET | `/api/runs/{runId}` | Run status + result |
| GET | `/api/runs/{runId}/events` | Paginated event replay |

### POST /api/runs example

```json
{
  "strategyId": "LondonOpenRangeBreakout",
  "symbol": "EUR_USD",
  "mode": "BACKTEST",
  "barsSource": { "type": "sample", "count": 500 },
  "capital": 100000
}
```

`barsSource.type`: `sample` | `year` (year requires `year` field).

### Hors scope (13.3)

- WebSocket (13.4)
- promote / kill (13.5+)
- DeploymentStore
- LIVE mode

### Démarrage

```bash
mvn exec:java -pl trading-runtime
# or
CONTROL_PLANE_PORT=8080 mvn exec:java -pl trading-runtime
```

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes

- `RunManager` runs backtests on virtual threads; events appended to `EventStore` via `RunLauncher`.
- Extended `EventStore` with `queryWithSequence` for cursor pagination.
- Dependencies: `trading-strategies`, `trading-data`, Javalin 6.6.0.

### File List

- pom.xml (root — javalin dependencyManagement)
- trading-runtime/pom.xml
- trading-runtime/src/main/java/.../ControlPlaneServer.java
- trading-runtime/src/main/java/.../ControlPlaneMain.java
- trading-runtime/src/main/java/.../RunManager.java
- trading-runtime/src/main/java/.../RunLauncher.java
- trading-runtime/src/main/java/.../RunRecord.java
- trading-runtime/src/main/java/.../BarSourceResolver.java
- trading-runtime/src/main/java/.../StoredRunEvent.java
- trading-runtime/src/main/java/.../EventStore.java (queryWithSequence)
- trading-runtime/src/main/java/.../InMemoryEventStore.java
- trading-runtime/src/main/java/.../SqliteEventStore.java
- trading-runtime/src/test/java/.../ControlPlaneServerTest.java
- trading-runtime/src/test/java/.../RunManagerTest.java
