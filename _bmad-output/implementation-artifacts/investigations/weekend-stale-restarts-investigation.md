# Investigation: Strategy Status COMPLETED on Weekend Market Close Stale Restarts

## Hand-off Brief

1. **What happened.** A large number of `COMPLETED` strategy runs accumulated on the Trading Desk dashboard. This was caused by the weekend close of the OANDA market on Friday at 21:00 UTC (17:00 EST), which stopped the live price stream; the `StaleRunWatchdog` flagged the runs as stale (since no events were received for >120 seconds), stopped them (marking them `COMPLETED`), and started new runs, which in turn became stale and were restarted up to 3 times per hour per strategy.
2. **Where the case stands.** The root cause is **Confirmed** (High confidence). The watchdog continues to cycle restarts every hour when the OANDA market is closed, accumulating completed run records in memory because `RunManager` never removes ended run records from its in-memory map.
3. **What's needed next.** Implement a weekend check in `StaleRunWatchdog` to avoid restarts during market close hours, and implement a cleanup mechanism in `RunManager` to clear/remove completed runs from memory.

## Case Info

| Field            | Value                                                                      |
| ---------------- | -------------------------------------------------------------------------- |
| Ticket           | N/A                                                                        |
| Date opened      | 2026-06-20                                                                 |
| Status           | Concluded                                                                  |
| System           | macOS, Java 23, OANDA API                                                 |
| Evidence sources | `StaleRunWatchdog.java`, `RunManager.java`, `/api/control/summary` API     |

## Problem Statement

The user noticed a sudden large number of completed strategies (approx. 102 completed runs in memory) on the Trading Desk dashboard that appeared over the course of a few hours.

## Evidence Inventory

| Source | Status | Notes |
| ------ | ------ | ----- |
| `/api/control/summary` API | Available | Shows 106 runs in memory: 4 `RUNNING`, 102 `COMPLETED`. All running strategies had their last event at `20:59:05 UTC` on Friday. |
| `/api/runs` API | Available | Shows 1082 runs (combining 106 in-memory runs + 976 historical backtests from SQLite). |
| `StaleRunWatchdog.java` | Available | Shows it runs every 60 seconds, stopping stale runs (>120s silent) and starting new ones, with a limit of 3 restarts per hour per strategy. |
| `RunManager.java` | Available | Shows that `runs` are added to the map on start but never removed, keeping completed runs in memory indefinitely. |

## Investigation Backlog

| # | Path to Explore | Priority | Status | Notes |
| - | --------------- | -------- | ------ | ----- |
| 1 | Analyze the timing of the last events | High | Done | Verified last event was at Friday 20:59:05 UTC (market close). |
| 2 | Check watchdog throttle reset logic | High | Done | Confirmed `restartsPerHour` clears every hour, allowing infinite restarts over the weekend. |
| 3 | Check run removal from memory | High | Done | Verified `RunManager` never calls `runs.remove(runId)`. |

## Timeline of Events

| Time (UTC) | Event | Source | Confidence |
| ----------- | ------------------- | --------------------- | --------------------- |
| 2026-06-19T20:59:05Z | Last events/ticks received on active strategies | `/api/control/summary` | Confirmed |
| 2026-06-19T21:00:00Z | OANDA Forex market closes for the weekend | Market standard | Confirmed |
| 2026-06-19T21:01:05Z | Watchdog detects runs as stale (>120s since 20:59:05) and restarts them | `StaleRunWatchdog.java` | Confirmed |
| 2026-06-19T21:05:00Z | Watchdog restarts strategies up to 3 times in the first hour, then hits the hourly limit | `StaleRunWatchdog.java` | Confirmed |
| Every hour after | Watchdog hourly counter resets; restarts strategies 3 more times | `StaleRunWatchdog.java` | Confirmed |

## Confirmed Findings

### Finding 1: Market Close stopped price updates
**Evidence:** `/api/control/summary` query showing `lastEventAt` for all running strategies is `2026-06-19T20:59:05.335329940Z` or `2026-06-19T20:59:05.324731656Z` (which is Friday 16:59:05 EST, right before the 17:00 EST market close).

### Finding 2: Watchdog hourly restart logic resets indefinitely
**Evidence:** `StaleRunWatchdog.java:45-48` and `StaleRunWatchdog.java:70-75`
```java
if (Duration.between(currentHour, Instant.now()).toHours() >= 1) {
    restartsPerHour.clear();
    currentHour = Instant.now();
}
...
if (restarts >= 3) {
    log.warn("Strategy {} has reached the maximum auto-restart limit (3 per hour). Skipping restart for stale run {}.", strategyId, runId);
    continue;
}
```
**Detail:** The watchdog restarts stale strategies up to 3 times per hour. However, because it clears the counter every hour, it will attempt another 3 restarts in the next hour, continuing for the entire weekend (48 hours), leading to up to 144 completed runs per strategy.

### Finding 3: RunManager retains all runs in memory
**Evidence:** `RunManager.java` contains no references to `runs.remove(...)`.
**Detail:** Runs are put in the `runs` map at registration but never removed when they stop, fail, or complete, so they accumulate in memory.

## Deduced Conclusions

### Deduction 1: Stale Run Accumulation
**Based on:** Findings 1, 2, and 3.
**Reasoning:** 
1. Market close stops ticks.
2. Watchdog restarts stale runs 3 times per hour.
3. Every restart creates a new run and leaves the stopped run in the `runs` map as `COMPLETED`.
4. Over the course of 6.5 hours, this has accumulated 102 completed runs in memory.

## Source Code Trace

| Element       | Detail                                      |
| ------------- | ------------------------------------------- |
| Error origin  | `StaleRunWatchdog.java:39` (scheduleAtFixedRate) |
| Trigger       | `StaleRunWatchdog.java:43` (checkStaleRuns) |
| Condition     | Market close -> no new events -> watchdog triggers recovery loop |
| Related files | `RunManager.java`, `ControlSummaryService.java` |

## Conclusion

**Confidence:** High

The strategies transitioned to `COMPLETED` because the `StaleRunWatchdog` stopped them after they stopped receiving price updates due to the Friday market close. Because the watchdog resets its throttle limit every hour, it keeps attempting to restart the strategies, registering new runs and stopping them, which creates a large number of `COMPLETED` runs in memory since `RunManager` does not clean up ended run records.

## Recommended Next Steps

### Fix direction

1. **Weekend session detection in `StaleRunWatchdog`**:
   The watchdog should not attempt to restart strategies if the OANDA market is closed (e.g. from Friday 17:00 EST / 21:00 UTC to Sunday 17:00 EST / 21:00 UTC).
2. **Clean up ended runs in `RunManager`**:
   Alternatively, or additionally, finished runs (`COMPLETED` or `FAILED`) should be removed from `RunManager`'s active `runs` map after a certain timeout or when they transition to a terminal state (or the UI should handle filtering/hiding old completed runs).
