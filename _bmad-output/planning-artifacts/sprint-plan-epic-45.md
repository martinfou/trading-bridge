# Sprint Plan — Epic 45: MES & CME Futures Backtesting Realism Engine & Microstructure Hardening

## Executive Summary

Backtesting quantitative strategies against **MES (Micro E-mini S&P 500)** in `trading-bridge` revealed critical divergences from live execution reality:
1. Limit order fills relying on low/high "touch" rather than realistic trade-through execution.
2. Futures rollover simulation executing at nominal prices without deducting calendar spread commissions or slippage.
3. Unquantized floating-point order prices executing on invalid $0.25 tick boundaries.
4. CME Globex sessions, daily 5pm-6pm maintenance halts, and holiday schedules being evaluated under Spot Forex calendar rules.
5. Ingestion of IBKR historical export timestamps treating US Eastern time as UTC (4–5 hour shift).
6. Erroneous application of FINRA PDT ($25k minimum) rules to CFTC-regulated futures accounts.

**Epic 45** executes a full sprint to eliminate these biases, providing a production-grade futures simulation engine.

---

## Realistic Cost, Position Sizing & Slippage Benchmark Table

| Asset Class | Instrument Examples | Lot Size / Unit Type | Typical Commission / side | Realistic Slippage | Default Recommended Capital |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **⚡ CME Micro Futures** | `MES`, `MNQ`, `M2K` | **1.0 – 5.0 contracts** (discrete integer) | **$0.62** / contract ($1.24 round-trip: $0.25 IBKR + $0.35 CME + $0.02 NFA) | **0.005% – 0.010%** (1 tick / 0.25 pt) | **$10,000 – $50,000** |
| **⚡ CME E-mini Futures** | `EMD` (MidCap 400), `ES`, `NQ` | **1.0 contract** | **$1.25** / contract ($2.50 round-trip) | **0.020% – 0.030%** (0.10 pt) | **$30,000 – $50,000** |
| **💱 Forex Majors & Crosses** | `EUR/USD`, `GBP/USD`, `USD/JPY` | **0.01 – 0.10 lots** (1,000 – 10,000 units) | **$0.035** / 0.01 lot ($0.07 RT on ECN Core) or **$0.00** on Spread accounts | **0.005% – 0.010%** (0.5 – 1.0 pip) | **$500 – $2,000** |

---

