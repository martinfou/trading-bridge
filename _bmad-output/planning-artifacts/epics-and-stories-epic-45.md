# Epics & Stories — Epic 45: MES & CME Futures Backtesting Realism Engine & Microstructure Hardening

> Phase 3 — Solutioning & Epic Refinement  
> Project: Trading Bridge — Multi-Asset Trading Platform  
> Module: `trading-core`, `trading-backtest`, `trading-data`, `desktop/`  
> Objective: Eliminate backtesting biases, unrealistic fill assumptions, uncosted contract rollovers, timezone phase shifts, and regulatory misclassifications for CME Micro E-mini S&P 500 (`MES`) and electronic futures.

---

## Epic 45 Overview

Backtesting **MES (Micro E-mini S&P 500 futures)** requires specialized modeling for discrete $0.25 tick boundaries, conservative trade-through limit fills, unbundled exchange/NFA commission structures, realistic calendar spread rollovers, CME Globex trading hours (Sunday 6pm ET open, 5pm-6pm daily halt), timezone-accurate historical data ingestion, and exemption from equity-specific FINRA Pattern Day Trader (PDT) rules.

**Epic 45** delivers an industrial-grade futures backtesting engine that mirrors live CME execution, prevents inflated backtest performance metrics, and ensures strategy robustness across varying market volatility regimes.

---

## User Stories Inventory

| Story ID | Title | Priority | Effort | Description | Dependencies |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`45-1`** | **Discrete Tick Alignment & Price Quantization Engine** | P0 | S | Enforce minimum tick increments ($0.25 on MES) across order creation, indicator rounding, and fill validation; reject or snap off-tick prices. | — |
| **`45-2`** | **Conservative Limit & Stop Execution Simulation (Trade-Through & News Slippage)** | P0 | M | Replace naive touch fills with conservative trade-through logic; implement discrete tick-based slippage and event volatility expansion for stop fills. | 45.1 |
| **`45-3`** | **Futures Rollover Spread Execution & Friction Accounting** | P0 | M | Model calendar spread rollovers in `BacktestEngine.java` with full round-turn commission and bid-ask spread friction accounting. | 45.1 |
| **`45-4`** | **CME Globex Session Calendar & Margin Regulation Alignment (PDT Exemption)** | P0 | M | Implement `CmeGlobexMarketCalendar` (RTH vs ETH, maintenance halts, holiday early closes) and exempt futures from FINRA PDT $25k restriction in `MarginTracker.java`. | — |
| **`45-5`** | **IBKR Historical Data Timezone Normalization (`America/New_York` to UTC)** | P0 | S | Fix `IbkrHistoricalDataLoader.java` to parse US Eastern Time with daylight saving transitions (EDT/EST) to eliminate 4–5 hour bar phase shifts. | — |
| **`45-6`** | **Volatility-Adaptive Risk Sizing & Continuous Panama Contract Validation Suite** | P1 | M | Add ATR-scaled contract sizing helpers and golden test suite validating backtest integrity across 2020–2026 low-vol and high-volatility market regimes. | 45.2, 45.3, 45.4 |

---

## Detailed Story Breakdowns

### Story 45.1: Discrete Tick Alignment & Price Quantization Engine
* **File:** [`45-1-discrete-tick-alignment-and-quantization-engine.md`](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/45-1-discrete-tick-alignment-and-quantization-engine.md)
* **Goal:** Ensure all order prices, stop losses, and take profits conform to the instrument's minimum tick increment.
* **Scope:**
  - Enhance `FuturesContract.java` and `FuturesRegistry.java` with `quantizePrice(double price)` and `isValidTick(double price)`.
  - For `MES`: `minTick = 0.25`, snapping prices via `Math.round(price * 4.0) / 4.0`.
  - Update `Order.java` and `BacktestEngine.java` to enforce tick quantization prior to fill processing.
* **Gherkin Acceptance Criteria:**
  - **Given** an MES order with a calculated price of `5012.3333`,
  - **When** the order is submitted to the engine,
  - **Then** the order price is automatically quantized to `5012.25` (or `5012.50` depending on order direction/rounding rule).
  - **When** `FuturesValuationModel.calculatePnL()` is executed,
  - **Then** PnL equals strictly an integer multiple of the tick value ($1.25 per contract).

---

### Story 45.2: Conservative Limit & Stop Execution Simulation (Trade-Through & News Slippage)
* **File:** [`45-2-conservative-limit-order-fill-and-slippage-model.md`](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/45-2-conservative-limit-order-fill-and-slippage-model.md)
* **Goal:** Eliminate the "touch fill" bias where limit orders are assumed 100% filled when price merely grazes the limit level.
* **Scope:**
  - In `BacktestEngine.java`, implement `TradeThrough` mode: Buy limit fills only if `bar.low() < order.price()` (strictly traded through by $\ge 1$ tick).
  - Configurable fill mode: `TOUCH`, `TRADE_THROUGH` (default for realistic testing), and `PROBABILISTIC_VOLUME`.
  - Implement tick-based stop loss slippage (minimum 1 tick on regular stops, dynamic 4–12 ticks during high-volatility news bars).
* **Gherkin Acceptance Criteria:**
  - **Given** a Buy Limit order at `5000.00` and a bar with `low = 5000.00`,
  - **When** backtest runs in `TRADE_THROUGH` mode,
  - **Then** the order remains unfilled.
  - **When** a subsequent bar has `low = 4999.75` (1 tick through),
  - **Then** the order fills at `5000.00`.

---

