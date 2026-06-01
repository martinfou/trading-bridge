# Story 21.7 — Hooks runtime santé sqcli et job optionnel

Status: done

Epic: 21 — Pont StrategyQuant CLI

## Story

As Martin,
I want the control plane to expose SQ bridge status and optional job triggers,
so that the TUI/dashboard shows whether SQ integration is alive.

## Acceptance Criteria

- [x] **AC1** — `GET /api/sq-bridge/status` → `{ sqHomeConfigured, sqcliReachable, inboxPendingCount, lastProbeAt }`
- [x] **AC2** — Probe uses harmless sqcli command when SQ_HOME set ; cached result TTL e.g. 60s
- [x] **AC3** — `POST /api/sq-bridge/process-inbox` async ; respects `SqJobMutex`
- [x] **AC4** — Optional `RunEvent` `SQ_EXPORT_RECEIVED` on processed file (additive RunEvent v1)
- [x] **AC5** — TUI command `/inbox` or `/sq` shows pending count + last run summary
- [x] **AC6** — When SQ not configured : 200 with `sqHomeConfigured: false` ; no 500

## Tasks

- [x] `SqBridgeService` + routes in `ControlPlaneServer`
- [x] Background inbox worker thread / executor
- [x] Extend `TuiCommandHandler` + tests
- [x] Wire to `SqInboxProcessor` from 21.2

## Dev Notes

- Depends on 21.2 ; optional sqcli probe from 21.4
- Epic 13 event store patterns
- Can ship after offline CLI path works

## File List

- `trading-backtest/.../events/RunEventType.java` (+ `SQ_EXPORT_RECEIVED`)
- `trading-backtest/.../events/RunEvent.java` (+ `sqExportReceived`)
- `trading-parser/.../bridge/SqInboxProgressListener.java`
- `trading-parser/.../bridge/SqInboxProcessor.java` (public `runBatch` + listener)
- `trading-runtime/.../SqBridgeService.java`
- `trading-runtime/.../ControlPlaneServer.java`
- `trading-runtime/pom.xml` (+ `trading-parser` dep)
- `trading-runtime/src/test/.../SqBridgeServiceTest.java`
- `trading-runtime/src/test/.../ControlPlaneServerTest.java`
- `trading-tui/.../ControlPlaneClient.java`
- `trading-tui/.../TuiCommandHandler.java`
- `trading-tui/src/test/.../TuiCommandHandlerTest.java`
- `docs/contributing.md`, `AGENTS.md`

## Change Log

- 2026-05-31: Story 21-7 — SqBridgeService, HTTP routes, TUI /sq, SQ_EXPORT_RECEIVED events.
- 2026-05-31: CR patches — close sqBridgeService, 409 client, tests.
- 2026-05-31: Story marked done.

## References

- Story 13.3 control plane HTTP
- Story 13.6 TUI
- Story 21-2 `SqInboxProcessor`

## Dev Agent Record

### Completion Notes

- `GET /api/sq-bridge/status` — probe `-symbol action=list`, cache 60s, pending XML count.
- `POST /api/sq-bridge/process-inbox` — 202 async, single-thread executor, `SqJobMutex` before drain.
- Each processed file appends `SQ_EXPORT_RECEIVED` to event store run `sq-bridge`.
- TUI: `/sq`, `/inbox`, `/inbox process`.
- Tests green for trading-backtest, trading-parser, trading-runtime, trading-tui.

### Review Findings (2026-05-31)

- [x] [Review][Patch] `ControlPlaneServer.close()` — fermer `SqBridgeService` [`ControlPlaneServer.java`]
- [x] [Review][Patch] TUI 409 — `ControlPlaneClient.processSqInbox()` accepte 409 [`ControlPlaneClient.java`]
- [x] [Review][Patch] Test — conflit 409 process-inbox [`ControlPlaneServerTest`, `ControlPlaneClientTest`]
- [ ] [Review][Defer] Probe sqcli sur thread HTTP — cache miss bloque jusqu'à 15s [`SqBridgeService.refreshProbeIfNeeded`]
- [ ] [Review][Defer] Events `sq-bridge` — append event store sans `RunRecord` ni WS replay standard [`SqBridgeService.runInboxWorker`]
- [ ] [Review][Defer] Mutex busy post-202 — worker échoue async si lock pris par sqcli job externe
- [ ] [Review][Dismiss] `sq.bridge.sq.home` — property test-only, acceptable pour CI offline
- [ ] [Review][Dismiss] Inbox runtime sans `--data-path` — synthetic bars suffisants pour story 21-7
