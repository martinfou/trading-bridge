# Story 45.1: Discrete Tick Alignment & Price Quantization Engine

Status: ready-for-dev

## Story

As a quantitative trader,
I want the backtesting engine and order processing pipeline to enforce discrete tick increments ($0.25 on MES, $0.10 on M2K/EMD),
So that limit orders, stop losses, and take profits execute exclusively on legitimate exchange tick boundaries and cannot produce unfillable floating-point fractional-tick fills.

## Acceptance Criteria

1. **Given** `FuturesContract.java` and `FuturesRegistry.java`:
   - Every contract definition contains `minTick` ($0.25 for MES/MNQ, $0.10 for M2K/EMD) and `tickValue` ($1.25 for MES).
   - Methods `quantizePrice(double price)` and `isValidTick(double price)` are provided.
2. **When** an order or position target with floating-point prices (e.g. `5012.3333`) is processed for an MES contract:
   - The price is automatically quantized to the nearest valid $0.25 tick (`5012.25` or `5012.50`).
3. **When** `FuturesValuationModel.calculatePnL()` computes realized or unrealized PnL:
   - PnL is calculated from quantized prices and is an exact multiple of the contract's tick value.
4. **And** unit test `FuturesTickQuantizationTest.java` passes with 100% boundary coverage on off-tick orders.

## Tasks / Subtasks

- [ ] **Task 1: Add Tick Quantization to Futures Model (`trading-core`)** (AC: 1, 2)
  - [ ] Add `quantizePrice(double rawPrice)` in `FuturesContract.java`.
  - [ ] Update `FuturesValuationModel.java` to validate and quantize entry/exit prices.
- [ ] **Task 2: Integrate Quantization in Order Processing (`trading-backtest`)** (AC: 2, 3)
  - [ ] In `BacktestEngine.processSingleOrder()` and `executeFill()`, apply price quantization for futures instruments.
- [ ] **Task 3: Unit Tests (`trading-core`, `trading-backtest`)** (AC: 4)
  - [ ] Author `FuturesTickQuantizationTest.java` verifying price rounding, tick validity, and exact PnL multiples.
