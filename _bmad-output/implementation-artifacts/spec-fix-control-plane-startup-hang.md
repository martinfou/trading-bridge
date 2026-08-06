---
title: 'Fix Control Plane Startup Hang'
type: 'bugfix'
created: '2026-08-06'
status: 'approved'
context: ['_bmad-output/project-context.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Control plane fails to start properly or hangs indefinitely due to `stdinWatcher` process suspension (`SIGTTIN`) when executed without an interactive console/TTY, compounded by synchronous `PRAGMA integrity_check` on 3.36 GB database and synchronous reconciliation/restoration loops prior to HTTP port binding.

**Approach:** Prevent `stdinWatcher` execution when `System.console() == null`, remove synchronous `PRAGMA integrity_check` from `SqliteEventStore` constructor, and execute `restoreActiveRuns` and `reconcileCompletedRuns` asynchronously in a background thread after `ControlPlaneServer` starts listening. Guard mutation endpoints with 503 while reconciliation is in progress, and report reconciliation status in `/api/health`.

## Boundaries & Constraints

**Always:** Ensure Javalin server binds to HTTP port immediately on startup. Keep `/api/diagnostics/integrity` fully functional for on-demand integrity checks. Expose reconciliation status on `/api/health`.

**Ask First:** Any architectural changes to database schemas or breaking API changes.

**Never:** Break existing `RunManager` state transition contracts or disable error logging for failed restorations. Allow run mutations while background reconciliation is `IN_PROGRESS`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Headless / Background Startup | Run in background without TTY (`System.console() == null`) | Server starts immediately, binds to port 8080 without `SIGTTIN` freeze | Stdin watcher thread skipped safely |
| Large SQLite Database Launch | `events.db` size > 1GB | Constructor completes in <100ms; integrity check deferred to on-demand endpoint | Log error if DB cannot be opened |
| Active Runs Restoration | Active runs present in DB on startup | Server binds port first; active runs restored asynchronously in background | Log individual run restoration failures without stopping server |
| Health Check During Recovery | `GET /api/health` during recovery | Returns `200 OK` with JSON `{"status": "UP", "reconciliation": "IN_PROGRESS"}` | N/A |
| Mutation Request During Recovery | `POST /api/runs` during `IN_PROGRESS` recovery | Returns `503 Service Unavailable` with error payload | Fast rejection to prevent state corruption |

</frozen-after-approval>

## Code Map

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneMain.java` -- Main entry point; stdin watcher configuration and async startup loop execution
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java` -- Active run state management; track reconciliation state (`AtomicReference<ReconciliationState>`) and guard mutations
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java` -- Javalin HTTP server; `/api/health` payload update and 503 guard on run mutation endpoints
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/SqliteEventStore.java` -- SQLite event store constructor; remove blocking startup `PRAGMA integrity_check`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlPlaneServerTest.java` -- Server startup unit and integration tests

## Tasks & Acceptance

**Execution:**
- [x] `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneMain.java` -- Guard `stdinWatcher` startup with `System.console() != null` and move `restoreActiveRuns` and `reconcileCompletedRuns` to async background thread after `app.start(port)` -- Prevents process freeze and port binding delay
- [x] `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java` -- Add `reconciliationState` (`IDLE`, `IN_PROGRESS`, `COMPLETED`, `FAILED`), state getters, and mutation guard check
- [x] `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java` -- Expose reconciliation status on `/api/health` and return 503 Service Unavailable on run mutations during `IN_PROGRESS`
- [x] `trading-runtime/src/main/java/com/martinfou/trading/runtime/SqliteEventStore.java` -- Remove synchronous `checkDatabaseIntegrity(connection)` call from constructor -- Eliminates multi-minute disk IO scan on 3.36 GB DB startup

**Acceptance Criteria:**
- Given control plane launched in background without TTY, when `ControlPlaneMain.main()` runs, then server binds port 8080 immediately and responds to `/api/health`.
- Given `GET /api/health` called during background recovery, then response is 200 OK with `"reconciliation": "IN_PROGRESS"`.
- Given run mutation API endpoint invoked while reconciliation is `IN_PROGRESS`, then server responds with `503 Service Unavailable`.
- Given a large `events.db` file, when `SqliteEventStore` is initialized, then constructor returns immediately without running blocking `PRAGMA integrity_check`.

## Verification

**Commands:**
- `./mvnw test -pl trading-runtime` -- expected: BUILD SUCCESS
- `./mvnw -q -pl trading-runtime -am clean install -DskipTests` -- expected: BUILD SUCCESS

