# Story 39.2 — Strategy-based Telemetry and Trade Tracking

Status: done

Epic: 39 — OANDA API Resilience & Transient Error Handling

## Story

As a trader,
I want the control plane telemetry and GUI dashboard to track trades and P&L metrics by Strategy ID (segregated by execution mode) instead of specific execution Run IDs,
so that when a strategy run restarts, I do not lose its cumulative trade history, realized P&L, or metric tracking on the dashboard.

## Acceptance Criteria

- [x] **AC1** — `RunManager.getTrades(runId)` retrieves all sibling runs sharing the same `strategyId` and `mode` (using `runRecordStore.listAll()`), aggregates their trades, sorts them chronologically, and returns the combined list.
- [x] **AC2** — `ControlSummaryService.calculatePnLMetrics(RunRecord record)` calculates realized P&L cumulatively across all sibling runs sharing the same `strategyId` and `mode`.
- [x] **AC3** — The open P&L and net position tracking are calculated only for the currently active/running runs of that strategy.
- [x] **AC4** — Unit tests verify cumulative trade retrieval and realized P&L aggregation across multiple sibling runs.

## Tasks

- [x] Modify `RunManager.getTrades` to retrieve sibling runs sharing the same `strategyId` and `mode`, aggregate their trades, and sort them.
- [x] Modify `ControlSummaryService.calculatePnLMetrics` to compute realized P&L cumulatively across sibling runs.
- [x] Write unit tests in `RunManagerTest.java` and `ControlSummaryServiceTest.java` verifying the cumulative aggregation.
- [x] Run `mvn test -pl trading-runtime` to ensure all tests pass.

## File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/RunManagerTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlSummaryServiceTest.java`

## References

- [RunManager.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java)
- [ControlSummaryService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java)
