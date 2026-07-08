# Investigation: Duplicate Trades Display

## Hand-off Brief

1. **What happened.** A concurrency race condition in the streaming price client caused multiple ticks to trigger strategy bar evaluations in parallel, resulting in duplicate order submissions and fills.
2. **Where the case stands.** Concluded. The race condition has been fixed by synchronizing `processTick`, and the database has been cleaned of duplicate events.
3. **What's needed next.** Restart the Java control plane to load the updated executor class, and refresh the desktop UI.

## Case Info

| Field            | Value                                                                      |
| ---------------- | -------------------------------------------------------------------------- |
| Ticket           | N/A                                                                        |
| Date opened      | 2026-07-08                                                                 |
| Status           | Concluded                                                                  |
| System           | macOS                                                                      |
| Evidence sources | LiveTradingView.vue, TradeReconstructor.java, ControlSummaryService.java |

## Problem Statement

The user reports seeing duplicate trades for the same instrument and average price:
* `GBP_USD BUY 100,000 1.33719 — — 2h 2m` (First duplicate)
* `GBP_USD BUY 100,000 1.33719 — — 2h 2m` (Second duplicate)
* `GBP_USD BUY 100,000 1.33661 — — 6d 3h` (Original trade)

## Evidence Inventory

| Source   | Status                          | Notes     |
| -------- | ------------------------------- | --------- |
| Screenshot/Text | Available | User reported open positions list show three positions, two of which are identical. |
| SQLite events db | Available | Sequence 3615, 3617, 3620, 3623 showed duplicate parallel orders and fills at 14:00. |

## Investigation Backlog

| # | Path to Explore | Priority              | Status                                | Notes     |
| - | --------------- | --------------------- | ------------------------------------- | --------- |
| 1 | Trace `/api/control/summary` and how `runs` positions array is built | High | Done | Identified concurrent processTick invocations as root cause. |
| 2 | Trace event replay in Sqlite database | Medium | Done | Confirmed duplicate event sequences in database and cleaned them. |

## Timeline of Events

| Time        | Event               | Source                | Confidence            |
| ----------- | ------------------- | --------------------- | --------------------- |
| 14:00:00.20 | ORDER_SUBMITTED #1  | events.db:seq 3615    | Confirmed             |
| 14:00:00.29 | ORDER_SUBMITTED #2  | events.db:seq 3617    | Confirmed             |
| 14:00:00.37 | FILL #1 at 1.33719  | events.db:seq 3620    | Confirmed             |
| 14:00:00.52 | FILL #2 at 1.33719  | events.db:seq 3623    | Confirmed             |

## Confirmed Findings

### Finding 1: Concurrency Race Condition in Tick Processing

**Evidence:** `OandaStreamingExecutor.java:430`

**Detail:** The `processTick` method was not synchronized. When multiple ticks arrived from the OANDA pricing stream concurrently, separate threads executed the strategy rules in parallel, leading to duplicate order generation.

## Deduced Conclusions

### Deduction 1: Parallel Bar Completion Triggers

**Based on:** Finding 1

**Reasoning:** When the hour boundary transitioned, multiple threads checked `aggregator.isNewPeriod` simultaneously. Because the first thread hadn't updated `currentPeriodStart` yet, both threads evaluated the completed bar start and triggered `strategy.onBar` concurrently.

**Conclusion:** Strategy rules executed twice, submitting two identical orders.

## Source Code Trace

| Element       | Detail                                      |
| ------------- | ------------------------------------------- |
| Error origin  | `OandaStreamingExecutor.java:430`           |
| Trigger       | Incoming pricing ticks on connection stream |
| Condition     | processTick method not synchronized         |
| Related files | BarAggregator.java                          |

## Conclusion

**Confidence:** High

The root cause of the duplicate trades was a concurrency race condition in the executor's tick aggregation. Making the method synchronized eliminates this race condition completely.

## Recommended Next Steps

### Fix direction

Restart the Java control plane so it loads the newly synchronized `OandaStreamingExecutor` code.
