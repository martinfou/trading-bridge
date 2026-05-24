# Story 13.2: EventStore SQLite + Replay API

Status: done

## Story

As a platform developer,
I want a durable, append-only EventStore backed by SQLite with a replay API,
so that run events can be queried and replayed in order without coupling to HTTP or RunContext wiring yet.

## Acceptance Criteria

1. **AC1 — Module scaffold:** New Maven module `trading-runtime` listed in root `pom.xml`. Depends on `trading-backtest` (RunEvent/RunEventJson) and `org.xerial:sqlite-jdbc` (version in parent `dependencyManagement`). `mvn clean install -pl trading-runtime` succeeds.
2. **AC2 — EventStore contract:** Interface exposes:
   - `long append(String runId, RunEvent event)` — returns global `sequence`
   - `List<RunEvent> query(String runId, long afterSequence, int limit)` — `sequence > afterSequence`, ascending, capped; `limit <= 0` → `IllegalArgumentException`
   - `long count(String runId)`
   - `List<RunEvent> replayAll(String runId)` — full ordered history for run
   - Rejects null/blank `runId`, null `event`
3. **AC3 — InMemoryEventStore:** Unit tests prove pagination, multi-run isolation, monotonic global sequences, accurate `count` and `replayAll`.
4. **AC4 — SqliteEventStore persistence:** Events survive close/reopen; queries identical after restart.
5. **AC5 — Schema + JSON round-trip:** Stored `json_line` = exact `RunEventJson.toJsonLine(event)`; read back via `fromJsonLine()` matches original. Schema:

   | Column | Type | Notes |
   |--------|------|-------|
   | `sequence` | INTEGER PK AUTOINCREMENT | Global ordering |
   | `run_id` | TEXT NOT NULL | Indexed |
   | `json_line` | TEXT NOT NULL | JSONL v1 line |
   | `created_at` | TEXT NOT NULL | ISO-8601 UTC insert time |

   Index: `idx_events_run_sequence ON events(run_id, sequence)`.
6. **AC6 — Configuration:** `EventStoreConfig` with explicit path override; default dev path `{repoRoot}/data/runtime/events.db` (create parent dirs); fallback `~/.trading-bridge/events.db` when repo root unknown.

## Tasks / Subtasks

- [x] Task 1 (AC1): Add `trading-runtime/pom.xml`; register module; parent `dependencyManagement` for sqlite-jdbc 3.49.1.0
- [x] Task 2 (AC2): `EventStore` interface + `EventStores` factory + validation
- [x] Task 3 (AC3): `InMemoryEventStore` + `InMemoryEventStoreTest`
- [x] Task 4 (AC4, AC5): `SqliteEventStore` — DDL on init, WAL optional, close/reopen
- [x] Task 5 (AC6): `EventStoreConfig` path resolution
- [x] Task 6 (AC4, AC5): `SqliteEventStoreTest` with temp DB file
- [x] Task 7 (AC1): `mvn clean install` from repo root

## Dev Notes

### Party mode — décisions verrouillées (2026-05-23)

| # | Sujet | Décision |
|---|--------|----------|
| 1 | Périmètre | `trading-runtime` = EventStore only ; pas HTTP (13.3), pas WS (13.4), pas RunManager |
| 2 | Persistance | SQLite JDBC append-only ; PostgreSQL = story future |
| 3 | Format | `RunEventJson.toJsonLine()` / `fromJsonLine()` — pas de re-modèle |
| 4 | Séquence | BIGINT global auto-incrément ; index `(run_id, sequence)` pour cursor API 13.3 |
| 5 | Implémentations | `InMemoryEventStore` (unit) + `SqliteEventStore` (integration) |
| 6 | Chemin DB | Dev: `data/runtime/events.db` ; fallback `~/.trading-bridge/events.db` |
| 7 | Wiring | **Pas** de listener RunContext en 13.2 — RunManager en 13.3 |

### Module structure

```
trading-runtime/
├── pom.xml
└── src/
    ├── main/java/com/martinfou/trading/runtime/
    │   ├── EventStore.java
    │   ├── EventStoreConfig.java
    │   ├── EventStores.java
    │   ├── InMemoryEventStore.java
    │   └── SqliteEventStore.java
    └── test/java/com/martinfou/trading/runtime/
        ├── InMemoryEventStoreTest.java
        └── SqliteEventStoreTest.java
```

**Reactor:** insert `<module>trading-runtime</module>` after `trading-backtest`.

### Interface sketch

```java
public interface EventStore extends AutoCloseable {
    long append(String runId, RunEvent event);
    List<RunEvent> query(String runId, long afterSequence, int limit);
    long count(String runId);
    List<RunEvent> replayAll(String runId);
    @Override void close();
}
```

### DDL

```sql
CREATE TABLE IF NOT EXISTS events (
  sequence    INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id      TEXT    NOT NULL,
  json_line   TEXT    NOT NULL,
  created_at  TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_run_sequence ON events(run_id, sequence);
```

### Dependencies

| Artifact | Scope |
|----------|-------|
| `trading-backtest` | compile |
| `sqlite-jdbc` | compile |
| `junit-jupiter` | test |

### Hors scope (13.2)

- Javalin REST `GET /api/runs/{runId}/events` (13.3)
- WebSocket broadcast (13.4)
- RunContext listener wiring (13.3)
- PostgreSQL, retention, migrations framework, auth

### Vérification

```bash
mvn clean install
mvn test -pl trading-runtime
# optional: sqlite3 data/runtime/events.db 'SELECT sequence, run_id FROM events LIMIT 5;'
```

### Références

- [ADR-13-02: architecture-epic-13-platform-runtime.md]
- [Prior: 12-5-run-event-stream-jsonl.md, 13-1-decouple-runcontext-catalog-factory.md]
- [Source: trading-backtest/.../events/RunEventJson.java]

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes

- New `trading-runtime` module with `EventStore`, `InMemoryEventStore`, `SqliteEventStore`, `EventStoreConfig`, `EventStores`.
- SQLite stores exact `RunEventJson.toJsonLine()` payloads; global `sequence` for cursor pagination (13.3).
- Default DB path resolves to repo `data/runtime/events.db`; `*.db` gitignored.
- 9 new tests; full reactor build green.

### File List

- pom.xml (root — module + sqlite-jdbc dependencyManagement)
- trading-runtime/pom.xml
- trading-runtime/src/main/java/com/martinfou/trading/runtime/EventStore.java
- trading-runtime/src/main/java/com/martinfou/trading/runtime/EventStoreConfig.java
- trading-runtime/src/main/java/com/martinfou/trading/runtime/EventStores.java
- trading-runtime/src/main/java/com/martinfou/trading/runtime/EventStoreValidation.java
- trading-runtime/src/main/java/com/martinfou/trading/runtime/InMemoryEventStore.java
- trading-runtime/src/main/java/com/martinfou/trading/runtime/SqliteEventStore.java
- trading-runtime/src/test/java/com/martinfou/trading/runtime/InMemoryEventStoreTest.java
- trading-runtime/src/test/java/com/martinfou/trading/runtime/SqliteEventStoreTest.java
- data/runtime/.gitkeep
- .gitignore (data/runtime/*.db)
