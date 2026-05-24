# Story 13.4: WebSocket RunEvent Broadcast

Status: done

## Story

As a platform developer,
I want WebSocket streaming of RunEvents per run with replay on connect,
so that TUI and dashboard clients receive live backtest progress without polling.

## Acceptance Criteria

1. **AC1 — WebSocket endpoint:** `WS /ws/runs/{runId}` on `ControlPlaneServer`; rejects unknown runId with close code 4404.
2. **AC2 — Replay on connect:** Sends all persisted events (from `EventStore`) in sequence order before live subscription.
3. **AC3 — Live broadcast:** New events appended during run are pushed to subscribed clients via `RunEventHub`.
4. **AC4 — Message format:** Each frame is JSON `{ "sequence": N, "event": { ... RunEvent v1 ... } }` (matches REST events item shape).
5. **AC5 — BroadcastingEventStore:** Decorator wraps `EventStore.append` to publish to hub after persist.
6. **AC6 — Tests + build:** `RunEventHubTest`, `BroadcastingEventStoreTest`, `ControlPlaneServerTest.webSocket_replaysRunEvents`; `mvn clean install` green.

## Tasks / Subtasks

- [x] Task 1 (AC5): `RunEventHub`, `BroadcastingEventStore`, `RuntimeStores`
- [x] Task 2 (AC1–AC4): WebSocket handler in `ControlPlaneServer`; `RunEventMessages`
- [x] Task 3 (AC6): Unit + WS integration tests; wire `ControlPlaneMain`

## Dev Notes

### Usage

```bash
mvn exec:java -pl trading-runtime
# ws://localhost:8080/ws/runs/{runId}
```

1. `POST /api/runs` → get `runId`
2. Connect WebSocket (replay sent immediately; live events follow if run still active)

### Hors scope

- `/ws/dashboard` fan-in (later)
- Auth, heartbeats (13.8)

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes

- `BroadcastingEventStore` publishes after delegate append.
- WS replays up to 1000 events on connect, then subscribes to hub.
- Tests use JDK `HttpClient` WebSocket API.

### File List

- trading-runtime/.../RunEventHub.java
- trading-runtime/.../BroadcastingEventStore.java
- trading-runtime/.../RunEventMessages.java
- trading-runtime/.../RuntimeStores.java
- trading-runtime/.../ControlPlaneServer.java
- trading-runtime/.../ControlPlaneMain.java
- trading-runtime/src/test/.../RunEventHubTest.java
- trading-runtime/src/test/.../BroadcastingEventStoreTest.java
- trading-runtime/src/test/.../ControlPlaneServerTest.java
