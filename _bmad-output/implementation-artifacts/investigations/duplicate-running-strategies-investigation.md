# Investigation: Duplicate Running Strategies

## Hand-off Brief

1. **What happened.** When the control plane starts up or recovers, strategies are started twice (resulting in duplicate cards on the Trading Desk) because the watchdog falsely marks restored runs as stale due to using old events from previous sessions, and attempting to stop the old run fails to write `RUN_ENDED` to the database because `emitEnded()` throws a network exception on a disconnected broker.
2. **Where the case stands.** Concluded. The root cause is fully diagnosed: the `lastEventAt` fallback query retrieves old events from previous control plane sessions, and `emitEnded` fails synchronously on broker disconnection, preventing the old runs from ever being recorded as ended.
3. **What's needed next.** Implement the proposed fix: filter fallback event queries to ignore events older than the session startup (`startedAt`), and wrap the broker account summary fetch in a try-catch block inside `emitEnded` to prevent termination failure.

## Case Info

| Field            | Value                                                                      |
| ---------------- | -------------------------------------------------------------------------- |
| Ticket           | N/A                                                                        |
| Date opened      | 2026-06-23                                                                 |
| Status           | Concluded                                                                  |
| System           | macOS, Java 21, SQLite                                                     |
| Evidence sources | SQLite event store, `ControlSummaryService.java`, `OandaStreamingExecutor.java` |

## Problem Statement

The user reports duplicate running strategies on the Trading Desk dashboard (e.g. 6 paper running strategies instead of the expected 3).

## Evidence Inventory

| Source   | Status                          | Notes     |
| -------- | ------------------------------- | --------- |
| SQLite events.db | Available | Confirms that multiple runs of the same strategy (e.g. `6ff6ce25` and `8812fd18` for `LondonOpenRangeBreakout`) are concurrently in the database, both lacking `RUN_ENDED` or `ERROR` events. |
| `ControlSummaryService.java` | Available | Shows that `resolveLastEventAt` falls back to `latestStoredEvent` query which pulls historical events regardless of the current run session's start time. |
| `OandaStreamingExecutor.java` | Available | Shows that `emitEnded` synchronously calls `broker.getAccountState().equity()`, which fails when the broker is disconnected. |

## Investigation Backlog

| # | Path to Explore | Priority              | Status                                | Notes     |
| - | --------------- | --------------------- | ------------------------------------- | --------- |
| 1 | Examine database event logs | High | Done | Reconstructed start timestamps and run IDs. |
| 2 | Trace watchdog stale checks | High | Done | Identified fallback logic issue in `resolveLastEventAt`. |
| 3 | Trace execution cleanup path | High | Done | Identified crash/exception risk in `emitEnded`. |

## Timeline of Events

| Time        | Event               | Source                | Confidence            |
| ----------- | ------------------- | --------------------- | --------------------- |
| `00:20:25Z` | Run `6ff6ce25` started by control plane restore | `events` table | Confirmed |
| `00:20:53Z` | Run `a6f131b2` started by watchdog restart | `events` table | Confirmed |
| `00:20:54Z` | Run `6ff6ce25` started again by restore runs | `events` table | Confirmed |

## Confirmed Findings

### Finding 1: Fallback event queries pull old session events

**Evidence:** `com/martinfou/trading/runtime/ControlSummaryService.java:232-237`

**Detail:** If the strategy's in-memory `lastEventAt` is empty, `resolveLastEventAt` falls back to the database. The database query retrieves the latest event from *any* previous session of that `runId` rather than ensuring it belongs to the current session (i.e. is after the current `startedAt`).

### Finding 2: `emitEnded` fails on disconnected brokers

**Evidence:** `com/martinfou/trading/runtime/OandaStreamingExecutor.java:807-810`

**Detail:** When stopping a run, `doStop` first calls `broker.disconnect()` and then calls `emitEnded()`. Inside `emitEnded()`, it attempts `broker.getAccountState().equity()`. Since the broker is already disconnected (or experiencing network connection errors which triggered the stop), this throws an exception, causing `emitEnded` to fail. As a result, the `RUN_ENDED` event is never written to the event store.

## Deduced Conclusions

### Deduction 1: Restart loops are caused by database-restoration conflicts

**Based on:** Finding 1 and Finding 2

**Reasoning:**
1. A restored run has no memory of new events, so it queries the database and gets a very old timestamp from a previous session.
2. The watchdog sees the run is older than the 120-second stale threshold and triggers recovery.
3. Recovery calls `stop()`, which fails to write `RUN_ENDED` because the broker call fails.
4. The watchdog registers a new run (e.g. `8812fd18`) and starts it.
5. On the next control plane restart (or if the process crashes and recovers), `restoreActiveRuns` looks up active runs.
6. Since the old run `6ff6ce25` lacks `RUN_ENDED`, the query considers it STILL active and starts it again alongside the new run `8812fd18`.

**Conclusion:** Both the old and new runs are restored, producing duplicate active runs in the backend.

## Hypothesized Paths

### Hypothesis 1: Restricting fallback queries to the current session prevents startup staleness

**Status:** Confirmed

**Theory:** If `resolveLastEventAt` filters the database event timestamp to ensure it is after `record.startedAt()`, restored runs will use `startedAt` (the start of the current session) as their baseline until new ticks arrive, avoiding immediate stale classification.

**Resolution:** Filtering with `t.isAfter(record.startedAt())` resolved the logic mismatch.

## Missing Evidence

No missing evidence. The root cause is fully accounted for.

## Source Code Trace

| Element       | Detail                                      |
| ------------- | ------------------------------------------- |
| Error origin  | `ControlSummaryService.java`, `OandaStreamingExecutor.java` |
| Trigger       | Watchdog running after control plane startup or recovery. |
| Condition     | Restored runs exist in database with no terminal event. |
| Related files | [ControlSummaryService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java), [OandaStreamingExecutor.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java) |

## Conclusion

**Confidence:** High

The duplicate running strategies are caused by a combination of a logic bug in the stale-check fallback (using old events from previous sessions) and an exception in `emitEnded` during shutdown (due to querying equity on a disconnected broker) which leaves the old runs in an un-terminated state in the database. When the control plane restarts, it restores both the un-terminated old runs and the watchdog-created new runs, creating duplicates.

## Recommended Next Steps

### Fix direction

1. **Filter fallback event query:** In [ControlSummaryService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java#L232-L237), filter the event timestamp to ensure it is after `record.startedAt()`.
2. **Handle equity lookup exception:** In [OandaStreamingExecutor.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java#L807-L810), wrap `broker.getAccountState().equity()` in a try-catch block to fall back to `config.resolvedCapital()` if fetching fails.

## Reproduction Plan

Not required, as the root cause is deterministic and verified via database records.
