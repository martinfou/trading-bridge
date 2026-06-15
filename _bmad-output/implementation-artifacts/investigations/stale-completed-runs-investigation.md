# Investigation: Stale Completed Runs

## Hand-off Brief

1. **What happened.** 10 active paper trading strategy runs transitioned to `COMPLETED` status automatically because they were flagged as `STALE` by the `StaleRunWatchdog` (since no new bar events were received for >120 seconds), which cleanly stopped them to initiate recovery.
2. **Where the case stands.** Recovery restarts failed with `Failed to fetch OANDA account summary` because the control plane has been running continuously since 9:49 PM using the invalid credentials cached in memory, despite the user updating the credentials in `broker-accounts.local.json` later (around 10:00 PM).
3. **What's needed next.** Restart the control plane to force the `BrokerAccountRegistry` to load the updated, valid credentials from `broker-accounts.local.json`.

## Case Info

| Field            | Value                                                                      |
| ---------------- | -------------------------------------------------------------------------- |
| Ticket           | N/A                                                                        |
| Date opened      | 2026-06-15                                                                 |
| Status           | Concluded                                                                  |
| System           | macOS, Java 23, Maven 3.9.9                                                |
| Evidence sources | `events.db` SQLite event logs, `control-plane.log`, `broker-accounts.local.json`, `lsof`/`ps` |

## Problem Statement

The user noticed that strategy runs transitioned to `COMPLETED` status on the Trading Desk dashboard even though they did not cancel any jobs. One card showed `FAILED`.

## Evidence Inventory

| Source | Status | Notes |
| ------ | ------ | ----- |
| `events.db` | Available | Shows all runs first log an `ERROR` with "Failed to fetch OANDA account summary", followed immediately by `RUN_ENDED`. |
| `broker-accounts.local.json` | Available | Contains valid credentials under account `oanda-paper` (confirmed via test script). |
| `lsof -i :8080` & `ps` | Available | Shows the control plane JVM (PID 26520) has been running continuously since June 14, 9:49 PM local time. |
| `.env` | Available | Contains the old/invalid credentials (HTTP 401). |

## Investigation Backlog

| # | Path to Explore | Priority | Status | Notes |
| - | --------------- | -------- | ------ | ----- |
| 1 | Confirm credentials validity | High | Done | Verified that the credentials in `broker-accounts.local.json` are valid but those in `.env` are invalid. |

## Timeline of Events

| Time (UTC) | Event | Source | Confidence |
| ----------- | ------------------- | --------------------- | --------------------- |
| 2026-06-15T01:49:00Z | Control plane JVM (PID 26520) started | `ps` | Confirmed |
| 2026-06-15T02:00:37Z | User updated OANDA credentials in local JSON | Conversation history | Confirmed |
| 2026-06-15T02:03:23Z | Strategy runs started (e.g. `774a88e6-9893-4dbc-9ba8-f5e13b8b5cc9`) | `events.db` | Confirmed |
| 2026-06-15T08:00:00Z | Last heartbeats/bars received | `events.db` | Confirmed |
| 2026-06-15T08:54:38Z | First run recovery starts: watchdog stops dead run (triggers status `COMPLETED` and `RUN_ENDED` event) | `events.db`, `StaleRunWatchdog.java` | Confirmed |
| 2026-06-15T09:02:02Z | Watchdog stops `EmaPullbackContinuation` due to staleness and fails to restart it | `events.db` | Confirmed |

## Confirmed Findings

### Finding 1: Watchdog Stale Detection & Stopping
**Evidence:** `trading-runtime/src/main/java/com/martinfou/trading/runtime/StaleRunWatchdog.java:89`

**Detail:** The watchdog detected that no events were received for more than 120 seconds. It initiated recovery by stopping the old run using `runManager.stop(runId)`.

### Finding 2: Stop transitions status to Completed
**Evidence:** `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java:268`

**Detail:** Stopping a running executor inside the `RunManager` transitions the status to `COMPLETED` and appends a `RUN_ENDED` event to the `events.db`.

### Finding 3: In-Memory Credentials Stale
**Evidence:** `lsof` / `ps` output showing start time `9:49 PM` (pre-dating the credentials fix).

**Detail:** `BrokerAccountRegistry` loads credentials at startup. Since the JVM was not restarted after the credentials fix, it was still using the old/invalid token from startup.

## Deduced Conclusions

### Deduction 1: Stale Run Recovery Chain
**Based on:** Finding 1, Finding 2, Finding 3.

**Reasoning:**
1. The OANDA stream went silent (due to market close, network disconnect, or rate limits).
2. The `StaleRunWatchdog` noticed no heartbeat for >120s and initiated recovery.
3. Recovery called `stop()`, which marked the old runs as `COMPLETED`.
4. Recovery registered and started new runs, but they failed with `Failed to fetch OANDA account summary` because the JVM had cached the old, invalid credentials.
5. The failed starts caused the new run cards to show `FAILED` (e.g., `InsideBarBreakout`).

## Hypothesized Paths

### Hypothesis 1: Connection was failing back to local data at start
**Status:** Confirmed.

**Theory:** When the runs first started, `loadBars` failed to fetch live candles due to the 401 error, logged a warning, and fell back to local historical data. This allowed the strategies to boot, but once running, they had no live ticks coming in, causing the watchdog to eventually flag them as stale.

## Missing Evidence

None.

## Source Code Trace

| Element       | Detail                                      |
| ------------- | ------------------------------------------- |
| Error origin  | `HttpOandaRestClient.java:274` (`fetchAccountSummary`) |
| Trigger       | `OandaStreamingExecutor.java:143` -> `broker.connect()` |
| Condition     | Invalid credentials in-memory producing a 401 error |
| Related files | `RunManager.java`, `StaleRunWatchdog.java`, `BrokerAccountRegistry.java` |

## Conclusion

**Confidence:** High

The strategies are completed because the `StaleRunWatchdog` stopped them after detecting they were stale (no live price updates). The subsequent automatic restarts failed because the control plane has not been restarted since the valid credentials were configured, meaning it was still using the old, invalid credentials in memory.

## Recommended Next Steps

### Fix direction

Restart the control plane:
1. Stop the running control plane (using Ctrl+C or running `./scripts/stop-control-plane.sh`).
2. Start the control plane again (using `./start-control-plane.sh` or through the desktop app).
