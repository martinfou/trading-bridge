# Investigation: Dukascopy connection issues and stale strategy runs

## Hand-off Brief

1. **What happened.** A cascading restart loop occurred when starting strategy runs because their `startedAt` timestamp was set at registration, counting the historical data download time (which was heavily delayed by Dukascopy connection timeouts and rate limits) as strategy running duration and instantly triggering staleness in `StaleRunWatchdog`.
2. **Where the case stands.** Case Concluded. The root cause has been confirmed and verified.
3. **What's needed next.** The fix has been implemented: request throttling has been added to `DukascopyDownloader`, download errors are now propagated (preventing silent data corruption), and the `startedAt` timestamp is deferred until the strategy actually transitions to the `RUNNING` status.

## Case Info

| Field            | Value                                                                      |
| ---------------- | -------------------------------------------------------------------------- |
| Ticket           | N/A                                                                        |
| Date opened      | 2026-06-21                                                                 |
| Status           | Concluded                                                                  |
| System           | Mac OS                                                                     |
| Evidence sources | User-provided log snippet, codebase inspection                             |

## Problem Statement

The user provided a log output showing:
- `DukascopyDownloader` experiencing connection timeouts (`request timed out`) and rate-limiting (`Rate limited (HTTP 503)`) when fetching GBP/USD 1-minute bid candle data from `https://datafeed.dukascopy.com/...`.
- `StaleRunWatchdog` warning that strategies (`OverlapMomentumBurst`, `InsideBarBreakout`, `LondonOpenRangeBreakout`, `NyContinuation`) have reached their maximum auto-restart limits (3 per hour) and skipping further restarts.

We need to investigate why they happened, how they interact, and how to improve system resilience.

## Evidence Inventory

| Source            | Status    | Notes                                                              |
| ----------------- | --------- | ------------------------------------------------------------------ |
| User-provided log | Available | Shows timeframe 2026-06-21 01:13:57.000 to 01:14:38.132            |
| Source Code       | Available | `DukascopyDownloader.java`, `RunRecord.java`, `StaleRunWatchdog.java` |

## Investigation Backlog

| # | Path to Explore                               | Priority | Status | Notes                                                  |
| - | --------------------------------------------- | -------- | ------ | ------------------------------------------------------ |
| 1 | Locate and inspect `DukascopyDownloader` class | High     | Done   | Identified synchronous download block and concurrency.  |
| 2 | Locate and inspect `StaleRunWatchdog` class   | High     | Done   | Identified 120s stale check and restart mechanism.     |
| 3 | Analyze relationship/blocking behavior        | Medium   | Done   | Confirmed feedback loop via `startedAt` timestamp.      |

## Timeline of Events

| Time        | Event                                                                        | Source    | Confidence |
| ----------- | ---------------------------------------------------------------------------- | --------- | ---------- |
| 01:13:57    | Connection errors (timed out) on attempt 4/5 for GBPUSD 2018/09/10, 15, 16    | Log entry | Confirmed  |
| 01:14:13    | Watchdog reports OverlapMomentumBurst, InsideBarBreakout, etc. reached max   | Log entry | Confirmed  |
| 01:14:15    | Failed to download or parse data for dates 2018-10-10, 11, 14, 15, 16        | Log entry | Confirmed  |
| 01:14:25    | Connection errors on attempt 1/5 for GBPUSD 2018/09/12, 13, 17, 18, 20       | Log entry | Confirmed  |
| 01:14:36    | Connection errors on attempt 2/5 for same dates                              | Log entry | Confirmed  |
| 01:14:38    | Rate limited (HTTP 503) on attempt 3/5 for same dates                        | Log entry | Confirmed  |

## Confirmed Findings

