---
baseline_commit: b1d6db7cc73a21922a8284a0d62f79412309ab82
---
# Story 16.12: Fix OANDA Position Client Tag Matching

Status: done

## Story

As a trader running multiple strategies on the same instrument,
I want active broker positions to be filtered by their associated strategy run's client tag,
So that strategies do not display each other's trades, get incorrect telemetry, or trigger cross-strategy liquidation.

## Acceptance Criteria

1. **AC1 — Propagate Trade Client Tag**: When placing market or limit/stop orders via OANDA broker (`HttpOandaRestClient.java`), set `tradeClientExtensions` in the order parameters using the client-side order ID (the strategy's `order.id()`) alongside the existing `clientExtensions` mapping. This ensures OANDA associates the client tag directly with the resulting filled trade.
2. **AC2 — Eliminate Telemetry Leaks**: Ensure that `ControlSummaryService.java` and `ControlPlaneServer.java` filter broker positions so that they only match a strategy run if the position's `clientTag` matches one of the run's journaled `orderId`s.
3. **AC3 — Safe Fallback**: If a position has no `clientTag` (null or blank), only associate it with a run if there is exactly one active run on the control plane for that symbol. If multiple active runs trade the same symbol, do not assign the untagged position to all of them (preventing it from leaking to other strategies).
4. **AC4 — Reconciliation Filtering**: Update `ReconciliationService.java` so that broker positions are filtered by `clientTag` (matching the run's order history) before comparing them against journal positions, preventing false reconciliation warnings or signals.
5. **AC5 — Safe Emergency Liquidation**: In `OandaStreamingExecutor.java` (liquidate / emergency close methods), ensure that positions are only targeted for liquidation if their `clientTag` matches the run's order history. If the position has no `clientTag`, do not liquidate it if there are other active runs on the same symbol.

## Tasks / Subtasks

- [x] Task 1: OANDA Order Payload Fix (AC: 1)
  - [x] Modify `placeMarketOrder` in [HttpOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java) to append `tradeClientExtensions` using the same tag structure as `clientExtensions`.
  - [x] Modify `placeOrder` in [HttpOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java) to append `tradeClientExtensions` using the same tag structure as `clientExtensions`.
- [x] Task 2: Refine Position Telemetry and Reconciler Matching (AC: 2, 3, 4)
  - [x] Refine `getPositions` in [ControlSummaryService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java) to only match untagged positions (null or blank `clientTag`) if a single run is active for that symbol.
  - [x] Apply the same position filtering logic in [ControlPlaneServer.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java).
  - [x] Refine [ReconciliationService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ReconciliationService.java) position filtering to prevent comparing untagged positions from other strategies.
- [x] Task 3: Refine Executor Liquidation Filtering (AC: 5)
  - [x] Refine `triggerBreachLiquidation` and emergency close logic in [OandaStreamingExecutor.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java) to prevent closing other strategies' positions when `clientTag` is null or does not match this run's IDs.
- [x] Task 4: Tests and Verification
  - [x] Add unit test case in [ReconciliationServiceTest.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/test/java/com/martinfou/trading/runtime/ReconciliationServiceTest.java) verifying behavior with multiple runs on the same symbol.
  - [x] Run full project build: `mvn clean install` to verify compliance.

### Review Findings

- [x] [Review][Patch] NPE in OandaStreamingExecutor and ReconciliationService on null active symbols list [OandaStreamingExecutor.java:1005]
- [x] [Review][Patch] ClassCastException on Event payload fields parsing [RunManager.java:1309]
- [x] [Review][Patch] Javalin server instance resource leak on start failure [ControlPlaneServer.java:139]
- [x] [Review][Patch] Incorrect local closure on multiple active runs in OandaStreamingExecutor [OandaStreamingExecutor.java:1020]
- [x] [Review][Patch] Subscription leak and Weekly Stats Cache memory growth in ControlPlaneServer [ControlPlaneServer.java:475]
- [x] [Review][Patch] Incomplete Order History Scan for Client Tag Matching [ControlPlaneServer.java:1152]
- [x] [Review][Patch] Bypassing Multi-Run Check in ReconciliationService overload [ReconciliationService.java:1184]
- [x] [Review][Patch] Reconciliation performance degradation on loadHistoricalOrders [RunManager.java:1474]
- [x] [Review][Patch] Caching mechanism defeated in getAlignmentDetails [RunManager.java:1291]
- [x] [Review][Patch] NPE in runReconciliation anomaly comparison [RunManager.java:1584]
- [x] [Review][Patch] retrieved broker closed in ControlSummaryService [ControlSummaryService.java:295]
- [x] [Review][Defer] RunManager does not submit resumed run to executor [RunManager.java:356] — deferred, pre-existing
- [x] [Review][Defer] RunManager connection leak for Broker [RunManager.java:527] — deferred, pre-existing
- [x] [Review][Defer] consecutiveTimeDrifts persistence on pause/restart [RunManager.java:370] — deferred, pre-existing
- [x] [Review][Defer] ControlPlaneServer pending orders dust comparison [ControlPlaneServer.java:1200] — deferred, pre-existing

## Dev Notes

- **OANDA API Context**: OANDA v20 distinguishes between `clientExtensions` (associated with the order) and `tradeClientExtensions` (associated with the trade generated by the order). Without specifying `tradeClientExtensions`, the resulting trade will not have client extensions set, causing `GET /v3/accounts/{accountID}/openTrades` to return them with null `clientExtensions`.
- **Untagged Position Matching**: Untagged positions are common for manual trades or legacy entries. The fallback matching logic must be restricted to cases where there is no ambiguity (i.e. only one active strategy run matches the symbol).

### References

- OANDA v20 Order endpoints: [HttpOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java#L106-L195)
- Telemetry builder: [ControlSummaryService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java#L283-L345)
- Control Plane Server endpoints: [ControlPlaneServer.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java#L980-L1039)
- Reconciliation: [ReconciliationService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ReconciliationService.java#L84-L96)
- Streaming Executor Liquidation: [OandaStreamingExecutor.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java#L415-L422)

## Dev Agent Record

### Agent Model Used

Antigravity

### Debug Log References

None

### Completion Notes List

- Propagated tradeClientExtensions in HttpOandaRestClient for market and limit/stop order placements.
- Created HttpOandaRestClientTest using local JDK HttpServer to assert OANDA request serialization of extensions.
- Modified ControlSummaryService and ControlPlaneServer position mapping to check position clientTag, with a fallback that matches untagged positions only when exactly one active run exists for the symbol.
- Overloaded ReconciliationService.reconcile to accept active runs' symbol supplier, only matching untagged positions for comparison if there is exactly one active run for the symbol.
- Updated OandaStreamingExecutor position reconciliation and emergency liquidation to prevent closing or reconciliation actions on other strategies' positions when clientTag is null/doesn't match the run's order history.
- Added a unit test in ReconciliationServiceTest verifying position reconciliation with single vs multiple active runs on the same symbol.
- Built and ran full project test suite successfully with no compilation errors or test failures.

### File List

- `trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java`
- `trading-data/src/test/java/com/martinfou/trading/data/oanda/HttpOandaRestClientTest.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ReconciliationService.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerRunExecutor.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/ReconciliationServiceTest.java`

### Change Log

- Set up tradeClientExtensions propagation for all order submissions.
- Filtered control plane telemetry positions by client tag and fallback single-run count rules.
- Filtered reconciliation comparison and emergency close targets to protect other strategies from cross-liquidation.
