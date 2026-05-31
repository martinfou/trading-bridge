# Story 13.8 — Heartbeat + Stale Data Detection

**Status:** done  
**Epic:** 13 — Platform Runtime  
**Date:** 2026-05-30

## Summary

Adds `HEARTBEAT` to the RunEvent schema, emits one heartbeat per bar during broker execution, and makes stale-run detection configurable via `StaleThresholds` (JSON + env override). Control summary exposes `signals.stale[]` and freshness counters.

## Acceptance criteria

- [x] `RunEventType.HEARTBEAT` in schema v1 with factory helper
- [x] Broker runs journal `HEARTBEAT` once per bar (`source: BAR_LOOP`)
- [x] `data/runtime/stale-thresholds.json` with `runningStaleThresholdSeconds` (default 120)
- [x] `GET /control/summary` includes `freshness.staleThresholdSeconds`, `freshness.staleRunCount`, `signals.stale[]`
- [x] Unit tests for thresholds, summary stale signals, broker heartbeats

## Files

| Path | Change |
|------|--------|
| `trading-backtest/.../RunEventType.java` | Add `HEARTBEAT` |
| `trading-backtest/.../RunEvent.java` | Add `heartbeat()` factory |
| `trading-runtime/.../StaleThresholds.java` | New config record |
| `trading-runtime/.../HeartbeatEvents.java` | Emit helper |
| `trading-runtime/.../BrokerRunExecutor.java` | Per-bar heartbeat |
| `trading-runtime/.../ControlSummaryService.java` | Config + stale signals |
| `data/runtime/stale-thresholds.json` | Default config |
| `docs/testing.md` | Document heartbeat + config |

## Dev notes

- Stale threshold should exceed expected bar interval for slow timeframes (e.g. H1 → set ≥ 4000s).
- Backtest runs complete quickly; stale detection targets long-running broker `RUNNING` states.

### Review Findings

- [x] [Review][Patch] Heartbeat timestamp should use bar time, not wall clock [`RunEvent.java` / `HeartbeatEvents.java`] — fixed: `RunEvent.heartbeat(..., Instant timestamp)` uses bar time.
- [x] [Review][Patch] Assert per-bar heartbeat count in broker tests [`BrokerRunExecutorTest.java:47`] — fixed: exact count + first heartbeat timestamp assertion.
- [x] [Review][Patch] JSON roundtrip test for `HEARTBEAT` [`RunEventTest.java`] — fixed: `jsonRoundTrip_preservesHeartbeatFields`.

- [x] [Review][Defer] Dashboard has no `signals.stale[]` panel [`control-room.blade.php`] — deferred, not in story AC; per-run `isStale` badge already shown.
- [x] [Review][Defer] Event store growth on long broker runs [`BrokerRunExecutor.java`] — deferred, one HEARTBEAT per bar adds volume (e.g. 8760/run); acceptable for MVP.
- [x] [Review][Defer] No wall-clock heartbeat for async live streaming [`BrokerRunExecutor.java`] — deferred, current executor is synchronous; future Epic 4 live path may need timer-based liveness.
