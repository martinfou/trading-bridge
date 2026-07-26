# Investigation: Duplicate Trades 2026-07-24

## Hand-off Brief

1. **What happened.** `NewsWeeklyStrategy` emits stop-loss (`STOP`) and take-profit (`LIMIT`) orders with the `closeOnly` flag. However, [OandaBroker.java](file:///Volumes/T7/src/trading-bridge/trading-broker/src/main/java/com/martinfou/trading/broker/OandaBroker.java) intercepts all `closeOnly` orders at submission and executes them immediately as market closes via `closeTrade()`. This closed trades instantly at entry, and re-submitting them in subsequent bars led to duplicate fills and reconciliation correctives.
2. **Where the case stands.** Concluded. The root cause has been diagnosed and isolated to the order intercept logic in [OandaBroker.java](file:///Volumes/T7/src/trading-bridge/trading-broker/src/main/java/com/martinfou/trading/broker/OandaBroker.java).
3. **What's needed next.** Propose a story or task to update [OandaBroker.java](file:///Volumes/T7/src/trading-bridge/trading-broker/src/main/java/com/martinfou/trading/broker/OandaBroker.java) to only execute immediate closes for `MARKET` type orders, and place `STOP` and `LIMIT` orders as pending `REDUCE_ONLY` orders at the broker.

## Case Info

| Field            | Value                                                                      |
| ---------------- | -------------------------------------------------------------------------- |
| Ticket           | N/A                                                                        |
| Date opened      | 2026-07-24                                                                 |
| Status           | Concluded                                                                  |
| System           | macOS                                                                      |
| Evidence sources | SQLite events database, `OandaBroker.java`, `NewsWeeklyStrategy.java`       |

## Problem Statement

The user's trading dashboard displays duplicate trades for the strategy `NewsWeek20Jul_Ecb_EUR_USD` (PAPER) on 2026-07-23 at 11:00. Specifically, two identical `BUY` trades with quantity 100,000, entry 1.13725, exit 1.13706, and P&L C$-26.75 are shown in the monitoring panel under Trades History.

## Evidence Inventory

| Source   | Status                          | Notes     |
| -------- | ------------------------------- | --------- |
| Screenshot | Available | Displays two identical BUY trades at 11:00 on July 23rd in the trades history panel. |
| SQLite events database | Available | Confirmed two BUY fills at 15:00:03Z followed by a 200,000 units SELL reconciliation corrective at 15:00:11Z. |
| Codebase | Available | Inspected `OandaBroker.java`, `NewsWeeklyStrategy.java`, and `HttpOandaRestClient.java`. |

## Investigation Backlog

| # | Path to Explore | Priority              | Status                                | Notes     |
| - | --------------- | --------------------- | ------------------------------------- | --------- |
| 1 | Locate and query SQLite database / event store for duplicate trade events | High | Done | Verified events on 2026-07-23. |
| 2 | Trace executor/trading runtime classes for execution pathways | High | Done | Identified immediate close-only intercept in `OandaBroker`. |
| 3 | Inspect frontend code fetching trades history | Medium | Done | Checked formatting in `TradeTable.vue`. |

## Timeline of Events

| Time (UTC)  | Event               | Source                | Confidence            |
| ----------- | ------------------- | --------------------- | --------------------- |
| 14:00:00.318 | `ORDER_SUBMITTED` for SELL entry (100,000 units) | events.db | Confirmed |
| 14:00:00.364 | `FILL` for SELL entry at 1.13723 | events.db | Confirmed |
| 14:00:00.408 | `ORDER_SUBMITTED` for BUY STOP (100,000 units) closeOnly Stop Loss | events.db | Confirmed |
| 14:00:00.510 | `FILL` for closeOnly order (broker closed the short trade immediately) | events.db | Confirmed (Trade 1 closed in 0.15s) |
| 15:00:03.083 | `ORDER_SUBMITTED` for BUY STOP closeOnly (re-emitted SL) | events.db | Confirmed |
| 15:00:03.205 | `ORDER_SUBMITTED` for BUY LIMIT closeOnly (re-emitted TP) | events.db | Confirmed |
| 15:00:03.279 | Both closeOnly orders executed immediately on broker (Trade 2 and 3 entries) | events.db | Confirmed |
| 15:00:11.399 | `FILL` for 200,000 SELL reconciliation (`BROKER_POSITION_CLOSED`) | events.db | Confirmed |

## Confirmed Findings

### Finding 1: Immediate execution of non-MARKET closeOnly orders
**Evidence:** [OandaBroker.java:L204](file:///Volumes/T7/src/trading-bridge/trading-broker/src/main/java/com/martinfou/trading/broker/OandaBroker.java#L204)
**Detail:** The broker intercept logic checks `if (order.isCloseOnly())` and immediately closes opposite positions using `client.closeTrade(...)` regardless of the order type (`STOP`/`LIMIT`).

### Finding 2: Strategy emits separate exit orders with closeOnly
**Evidence:** [NewsWeeklyStrategy.java:L250](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/newsweekly/NewsWeeklyStrategy.java#L250)
**Detail:** The strategy places initial stop-loss and take-profit levels as separate pending `STOP` and `LIMIT` orders in the `pending` queue, marked with `.closeOnly()`.

## Deduced Conclusions

### Deduction 1: Immediate Exit Trigger
**Based on:** Finding 1 & Finding 2
**Reasoning:** When the strategy completes an entry, it adds a `MARKET` entry and a `STOP` closeOnly stop-loss order. The entry fills first. The STOP order is submitted immediately after. Because it has `closeOnly = true`, the broker executes `client.closeTrade(...)` right away, closing the new position in less than a second.

### Deduction 2: Multiple Entries and Reconciliation Corrective
**Based on:** Timeline
**Reasoning:** At the next hour bar, the strategy sees that the trade is still active in its internal state (since no exit condition triggered in the strategy). It re-emits both the SL (`STOP`) and TP (`LIMIT`) closeOnly orders. The broker executes both immediately, leading to two duplicate `BUY` fills. The reconciliation loop sees a local position of +200,000 but a broker position of 0, and issues a 200,000 `SELL` corrective fill. The trade reconstructor matches this 200,000 `SELL` against the two 100,000 `BUY` fills, resulting in two duplicate closed trades in the database.

## Hypothesized Paths

### Hypothesis 1: Concurrency race condition during tick/bar processing
**Status:** Refuted
**Resolution:** Chronology shows sequential execution.

### Hypothesis 2: Frontend UI duplicates the display of a single trade record
**Status:** Refuted
**Resolution:** Database contains two separate trade records.

## Missing Evidence

*None. The evidence is complete.*

## Source Code Trace

| Element       | Detail                                      |
| ------------- | ------------------------------------------- |
| Error origin  | [OandaBroker.java:L204](file:///Volumes/T7/src/trading-bridge/trading-broker/src/main/java/com/martinfou/trading/broker/OandaBroker.java#L204) |
| Trigger       | Strategy SL/TP order submission.             |
| Condition     | Order is `closeOnly` but type is not `MARKET`. |
| Related files | `NewsWeeklyStrategy.java`, `HttpOandaRestClient.java` |

## Conclusion

**Confidence:** High

The root cause of the duplicate trades and immediate trade closures is that [OandaBroker.java](file:///Volumes/T7/src/trading-bridge/trading-broker/src/main/java/com/martinfou/trading/broker/OandaBroker.java) immediately closes trades on OANDA for any order marked `closeOnly`, even if it is a pending `STOP` or `LIMIT` order. 

## Recommended Next Steps

### Fix direction

1. Modify [OandaBroker.java:L204](file:///Volumes/T7/src/trading-bridge/trading-broker/src/main/java/com/martinfou/trading/broker/OandaBroker.java#L204) to only intercept `MARKET` closeOnly orders:
   ```java
   if (order.isCloseOnly() && order.type() == Order.Type.MARKET) {
   ```
2. Modify the OANDA REST Client to support `positionFill: REDUCE_ONLY` for pending `STOP` and `LIMIT` closeOnly orders.
   - Update `OandaRestClient.java` to accept a `positionFill` or `reduceOnly` flag in `placeOrder`.
   - Update `HttpOandaRestClient.java` to add `"positionFill": "REDUCE_ONLY"` to the JSON request payload when `reduceOnly` is true.

### Diagnostic

Run the maven build and test suite after the changes to verify no regressions in OANDA broker tests.

## Reproduction Plan

1. Run the control plane and start a paper strategy (e.g. `NewsWeek20Jul_Ecb_EUR_USD`).
2. Trigger/observe a trade entry.
3. Observe if the trade is closed immediately and duplicates are created at the next bar close.
