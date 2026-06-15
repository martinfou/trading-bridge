# Investigation: Chart Polling Flicker

## Hand-off Brief

1. **What happened.** The 10-second telemetry polling in `LiveTradingView.vue` fetched all 500 historical bars via `getBars(runId)` from OANDA REST API and overwrote the Vue ref `inspectBars.value` on every poll. This triggered the chart component's deep watcher and forced a full `setData()` reload, causing a visible flicker.
2. **Where the case stands.** Root cause confirmed. We do not need to fetch bars during polling because the chart updates in real-time over the WebSocket.
3. **What's needed next.** Implement a `skipBars` flag in `updateInspectTelemetry()` to fetch only trades and equity curves in the background, skipping historical bar fetches.

## Case Info

| Field            | Value                                                                      |
| ---------------- | -------------------------------------------------------------------------- |
| Ticket           | N/A                                                                        |
| Date opened      | 2026-06-15                                                                 |
| Status           | Active                                                                     |
| System           | macOS                                                                      |
| Evidence sources | Code review (`LiveTradingView.vue`, `ControlPlaneServer.java`)              |

## Problem Statement

The user reports: "I still get a flicker every 10 seconds on the price chart. in the plane I see logs about fetching last 500 candles. Do we really need a 10 seconds refresh"

## Evidence Inventory

| Source | Status | Notes |
| ------ | ------ | ----- |
| `LiveTradingView.vue` | Available | Calling `updateInspectTelemetry(true)` on 10s interval fetches bars |
| `ControlPlaneServer.java` | Available | `/api/runs/{runId}/bars` defaults to OANDA limit of 500 |

## Investigation Backlog

| # | Path to Explore | Priority | Status | Notes |
| - | --------------- | -------- | ------ | ----- |
| 1 | Check how `updateInspectTelemetry(true)` fetches bars and if it causes re-render | High | Done | Verified |
| 2 | Investigate why `fetching last 500 candles` happens on the backend on every poll | High | Done | Verified |
| 3 | Evaluate if the 10-second interval is necessary or can be adjusted | Medium | Done | Verified |

## Timeline of Events

| Time | Event | Source | Confidence |
| ---- | ----- | ------ | ---------- |
| 2026-06-14 21:54 | User reports 10-second flicker and OANDA fetch logs | User request | Confirmed |
| 2026-06-15 01:56 | Traced `getBars` call in `updateInspectTelemetry` during polling | Code inspection | Confirmed |

## Confirmed Findings

### Finding 1: Telemetry Refreshes Fetch Historical Bars
**Evidence:** `LiveTradingView.vue` line 348 (`getBars(runId)`) and line 126 (`updateInspectTelemetry(true)`).

**Detail:** The frontend polls `fetchSummary()` every 10 seconds, which calls `updateInspectTelemetry(true)`. This triggers a parallel fetch of 500 bars from OANDA REST API and overwrites `inspectBars.value`, triggering the `TradeChart.vue` watcher.

### Finding 2: Live Updates Arrive Over WebSockets
**Evidence:** `LiveTradingView.vue` line 27-31:
```typescript
  if (event.type === 'BAR' && event.payload && event.payload.bar) {
    if (tradeChartRef.value) {
      tradeChartRef.value.updateBar(event.payload.bar)
    }
  }
```

**Detail:** Real-time price updates (current in-progress bar) are pushed over WebSockets and updated dynamically. Re-fetching historical bars from OANDA REST API during polling is completely redundant.

## Deduced Conclusions

### Deduction 1: Redundant Fetches Cause the Flicker
**Based on:** Finding 1 and Finding 2.

**Reasoning:** Since live prices are pushed over WebSocket, fetching 500 candles every 10 seconds is only useful for initial load. Updating `inspectBars.value` triggers Vue's reactivity, forcing `TradeChart.vue` to update the series via `setData()`, which repaints the entire canvas.

**Conclusion:** Skipping bar fetches during polling refreshes will eliminate the flicker and OANDA API overhead completely.

## Hypothesized Paths

### Hypothesis 1: Background update of telemetry still re-renders/resets bars unnecessarily
- **Status:** Confirmed
- **Theory:** Assigning new bars triggers the watcher and canvas reload.
- **Resolution:** Confirmed by code trace in `TradeChart.vue` and `LiveTradingView.vue`.

### Hypothesis 2: Fetching 500 candles on every poll is inefficient and unnecessary
- **Status:** Confirmed
- **Theory:** OANDA REST API is queried for 500 bars on every 10s tick.
- **Resolution:** Confirmed by server logs and code trace in `ControlPlaneServer.java` and `RunManager.java`.

## Missing Evidence

*None.*

## Source Code Trace

| Element | Detail |
| ------- | ------ |
| Error origin | `LiveTradingView.vue:348` |
| Trigger | `pollTimer = setInterval(fetchSummary, 10000)` |
| Condition | Triggered every 10 seconds when a run is active |
| Related files | `TradeChart.vue`, `ControlPlaneServer.java` |

## Conclusion

**Confidence:** High

The root cause of the 10-second flicker is the redundant loading of 500 historical candles from OANDA on every telemetry poll. By introducing a `skipBars` parameter to `updateInspectTelemetry()`, we can skip the bar fetch during polling and fills, leaving `inspectBars.value` stable and keeping the chart update smooth.

## Recommended Next Steps

### Fix direction

Modify `updateInspectTelemetry` in `LiveTradingView.vue` to support `skipBars = true` and call it with `skipBars = true` from the polling loop and fill event handlers.
