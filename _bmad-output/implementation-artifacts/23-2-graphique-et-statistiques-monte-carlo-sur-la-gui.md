# Story 23.2: Graphical Representation and Statistics on the Desktop GUI

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Martin,
I want to see the Monte Carlo path distribution overlay and summary statistics in the Desktop GUI,
So that I can visually verify strategy robustness.

## Acceptance Criteria

1. **Given** a strategy run detail view in the Desktop app
2. **When** I click the "Monte Carlo" tab or section
3. **Then** the app requests the Monte Carlo API endpoint
4. **And** renders an interactive chart showing the median (50th), 5th, and 95th percentile paths overlaying the baseline equity curve
5. **And** displays statistics: VaR 95% P&L, Mean/Median Drawdown, Best/Worst P&L, and Loss Probability

## Tasks / Subtasks

- [x] **Extend useControlPlane Composable** (AC: #3)
  - [x] Implement `getMonteCarlo(runId, runs, blockSize)` method calling the `/api/runs/{runId}/monte-carlo` endpoint
- [x] **Create MonteCarloChart Component** (AC: #4)
  - [x] Implement `MonteCarloChart.vue` under `desktop/src/components/` using `lightweight-charts`
  - [x] Support rendering multiple line series: baseline (Orange, width 3), 5th percentile (Red, width 1.5, dashed), 50th percentile (Blue, width 1.5, dashed), and 95th percentile (Green, width 1.5, dashed)
- [x] **Integrate in ResultsView.vue** (AC: #1, #2, #5)
  - [x] Add the "Monte Carlo" tab next to the other tabs ("Overview", "Price Chart", "Trades")
  - [x] Maintain state for Monte Carlo statistics and load them on demand when the tab is clicked
  - [x] Implement a client-side Monte Carlo path simulation loop using the run's trades list to generate exact 5th, 50th, and 95th percentile equity curves to plot on the chart
  - [x] Render a beautiful statistics grid showing VaR 95% P&L, Loss Probability, Worst/Best P&L, and percentiles
- [x] **Verify in Desktop App**
  - [x] Run dev server and visually inspect the chart and statistics layout to ensure rich aesthetics

## Dev Notes

* **Client-Side Path Simulation**: The backend API provides final statistic percentiles. To render the visual paths on the chart without transmitting large path data arrays, run a 100-run Monte Carlo shuffle loop in Vue using `trades.value` to generate the 5th, 50th, and 95th percentile paths.
* **Lightweight Charts Styling**:
  ```typescript
  import { LineStyle } from 'lightweight-charts'
  // set lineStyle: LineStyle.Dashed for percentiles
  ```
* **Aesthetics**: Use glassmorphism cards and a clean flex grid for the stats view.

### Project Structure Notes

* Touched files:
  - `desktop/src/composables/useControlPlane.ts`
  - `desktop/src/views/ResultsView.vue`
  - `desktop/src/components/MonteCarloChart.vue` [NEW]

### References

* **Vision**: `docs/VISION.md` §3.3 & §3.7
* **Epics**: `_bmad-output/planning-artifacts/epics.md` § Epic 23 / Story 23.2

## Dev Agent Record

### Agent Model Used

Gemini 3.5 Flash (Medium)

### Debug Log References

### Completion Notes List

### File List

```
```