## Sprint Execution Phases

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 Epic 45 Sprint Phases                                  │
├───────────────────────┬───────────────────────────────────┬────────────────────────────┤
│ Phase                 │ Stories Covered                   │ Target Deliverables        │
├───────────────────────┼───────────────────────────────────┼────────────────────────────┤
│ Phase 1: Core Engine  │ Story 45.1 (Tick Quantization)    │ FuturesContract & Engine   │
│ Pricing & Fills       │ Story 45.2 (Trade-Through Fills)  │ tick-level fill simulation │
├───────────────────────┼───────────────────────────────────┼────────────────────────────┤
│ Phase 2: Friction &   │ Story 45.3 (Rollover Accounting)  │ BacktestEngine rollover    │
│ Calendar Alignment    │ Story 45.4 (CME Globex & PDT)     │ CmeGlobexCalendar & Margin │
├───────────────────────┼───────────────────────────────────┼────────────────────────────┤
│ Phase 3: Data &       │ Story 45.5 (IBKR Timezone UTC)    │ IbkrHistoricalDataLoader   │
│ Volatility Scaling    │ Story 45.6 (ATR Sizing & Golden)  │ Golden regression suite    │
└───────────────────────┴───────────────────────────────────┴────────────────────────────┘
```

---

## User Stories & Implementation Tasks

### Story 45.1: Discrete Tick Alignment & Price Quantization Engine
* **Goal**: Enforce discrete tick boundaries ($0.25 / $1.25 value on MES) across all order execution pathways.
* **Tasks**:
  1. Add `quantizePrice(double price)` and `isValidTick(double price)` to `FuturesContract.java` and `FuturesRegistry.java`.
  2. Update `BacktestEngine.executeFill()` and `processSingleOrder()` to quantize order prices before checking boundaries.
  3. Create unit test `FuturesTickQuantizationTest.java` verifying off-tick prices (e.g. `5012.333`) snap properly.
* **Acceptance**:
  - [ ] All MES fills occur at valid multiples of $0.25.
  - [ ] Trade PnL is strictly an integer multiple of $1.25 per contract.

### Story 45.2: Conservative Limit & Stop Execution Simulation (Trade-Through & News Slippage)
* **Goal**: Prevent optimistic touch fills and model volatility-dependent stop slippage.
* **Tasks**:
  1. Add `FillMode` enum to `BacktestEngine` (`TOUCH`, `TRADE_THROUGH`). Default to `TRADE_THROUGH`.
  2. Update `calculateLimitFillPrice`: Buy limit fills only if `bar.low() < order.price()`.
  3. Implement tick-based stop loss slippage with dynamic volatility scaling.
  4. Create unit test `ConservativeFillSimulationTest.java`.
* **Acceptance**:
  - [ ] Limit orders resting at exact bar High/Low do not fill in `TRADE_THROUGH` mode.
  - [ ] Stop orders incur discrete tick slippage based on bar range / ATR.

### Story 45.3: Futures Rollover Spread Execution & Friction Accounting
* **Goal**: Realistically account for calendar spread commissions and bid-ask slippage on quarterly contract rolls.
* **Tasks**:
  1. Update `BacktestEngine.checkFuturesRollovers()` to invoke full commission and slippage accounting on closing old position and opening new position.
  2. Add rollover trade markers and include rollover costs in net PnL and equity curve calculations.
  3. Create test `FuturesRolloverAccountingTest.java`.
* **Acceptance**:
  - [ ] Rollover execution deducts $1.24+ commission per contract rolled.
  - [ ] Equity curve reflects calendar roll friction without inflating win/loss trade counts.

### Story 45.4: CME Globex Session Calendar & Margin Regulation Alignment (PDT Exemption)
* **Goal**: Implement CME trading hours and disable PDT rule enforcement for futures.
* **Tasks**:
  1. Implement `CmeGlobexMarketCalendar.java` with Sunday open, daily 5pm-6pm halt, and US holiday schedules.
  2. Replace `ForexMarketCalendar` in `BacktestEngine.java` with a polymorphic `MarketCalendar` resolver.
  3. Update `MarginTracker.java` to bypass PDT day-trade count limits when `AssetClass == FUTURES`.
  4. Create tests `CmeGlobexMarketCalendarTest.java` and `FuturesMarginPdtExemptionTest.java`.
* **Acceptance**:
  - [ ] Saturday/holiday CME non-trading bars are skipped.
  - [ ] Futures accounts under $25,000 can execute unlimited day trades without PDT restriction.

### Story 45.5: IBKR Historical Data Timezone Normalization (`America/New_York` to UTC)
* **Goal**: Correct timestamp parsing from Interactive Brokers TWS historical data exports.
* **Tasks**:
  1. Update `IbkrHistoricalDataLoader.java` to parse date-time strings using `ZoneId.of("America/New_York")`.
  2. Handle EDT/EST transitions seamlessly to ensure standard UTC timestamps.
  3. Add unit test `IbkrTimezoneNormalizationTest.java`.
* **Acceptance**:
  - [ ] TWS timestamp `"20240315 09:30:00"` parses to `"2024-03-15T13:30:00Z"` during EDT.

### Story 45.6: Volatility-Adaptive Risk Sizing & Continuous Panama Contract Validation Suite
* **Goal**: Provide ATR-based risk sizing and regression testing against multi-year MES historical data.
* **Tasks**:
  1. Implement `AtrFuturesPositionSizer.java` for dynamic stop placement.
  2. Create golden baseline integration test `MesBacktestGoldenBaselineTest.java` covering 2020–2026 data.
  3. Verify Sharpe ratio period scaling for Globex 24/5 bar frequency.
* **Acceptance**:
  - [ ] Golden baseline runs deterministically with realistic metrics across low-vol and high-vol market cycles.
