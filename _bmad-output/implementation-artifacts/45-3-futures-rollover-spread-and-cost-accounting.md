# Story 45.3: Futures Rollover Spread Execution & Friction Accounting

Status: ready-for-dev

## Story

As a quantitative trader,
I want quarterly contract rollovers in the backtest engine to deduct realistic calendar spread commissions and bid-ask slippage,
So that holding long-term swing positions across contract expirations reflects actual execution friction and does not distort strategy trade statistics.

## Acceptance Criteria

1. **Given** an open MES position undergoing a quarterly rollover in `BacktestEngine.checkFuturesRollovers()`:
   - The closing leg and reopening leg are routed through full commission calculation (e.g. $0.62 per contract $\times 2 = $1.24 total roll commission).
   - Calendar spread bid-ask slippage (default 1 tick = $0.25 / $1.25) is deducted from realized equity.
2. **When** results are compiled in `BacktestResult.java`:
   - Rollover trades are tagged with `rolloverGroupId` and rollover costs are recorded in total commissions/slippage.
   - Total trade count and win/loss ratios distinguish between strategy exits and administrative contract rolls.
3. **And** unit test `FuturesRolloverAccountingTest.java` verifies commission and slippage deductions during multi-quarter hold backtests.

## Tasks / Subtasks

- [ ] **Task 1: Upgrade Rollover Execution in BacktestEngine (`trading-backtest`)** (AC: 1)
  - [ ] Update `checkFuturesRollovers()` in `BacktestEngine.java` to apply `calcCommission()` and `applySlippage()` on both roll legs.
- [ ] **Task 2: Update Performance Metrics & Trade Tagging (`trading-backtest`, `trading-core`)** (AC: 2)
  - [ ] Ensure `BacktestResult.java` correctly incorporates rollover fees into net PnL and equity curve.
- [ ] **Task 3: Unit Tests (`trading-backtest`)** (AC: 3)
  - [ ] Author `FuturesRolloverAccountingTest.java` verifying exact fee deduction and equity progression.
