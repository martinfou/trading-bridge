---
title: 'Fix Price Chart Polling Flicker'
type: 'bugfix'
created: '2026-06-15'
status: 'done'
baseline_commit: '8e41a18be120e2834adbd77877da4e3050d58193'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The 10-second telemetry polling in the live trading view re-fetches the entire historical bar data (500 bars) from the OANDA REST API and overwrites the Vue ref. This triggers a full repaint of the price chart and unnecessary external API load, resulting in a visible chart flicker every 10 seconds.

**Approach:** Update the frontend telemetry polling logic to fetch only trades and equity curves in the background, skipping historical bar fetches. Since live bars are already pushed and updated in real-time via WebSockets, the chart's historical bars only need to be fetched once on initial strategy selection.

## Boundaries & Constraints

**Always:** Use the WebSocket stream for real-time bar updates on the chart. Update trade history and equity curve telemetry in the background during periodic polls.

**Ask First:** If any other component depends on bars being updated every 10 seconds.

**Never:** Fetch the full 500-bar history from OANDA REST API during the 10-second polling interval or on fill event triggers.

</frozen-after-approval>

## Code Map

- `desktop/src/views/LiveTradingView.vue` -- Handles WebSocket events, telemetry polling interval, and fetches inspect telemetry.
- `desktop/src/components/TradeChart.vue` -- Renders TradingView lightweight-chart.

## Tasks & Acceptance

**Execution:**
- [x] `desktop/src/views/LiveTradingView.vue` -- Introduce `skipBars` parameter in `updateInspectTelemetry` and use it to skip fetching bars during polling and event refreshes.
- [x] `desktop/src/views/LiveTradingView.vue` -- Update the `lastEvent` watcher to mutate `inspectBars.value` in-place on WebSocket `BAR` events, and remove duplicate `updateInspectTelemetry` call to avoid redundant network requests.
- [x] `desktop/src/components/TradeChart.vue` -- Remove `{ deep: true }` from the `props.bars` watcher to prevent in-place array mutations from triggering full canvas repaints.

**Acceptance Criteria:**
- Given a live strategy run is selected, when the 10-second telemetry polling interval triggers, the app fetches summary, trades, and balances, but does not fetch bars, and the price chart remains smooth without flickering or reloading.
- Given a fill or order submitted event is received via WebSocket, the app triggers a background telemetry update that updates trades and KPIs without reloading the chart bars.
- Given the user is on another tab when a real-time `BAR` event arrives, the bar is correctly appended to `inspectBars` in memory so that when they switch back to the chart tab, no data is lost.

## Spec Change Log

- **2026-06-15 (Iteration 2)**: Added tasks to handle tab-switching data loss by updating `inspectBars` in-place and making `props.bars` watcher shallow. Also removed the redundant/overlapping parallel telemetry fetch in the WebSocket event watcher.

## Verification

**Commands:**
- `npm run build` -- expected: successful frontend compile without errors (run from `desktop/` folder).

**Manual checks (if no CLI):**
- Verify that the chart is populated initially upon strategy selection, and stays smooth without flashing/re-rendering during the 10-second updates.
- Switch to the "Trades History" tab, wait for a few WebSocket ticks, switch back to the "Price Chart" tab, and verify that the chart includes the ticks that arrived while the tab was hidden.

## Suggested Review Order

**Telemetry Optimization**

- Skips fetching bars during periodic telemetry updates to eliminate OANDA API overhead.
  [`LiveTradingView.vue:335`](../../desktop/src/views/LiveTradingView.vue#L335)

- Updates local inspectBars array in-place on ticks and removes duplicate polling call.
  [`LiveTradingView.vue:25`](../../desktop/src/views/LiveTradingView.vue#L25)

**Chart Repaint Fix**

- Removes deep watcher to prevent canvas re-renders when mutating bars in-place.
  [`TradeChart.vue:425`](../../desktop/src/components/TradeChart.vue#L425)