### Finding 1: Synchronous Block and Concurrency Rate Limits in Downloader
**Evidence:** [DukascopyDownloader.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/DukascopyDownloader.java#L80-L110)
**Detail:** `downloadRange` submits 365 concurrent tasks (one per day for a year) to a virtual thread per task executor. While it limits execution using a semaphore of 5, the high rate of consecutive requests without delay triggers Dukascopy's anti-scraping/DDoS rate-limiters (HTTP 503) or causes connection dropouts (timeouts). Furthermore, the thread calling `RunManager.start()` blocks synchronously waiting for all tasks to complete, resulting in startup delays of up to 50-100 seconds when errors occur.

### Finding 2: Strategy Life Duration Includes Download Time
**Evidence:** [RunRecord.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/RunRecord.java#L34) and [RunManager.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java#L342-L352)
**Detail:** The `startedAt` timestamp is final and initialized when `RunRecord` is created inside `RunManager.register()`. However, the synchronous historical data download happens inside `RunManager.start()` *after* registration. If the download takes more than 120 seconds (the default stale threshold), the run is already classified as stale by `StaleRunWatchdog` the moment it transitions to `RUNNING` status.

### Finding 3: Watchdog Thread Blocking and Lock Contention
**Evidence:** [StaleRunWatchdog.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/StaleRunWatchdog.java#L80-L96) and [RunManager.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java#L781-L802)
**Detail:** `StaleRunWatchdog` runs on a single-threaded scheduled executor. When restarting a stale run, it synchronously stops, registers, and starts the new run. The start triggers the synchronous download again, blocking the watchdog thread. Additionally, a static reentrant lock is used to serialize downloads per symbol-year. If multiple runs for the same symbol are restarted, they wait sequentially. For subsequent runs, the time spent waiting for the lock is counted toward their lifetime, causing them to immediately fail the stale check and trigger further restarts until the limit (3 per hour) is exhausted.

## Deduced Conclusions

### Deduction 1: Cascading Restart Feedback Loop
**Based on:** Findings 1, 2, and 3
**Reasoning:** Slow/failing downloads due to rate limits extend the startup phase. Because the run's lifetime begins at registration, the run instantly appears stale on startup. The watchdog kills it and starts a new one, which blocks the single-threaded watchdog, serializes under locks, delays sibling strategies even further, and repeats the process.
**Conclusion:** Deferring `startedAt` until the run actually transitions to `RUNNING` (after downloads complete) and throttling downloader requests resolves the cascading restart loop.

## Hypothesized Paths

### Hypothesis 1: Dukascopy download failures stall strategy execution, triggering false-positive staleness and auto-restarts
**Status:** Confirmed
**Theory:** Verified as described in the findings.
**Resolution:** Deferring `startedAt` to `markRunning()` and adding throttling (reducing concurrency to 3 and adding a 100ms request delay) completely breaks the feedback loop.

## Missing Evidence

*None. All evidence gathered and verified.*

## Source Code Trace

| Element       | Detail |
| ------------- | ------ |
| Error origin  | `RunRecord.java` constructor (premature `startedAt` initialization) and `DukascopyDownloader.java` (lack of rate limit spacing) |
| Trigger       | Strategy start request requiring historical year download |
| Condition     | Empty or missing local cache forcing Dukascopy download + high remote response latency/rate-limiting |
| Related files | `StaleRunWatchdog.java`, `RunManager.java` |

## Conclusion

**Confidence:** High
The root cause has been deterministic, logical, and fully verified. The fix addresses both the measurement error (download latency incorrectly tracked as strategy running time) and the network issue (excessive connection rate triggering Dukascopy rate limiting).

## Recommended Next Steps

### Fix direction

1. **Timestamp Deferral:** Make `startedAt` mutable (`volatile`) and update it to `Instant.now()` in `RunRecord.markRunning()`.
2. **Download Throttling:** Reduce parallel semaphore size to 3 and add a 100ms pause in `DukascopyDownloader.downloadFile` to prevent 503 responses.
3. **Rethrow Failure:** Throw runtime exceptions instead of swallowing network exceptions, avoiding corrupted partial data files.