### Story 45.3: Futures Rollover Spread Execution & Friction Accounting
* **File:** [`45-3-futures-rollover-spread-and-cost-accounting.md`](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/45-3-futures-rollover-spread-and-cost-accounting.md)
* **Goal:** Fully model the transaction costs, calendar spread slippage, and PnL continuity during quarterly futures rollovers.
* **Scope:**
  - In `BacktestEngine.checkFuturesRollovers()`, route rollover closing and opening trades through standard cost accounting.
  - Apply configured commission (e.g. $0.62 per contract $\times 2$ legs = $1.24) and 1 tick calendar spread slippage.
  - Track rollover trade groups via `rolloverGroupId` without artificially inflating win/loss trade count metrics.
* **Gherkin Acceptance Criteria:**
  - **Given** an open MES position during a quarterly rollover (e.g. March to June),
  - **When** `checkFuturesRollovers()` executes,
  - **Then** total commissions increase by 2x the per-trade commission, and equity reflects the roll spread friction.
  - **When** backtest results are compiled,
  - **Then** rollover transactions are clearly categorized in trade analytics.

---

### Story 45.4: CME Globex Session Calendar & Margin Regulation Alignment (PDT Exemption)
* **File:** [`45-4-cme-globex-session-calendar-and-pdt-exemption.md`](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/45-4-cme-globex-session-calendar-and-pdt-exemption.md)
* **Goal:** Accurately simulate CME Globex trading hours and exempt CFTC-regulated futures from FINRA PDT restrictions.
* **Scope:**
  - Create `CmeGlobexMarketCalendar.java` handling:
    - Sunday 6:00 PM ET Open to Friday 5:00 PM ET Close.
    - Daily maintenance halt (5:00 PM – 6:00 PM ET).
    - Session classification: RTH (9:30 AM – 4:00 PM ET) vs ETH (Overnight).
    - US Federal Holiday early closes (1:00 PM ET).
  - Update `MarginTracker.java` to check `AssetValuationRegistry.resolve(symbol).assetClass()`:
    - If `AssetClass.FUTURES`, bypass PDT day-trade count restrictions regardless of equity balance.
* **Gherkin Acceptance Criteria:**
  - **Given** an account with $5,000 equity trading MES,
  - **When** executing 10 day trades in a single week,
  - **Then** `MarginTracker.evaluate()` returns `pdtRestricted = false`.
  - **When** a bar occurs on Saturday at 14:00 UTC or during daily maintenance (17:30 ET),
  - **Then** `CmeGlobexMarketCalendar.isTradingBar()` returns `false`.

---

### Story 45.5: IBKR Historical Data Timezone Normalization (`America/New_York` to UTC)
* **File:** [`45-5-ibkr-historical-data-timezone-normalization.md`](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/45-5-ibkr-historical-data-timezone-normalization.md)
* **Goal:** Eliminate timestamp phase shifts in historical bar data exported from Interactive Brokers TWS.
* **Scope:**
  - In `IbkrHistoricalDataLoader.java`, parse compact timestamps with an explicit source `ZoneId` (defaulting to `America/New_York` for US Futures).
  - Account for Eastern Daylight Time (EDT, UTC-4) and Eastern Standard Time (EST, UTC-5).
  - Support configurable source timezone via overloaded `loadCsv(Path path, String symbol, ZoneId sourceZone)`.
* **Gherkin Acceptance Criteria:**
  - **Given** an IBKR bar timestamp string `"20240315 09:30:00"`,
  - **When** parsed during EDT,
  - **Then** the resulting `Instant` is exactly `"2024-03-15T13:30:00Z"` (13:30 UTC, matching 9:30 AM ET market open).

---

### Story 45.6: Volatility-Adaptive Risk Sizing & Continuous Panama Contract Validation Suite
* **File:** [`45-6-volatility-adaptive-sizing-and-regime-validation.md`](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/45-6-volatility-adaptive-sizing-and-regime-validation.md)
* **Goal:** Provide adaptive sizing tools for MES strategies and construct a golden benchmark test suite.
* **Scope:**
  - Implement `AtrFuturesPositionSizer` that scales stop distance and contract sizing based on ATR volatility.
  - Build golden regression test `MesBacktestGoldenBaselineTest.java` running multi-year MES data (2020–2026).
  - Validate Sharpe ratio calculation on Globex bar frequency (~5,800 periods/year).
* **Gherkin Acceptance Criteria:**
  - **Given** a 3-year continuous MES dataset,
  - **When** backtested across low-VIX and high-VIX regimes,
  - **Then** strategy metrics match expected golden benchmarks within 0.1% tolerance.

---

## Blue Team / Red Team Hardening Matrix

| # | Vulnerability / Attack Vector | Severity | Hardened Mitigation | Enforcing Story |
| :--- | :--- | :---: | :--- | :---: |
| **1** | **Fractional Contract Rounding Explosion** | 🔴 HIGH | Force integer contracts $\ge 1$ and validate initial margin before fill dispatch. | **45.1 / 45.4** |
| **2** | **Touch Fill Free Money Illusion** | 🔴 HIGH | Enforce `TRADE_THROUGH` execution mode on limit orders in all qualification backtests. | **45.2** |
| **3** | **Uncosted Synthetic Rollover Arbitrage** | 🔴 HIGH | Deduct double commission + spread slippage on every simulated contract roll. | **45.3** |
| **4** | **4-Hour Timezone Inversion Ambush** | 🔴 HIGH | Strict `America/New_York` timezone parsing with daylight saving adjustment. | **45.5** |
| **5** | **False PDT Lockout on Micro Accounts** | 🟠 MED | Explicit asset class branching in `MarginTracker`: exempt futures from FINRA rules. | **45.4** |
| **6** | **Fixed Point Stop Evaporation in Vol Spikes**| 🟠 MED | Volatility-adaptive ATR stop scaling recommendations for all CME equity index models. | **45.6** |
