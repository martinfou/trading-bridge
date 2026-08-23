# Story 45.6: Volatility-Adaptive Risk Sizing & Continuous Panama Contract Validation Suite

Status: ready-for-dev

## Story

As a quantitative researcher,
I want an ATR-based position sizing and stop calibration utility for MES strategies along with a multi-year golden test suite,
So that strategies remain resilient across low-volatility and high-volatility regimes without risking stop evaporation or capital exhaustion.

## Acceptance Criteria

1. **Given** `AtrFuturesPositionSizer.java` in `trading-strategies`:
   - Provides helper methods to compute dynamic stop distance in index points as a multiple of ATR (e.g. $1.5 \times \text{ATR}_{14}$).
   - Adjusts integer contract allocation so that account risk remains within the target percentage (e.g. 1.0% of equity).
2. **When** executing backtests over multi-year continuous MES data (2020–2026):
   - Sharpe ratio annualization correctly utilizes Globex bar frequency (~5,800 hourly periods/year).
3. **And** golden integration test `MesBacktestGoldenBaselineTest.java` runs deterministically and asserts metric consistency.

## Tasks / Subtasks

- [ ] **Task 1: Implement AtrFuturesPositionSizer (`trading-strategies`, `trading-core`)** (AC: 1)
  - [ ] Author `AtrFuturesPositionSizer.java` with volatility-scaled point stops and discrete contract allocation.
- [ ] **Task 2: Golden Baseline Integration Test (`trading-backtest`)** (AC: 2, 3)
  - [ ] Build `MesBacktestGoldenBaselineTest.java` validating Sharpe, MaxDD, and trade execution across multi-year MES dataset.
