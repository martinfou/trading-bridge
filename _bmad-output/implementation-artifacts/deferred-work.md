# Deferred work backlog

## Deferred from: code review of 13-8-heartbeat-stale-detection (2026-05-30)

- Dashboard has no `signals.stale[]` panel — per-run `isStale` badge sufficient for MVP; aggregate stale card can mirror gaps/drift later.
- Event store growth on long broker runs — one HEARTBEAT per bar; revisit if SQLite size or replay latency becomes an issue.
- No wall-clock heartbeat for async live streaming — synchronous `BrokerRunExecutor` only; Epic 4 live bar feed may need timer-based liveness independent of bar loop.
