---
baseline_commit: d9394689f5f10c0930c2909c5d51d404be891a4d
---
# Story 16.14: Position Synchronization

Status: review

## Story

As a trader,
I want the platform's runtime tracking and telemetry to remain strictly synchronized with my actual broker account positions,
So that I do not see ghost active/open trades or incorrect telemetry when trades are closed directly at the broker (via Stop Loss, Take Profit, or manual intervention).

## Acceptance Criteria

1. **AC1 — Correct Presentation Fallback**:
   In [ControlSummaryService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java)'s `getPositions` method, do not fall back to replaying journal fills if the run is broker-backed and the broker is queried successfully. 
   - If the broker returns **0 positions** for that run's symbol/client tag, the positions list must be returned as empty.
   - Only fall back to `JournalPositions.fromFills(...)` if:
     - The run is not broker-backed (e.g. `PAPER_STUB` or backtests).
     - OR the broker query fails with an exception (e.g. network timeout).

2. **AC2 — Active Trade Reconciliation & Healing**:
   In [OandaStreamingExecutor.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java), introduce periodic position reconciliation.
   - At the end of each bar or periodically (e.g., every 60 seconds), verify the actual open positions reported by the broker against the current local journaled positions.
   - If the broker reports **0 open positions** for the strategy's symbol and client tag, but the local event store thinks there is an active trade (i.e., replaying `FILL` events results in an open quantity), the executor must append a corrective `FILL` event to the event store.
   - The corrective `FILL` event should have:
     - An opposite side to the open position (e.g., `SELL` to close a `BUY`).
     - A quantity matching the remaining open quantity.
     - A price equal to the last known market price or close price.
     - A payload flag `reconciliation: true` and `reason: "BROKER_POSITION_CLOSED"`.
   - This ensures subsequent calls to `calculatePnLMetrics` correctly see the trade as closed.

3. **AC3 — Standalone Runner Verification**:
   In [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java)'s `updatePositions` method:
   - Periodically (e.g., every 60 seconds) query OANDA's actual open trades list using the REST client.
   - If a trade ID stored in the `activeTrades` list is no longer returned in OANDA's open trades list, remove it from `activeTrades`, record the realized PnL, and log a warning.
   - This prevents wicks, spread differences, or manual closes from leaving stale active trades in the runner's memory and `/tmp/live-strategy-state-*.json` state files.

4. **AC4 — Unit & Integration Tests**:
   - Add unit tests in [ControlSummaryServiceTest.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlSummaryServiceTest.java) to verify that `getPositions` returns an empty list (no fallback) when a broker-backed run has a successful broker query with 0 positions.
   - Add unit tests in [ReconciliationServiceTest.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/test/java/com/martinfou/trading/runtime/ReconciliationServiceTest.java) verifying reconciliation detection.

## Tasks / Subtasks

- [x] Task 1: Fix Presentation Fallback Logic (AC: 1)
  - [x] Modify `getPositions` in [ControlSummaryService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java) to track whether the broker query succeeded.
  - [x] Only execute the fallback block if the query failed or is not broker-backed.
- [x] Task 2: Implement Event Journal Healing in Live Executor (AC: 2)
  - [x] Implement a periodic reconciliation check in [OandaStreamingExecutor.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java).
  - [x] Compare broker-reported positions against `JournalPositions.fromFills`.
  - [x] Append corrective `FILL` events if the broker reports no position but the journal has open fills.
- [x] Task 3: Implement Broker Position Verification in Standalone Runner (AC: 3)
  - [x] Modify `updatePositions` in [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java) to fetch open trades from OANDA.
  - [x] Reconcile `activeTrades` and remove any trade IDs that are no longer active at OANDA.
- [x] Task 4: Tests and Verification (AC: 4)
  - [x] Add/update test cases in [ControlSummaryServiceTest.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlSummaryServiceTest.java) and [ReconciliationServiceTest.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/test/java/com/martinfou/trading/runtime/ReconciliationServiceTest.java).
  - [x] Run full project build: `mvn clean install` to verify.

## Dev Notes

- **SQLite Event Store Persistence**: Because the SQLite database is append-only, appending corrective `FILL` events is the only way to heal the event stream for calculating metrics without modifying historical entries.
- **Ambiguity of Closed Prices**: If a trade was closed via Stop Loss or Take Profit at OANDA, the exact exit price might differ from our local close price. Querying the trade details from OANDA or using the last candle price for the corrective fill is acceptable.

### References

- Control Summary Service: [ControlSummaryService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java#L283-L358)
- Streaming Executor: [OandaStreamingExecutor.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java#L148-L158)
- Standalone Runner positions update: [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java#L868-L930)
