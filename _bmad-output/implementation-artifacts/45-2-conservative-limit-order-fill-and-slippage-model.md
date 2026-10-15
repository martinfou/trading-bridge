# Story 45.2: Conservative Limit & Stop Execution Simulation (Trade-Through & News Slippage)

Status: ready-for-dev

## Story

As a quantitative trader,
I want the backtesting engine to simulate conservative limit order fills (requiring prices to trade through the limit level by at least 1 tick) and realistic tick-based stop slippage,
So that strategy backtests do not present artificially inflated win rates caused by optimistic touch fills at bar extremes.

## Acceptance Criteria

1. **Given** `BacktestEngine.java`:
   - Introduce `FillMode` enum: `TOUCH` (legacy), `TRADE_THROUGH` (default conservative).
2. **When** `FillMode == TRADE_THROUGH`:
   - A BUY limit order at `5000.00` is only filled if `bar.low() < 5000.00` (trades through to at least `4999.75`).
   - A SELL limit order at `5000.00` is only filled if `bar.high() > 5000.00` (trades through to at least `5000.25`).
3. **When** stop-loss orders are triggered:
   - Stop slippage is applied in discrete tick steps (minimum 1 tick on normal bars, dynamic scaling on high-range bars).
4. **And** unit test `ConservativeFillSimulationTest.java` verifies that touch-only bars do not fill limit orders under `TRADE_THROUGH`.

## Tasks / Subtasks

- [ ] **Task 1: Implement FillMode & Trade-Through Matching (`trading-backtest`)** (AC: 1, 2)
  - [ ] Add `FillMode` enum (`TOUCH`, `TRADE_THROUGH`) and fluent setter `withFillMode(FillMode mode)` to `BacktestEngine.java`.
  - [ ] Update `calculateLimitFillPrice()` in `BacktestEngine.java` to enforce trade-through conditions.
- [ ] **Task 2: Enhance Stop Loss Slippage Engine (`trading-backtest`)** (AC: 3)
  - [ ] Update `checkStopLossesTakeProfits()` to calculate tick-based slippage rather than purely continuous percentage.
- [ ] **Task 3: Unit Tests (`trading-backtest`)** (AC: 4)
  - [ ] Author `ConservativeFillSimulationTest.java` asserting fill rejections on exact High/Low touches and proper execution on trade-throughs.
