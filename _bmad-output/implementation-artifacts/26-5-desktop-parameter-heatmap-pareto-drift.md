# Story 26.5: Parameter Heatmap, Pareto Frontier & Drift Analytics

Status: done

## Story

As a quantitative trader,
I want to consult parameter sensitivity heatmaps, Pareto Frontier charts, and live performance drift monitors,
so that I can evaluate parameter robustness, optimize my risk-reward settings, and detect model degradation.

## Acceptance Criteria

1. **Given** multiple backtests for the same strategy with different parameter sets
   **When** I load the Parameter Sensitivity Heatmap in the GUI
   **Then** the backend endpoint `GET /api/backtests/analytics/heatmap` returns the aggregated matrix data.
   **And** the GUI renders a 2D heatmap grid (e.g. fast period on X-axis, slow period on Y-axis) showing color-coded performance (e.g. Profit Factor or Sharpe Ratio).

2. **Given** a set of backtest runs for a strategy
   **When** I load the Pareto Frontier Scatter Plot in the GUI
   **Then** the backend endpoint `GET /api/backtests/analytics/pareto` returns risk vs. reward pairs.
   **And** the GUI renders a scatter plot (Max Drawdown on X-axis, Sharpe/Return on Y-axis) and highlights the non-dominated configurations (Pareto Frontier).

3. **Given** a running strategy in live/paper mode and its corresponding backtest run (matched via `parameter_hash`)
   **When** I query the strategy status
   **Then** the system checks if the live performance (drawdown, win rate) significantly deviates from the historical backtest baseline distribution.
   **And** if thresholds are breached, it flags a warning or alerts the user of a performance drift.

## Tasks / Subtasks

- [ ] **Task 1: Analytics Calculators on Backend (AC 1, AC 2, AC 3)**
  - [ ] Implement analytics logic in `trading-runtime` (or `trading-backtest`).
  - [ ] Create endpoints:
    - `GET /api/backtests/analytics/heatmap` (takes strategyId, param1, param2, targetMetric)
    - `GET /api/backtests/analytics/pareto` (takes strategyId)
  - [ ] Implement drift check comparison logic matching live metrics against backtest thresholds.
- [ ] **Task 2: UI Heatmap and Scatter Components (AC 1, AC 2)**
  - [ ] Create `ParameterSensitivityHeatmap.vue` inside the desktop app.
  - [ ] Create `ParetoFrontierChart.vue` inside the desktop app.
  - [ ] Wire these components to receive fetched data and render charts via `Chart.js`.
- [ ] **Task 3: Drift Status Widget (AC 3)**
  - [ ] Update the live/paper strategy monitoring view to display drift indicators and warnings.
  - [ ] Write integration/unit tests validating the drift calculation and heatmap aggregation logic.

## Dev Notes

- Ensure heavy statistical/analytical calculations (e.g., Pareto dominance sorting, drift thresholds) are done on the Java backend to keep the Vue frontend fast and clean.
- Ensure proper logging and error handling for cases where parameters do not map nicely to a 2D grid.
