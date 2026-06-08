# Story 26.4: GUI Backtest History View

Status: done

## Story

As a trader,
I want to consult and filter my backtest run history on a graphical dashboard,
so that I can easily identify the best-performing strategy configurations.

## Acceptance Criteria

1. **Given** the desktop application
   **When** I navigate to the "Backtest History" view
   **Then** it displays a table of all persisted backtest runs showing: Date/Time, Strategy ID, Symbol, Net Profit, Sharpe Ratio, Profit Factor, Max Drawdown, and Parameters summary.

2. **Given** the search and filter inputs on the view
   **When** I change values for Symbol, Strategy, Min Sharpe, or Min Profit Factor
   **Then** the list is dynamically filtered and reloaded from the API (`GET /api/backtests`) within 200ms.

3. **Given** a row in the backtest runs table
   **When** I click the row
   **Then** a drawer or modal panel opens to display the detailed parameters map and full metrics.
   **And** it renders the equity curve and drawdown curve charts using `Chart.js` (or the existing app charting library).

## Tasks / Subtasks

- [ ] **Task 1: Desktop Route & View Scaffold (AC 1)**
  - [ ] Create `BacktestHistoryView.vue` in `desktop/src/views/`.
  - [ ] Register the route `/backtest-history` (or wire it into navigation layout).
  - [ ] Scaffold the history grid table with responsive columns.
- [ ] **Task 2: API Client Integration & Filtering (AC 2)**
  - [ ] Extend the Vue composable or API client (`useControlPlane`) to fetch backtest summaries with query arguments.
  - [ ] Implement query debounce and reactive filters on the view.
- [ ] **Task 3: Detail Drawer & Equity Chart (AC 3)**
  - [ ] Create `BacktestDetailsPanel.vue` to show parameter details.
  - [ ] Integrate a chart component (`BacktestEquityChart.vue`) that requests details from `GET /api/backtests/{runId}` and draws the line chart of equity and drawdown.

## Dev Notes

- Avoid Tailwind CSS unless it is already configured in the Electron project; use vanilla CSS/Scoped Vue styles consistent with the desktop app's aesthetic rules.
- Verify page load times are fast under mocked larger datasets (up to 1,000 runs).
