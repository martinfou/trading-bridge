# Story 23.1: Integration of MonteCarloSimulation and Endpoint API

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want to expose the existing MonteCarloSimulation functionality via the Control Plane HTTP API,
So that frontend components can retrieve Monte Carlo distribution results and robustness scores.

## Acceptance Criteria

1. **Given** a finished backtest run record with trades
2. **When** `GET /api/runs/{runId}/monte-carlo?runs=1000&blockSize=3` is called
3. **Then** the system computes the Monte Carlo shuffles in parallel using ForkJoinPool
4. **And** returns a JSON response containing percentile arrays (5th, 25th, 50th, 75th, 95th) for P&L, drawdown, Sharpe ratio, worst/best P&L, VaR 95% (5th percentile of P&L), and probability of loss (P&L < 0)
5. **And** the operation completes in under 2 seconds for standard backtests

## Tasks / Subtasks

- [ ] **Add Javalin Route** (AC: #2)
  - [ ] Register `GET /api/runs/{runId}/monte-carlo` in `ControlPlaneServer.java`
  - [ ] Extract optional query parameters: `runs` (default: 1000) and `blockSize` (default: 3)
- [ ] **Retrieve Run and Trades** (AC: #1)
  - [ ] Load `RunRecord` from `RunManager` by `runId` (return 404 if not found)
  - [ ] Retrieve or reconstruct trades as `List<Map<String, Object>>` (either from the run's `endedPayload` if completed, or reconstruct via `reconstructTradesFromFills` from EventStore)
- [ ] **Reconstruct Baseline Result & Run Simulation** (AC: #3, #5)
  - [ ] Map serialized trade maps to `com.martinfou.trading.core.Trade`. To preserve exact PnL and avoid rounding differences, instantiate `Trade` as anonymous classes overriding `pnl()` to return the mapped `"pnl"` value
  - [ ] Extract `initialCapital` from endedPayload or configuration snapshot
  - [ ] Build baseline `BacktestResult` using the builder (with strategy name, initial capital, trades list, and `periodsPerYear` defaulting to 252.0)
  - [ ] Instantiate `MonteCarloSimulation` and call `run()`
- [ ] **Calculate Percentiles and Return JSON** (AC: #4)
  - [ ] Calculate percentiles at quantiles `{0.05, 0.25, 0.50, 0.75, 0.95}` for sorted list of P&L, max drawdown, and Sharpe ratio
  - [ ] Return JSON response containing:
    - `runId`: Run identifier
    - `runs`: Number of simulations run
    - `blockSize`: Block size used
    - `pnlPercentiles`: List of 5 percentiles for P&L
    - `drawdownPercentiles`: List of 5 percentiles for Drawdown
    - `sharpePercentiles`: List of 5 percentiles for Sharpe ratio
    - `worstPnl`: Worst P&L seen
    - `bestPnl`: Best P&L seen
    - `var95`: Value at Risk at 95% (5th percentile of P&L)
    - `probabilityOfLoss`: Probability of loss as a percentage (P&L < 0)
- [ ] **Add Integration Tests** (AC: #5)
  - [ ] Add integration test case in `ControlPlaneServerTest.java` querying the route and verifying the percentile values are calculated and returned correctly

## Dev Notes

* **Reuse Existing Simulation Logic**: Do not duplicate Monte Carlo engine logic. The `com.martinfou.trading.backtest.MonteCarloSimulation` class handles ForkJoinPool threading, block bootstrap shuffling, and percentiles.
* **Instantiating Trades for Simulation**:
  ```java
  trades.add(new Trade(symbol, side, entryPrice, exitPrice, quantity, entryTime, exitTime) {
      @Override
      public double pnl() {
          return pnlValue; // Exact PnL value from the map
      }
  });
  ```
* **Performance**: 1000 runs of the simulation execute in parallel and should complete in under 2 seconds.
* **Architecture Alignment**: The route belongs in `ControlPlaneServer` next to the `/api/runs/...` group of routes.

### Project Structure Notes

* Target module: **`trading-runtime`** (`com.martinfou.trading.runtime.ControlPlaneServer`).
* Simulation classes imported from **`trading-backtest`** (`com.martinfou.trading.backtest.*`).

### References

* **Backlog specification**: `_bmad-output/planning-artifacts/epics.md` § Epic 23 / Story 23.1
* **Monte Carlo engine source**: `com.martinfou.trading.backtest.MonteCarloSimulation`
* **Run Record schema**: `com.martinfou.trading.runtime.RunRecord`
* **Javalin server controllers**: `com.martinfou.trading.runtime.ControlPlaneServer`

## Dev Agent Record

### Agent Model Used

Gemini 3.5 Flash (Medium)

### Debug Log References

### Completion Notes List

### File List

```
```
