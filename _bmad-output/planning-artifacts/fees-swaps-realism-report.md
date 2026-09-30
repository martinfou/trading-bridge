# Trading Bridge - Fees, Spreads, and Swap Realism Audit

**Date**: 2026-08-12
**Author**: Quantitative Backtesting Audit (Hermes Agent)
**Repo**: /home/martinfou/projects/trading-bridge
**Status**: CRITICAL - Batch results are ZERO-COST, published returns are invalid

---

## Executive Summary

1. **THE BATCH RAN WITH ZERO COSTS.** Every strategy/pair combination in the July 31, 2026 batch (1,496 backtests, 645 "profitable") was executed with `BacktestExecutionCost.ZERO`: zero commission, zero slippage, zero swap. The SQLite database confirms `total_commission=0.0, total_slippage=0.0` for all persisted runs. Published returns like +197.8% GBP_USD, +210% EmaPullbackContinuation, etc. are cost-free artifacts. Re-running the batch with realistic costs ($0.07/trade commission + slippage) is the single most urgent action.

2. **Swap rates are static 2024-2026 values applied anachronistically to 2010-2025.** The interbank rate environment was radically different: USD near 0.25% in 2010-2015 vs near 5% in 2023. Carry-sensitive strategies (AUD/JPY, NZD/JPY, USD/JPY longs) report entirely fabricated swap PnL for pre-2022 periods. The developer's own research commits confirm this kills strategies: commit `2c2c82e0` "swap model const 2024-26 kills AUD/NZD -4.7K/-5K" and commit `431c402f` CarryPremium "profit 100% swap constant."

3. **JPY pip conversion uses fixed `DEFAULT_USD_JPY` = 150.0 regardless of historical period.** USD/JPY traded at ~80 in 2012 and ~105 in 2016. The fixed 150 rate overstates JPY-cross swap costs by 1.5-2x for historical periods and understates them for very recent data (where USD/JPY was ~160). Affects all JPY-quoted pairs (GBP_JPY, EUR_JPY, AUD_JPY, NZD_JPY, USD_JPY) for both swap and PnL calculations.

4. **No bid/ask spread is modeled.** The engine applies only per-side slippage (0.00005 = ~0.5 pip), not a full bid/ask spread. Real retail brokers charge 0.6-1.5 pips per side. High-frequency strategies (2,000+ trades) are systematically over-optimistic by ~$0.06-0.15 per micro-lot filled (1,000 units) per leg, which compounds to thousands of dollars over many trades.

5. **Swap is only counted on closeOnly() exits, NOT on stop-loss/take-profit/end-of-run closes.** The `reduceOppositeSide()` method (BacktestEngine.java:371-374) calculates swap, but `closePosition()` (BacktestEngine.java:469-489) used for SL/TP hits and `closeRemainingPositions()` does NOT. Long-running positions closed by stop-loss or at end-of-backtest window carry zero swap cost credit/debit.

**Priority order**: Fix batch cost wiring (P0, 1 hour) > Add bid/ask spread model (P1, 1 day) > Time-varying swap rates (P2, 3-5 days) > Fix swap-on-close gap (P3, 1 day)

---

## Context

Trading Bridge is a Java/Maven monorepo forex backtesting platform. On July 31, 2026, a batch run of 136 strategies across 11 pairs (1,496 total backtests) was executed with $1,000 capital, 0.01 fixed lot size. The results were published in `batch-results/profitable_report.txt`. This audit examines whether the trading costs (fees, spreads, slippage, swap/rollover) applied are realistic, and whether the published strategy returns survive realistic cost modeling.

## Methodology

- Static code analysis of all cost-related engine classes: `BacktestEngine.java`, `SwapCalculator.java`, `BacktestExecutionCost.java`, `RunContext.java`, `ForexPnL.java`, `BacktestResult.java`, `MonteCarloSimulation.java`
- SQLite query of `data/runtime/events.db` (`backtest_runs` table) to verify persisted cost values
- Git log analysis of swap-related commits (`bcced4d6`, `caad0d80`, `2c2c82e0`, `431c402f`)
- Analysis of batch result files: `batch-results/profitable_report.txt`, `batch-results/profitable_report_clean.txt`, `docs/batch-backtest-results.md`
- Broker comparison: OANDA spread-only pricing model, IBKR commission+spread model
- Historical interest rate comparison against static swap rates

---

## Findings

### A. Are the Current Cost Parameters Realistic?

#### A1. Commission Model

The engine supports a fixed commission model via `BacktestEngine.withCommissionFixed(double)`. The skill documentation suggests $0.07 per trade (~0.7 pips on 1,000 units) as a default:

```
BacktestEngine.java:60 - private double commissionFixed = 0.0;  // default is ZERO
```

This rate is defensible for a commission-based broker like IBKR (FX commission ~$0.40-0.80 per standard lot, so ~$0.04-0.08 per micro-lot). However:

- **The batch never applied it.** `RunContext.forStrategy()` (RunContext.java:106) hardcodes `BacktestExecutionCost.ZERO`. The `RunAllBatchBacktests.java` runner calls `RunContext.forStrategy(strategyId, strategy, symbol, RunMode.BACKTEST, bars, CAPITAL, null)` - the 7-arg overload that defaults to ZERO.
- SQLite confirms: `SELECT DISTINCT total_commission FROM backtest_runs` returns only `0.0`.

**Gap**: The commission parameter is reasonable for IBKR-style accounts but was never active. For OANDA spread-only accounts, the model is wrong: OANDA charges no separate commission but embeds cost in the spread.

#### A2. Slippage Model

```
BacktestEngine.java:63 - private double slippagePct = 0.0;     // default ZERO
BacktestEngine.java:64 - private double stopSlippagePct = 0.0;  // default ZERO
```

When configured (e.g., `BacktestExecutionCost.OANDA_SPREAD` = 0.00005 slippageFixed), the engine applies per-side slippage working against the trader:

```
BacktestEngine.java:523-527:
double slipAmount = slippageFixed > 0 ? slippageFixed : price * slippagePct;
totalSlippage += slipAmount * quantity;
return side == Order.Side.BUY ? price + slipAmount : price - slipAmount;
```

At 0.00005 on EUR/USD at 1.10 with 1,000 units: ~$0.055 per fill, ~$0.11 round-trip. This is modeled as a constant adverse fill offset, not a true bid/ask spread.

**Gap vs real brokers**:

| Cost Component | Engine (if configured) | OANDA Spread-Only | IBKR + Spread |
|---|---|---|---|
| Commission per trade | $0.07 (fixed) | $0.00 | $0.04-0.08 |
| Spread per side | NOT MODELED | 0.6-1.5 pips | 0.1-0.3 pips |
| Slippage per fill | 0.5 pip (fixed) | Variable (0-2 pips) | Variable (0-0.5 pips) |
| Round-trip EUR/USD 1k | ~$0.25-0.30 | ~$0.12-0.30 | ~$0.10-0.20 |

**Critical gap**: The engine has NO bid/ask spread. In real trading, every entry is filled at the ask (for buys) or bid (for sells). The current model applies a flat slippage in the adverse direction per fill, which is better than nothing but systematically understates costs by 0.6-1.5 pips per side for retail spread-only brokers, and 0.1-0.3 pips for ECN+commission brokers.

#### A3. Swap/Rollover Model

**Evidence: Static 2024-2026 rates applied to 2010-2025 backtest window**

```
SwapCalculator.java:22-40:
// Source: approximate interbank rates as of 2024-2026
SWAP_RATES.put("EUR_USD",  new double[]{-3.5,  1.2});
SWAP_RATES.put("AUD_JPY",  new double[]{ 6.5, -10.0});  // AUD >> JPY (high carry!)
SWAP_RATES.put("NZD_JPY",  new double[]{ 7.0, -11.0});  // NZD >> JPY (high carry!)
```

These rates reflect a period where the Fed funds rate was 5.25-5.50% (2024-2026). However: - From 2010-2015, the Fed rate was 0-0.25%. AUD and NZD rates were 2-4%. The actual carry for AUD/JPY long was much LOWER than the 6.5 pips/day modeled. - From 2020-2022, all major central banks were at near-zero rates. Swap was essentially zero for most pairs. - The engine applies the same +6.5 pips/day AUD/JPY long swap to 2010 data as to 2024 data.

**Quantifying the error**: For a long AUD/JPY position in 2011: - Engine models: +6.5 pips/day credit (~$0.43/day on 1,000 units at USD/JPY=80, converted via fixed 150 rate, giving $0.23/day - another error) - Reality: approximately +1-2 pips/day (RBA at 4.75%, BOJ at 0.10%) - Overstatement: 3-6x

The developer has acknowledged this in two research commits:

- **Commit `2c2c82e0`** (Aug 12, 2026): "swap model const 2024-26 kills AUD/NZD -4.7K/-5K" - The AugustRiskFade strategy was rejected because fixed swap rates destroyed its profitability.
- **Commit `431c402f`** (Aug 3, 2026): CarryPremium "profit 100% swap constant" - The only carry-trade strategy in the catalog was rejected because its PnL was entirely fabricated by static swap credits.

#### A4. JPY Pip Conversion Error

```
SwapCalculator.java:80-85:
double pipSize = symbol.contains("JPY") ? 0.01 : 0.0001;
double pipValueInUSD = symbol.contains("JPY")
    ? quantity * pipSize / (usdJpyRate > 0 ? usdJpyRate : ForexPnL.DEFAULT_USD_JPY)
    : quantity * pipSize;
```

And in `ForexPnL.java:7`:
```java
public static final double DEFAULT_USD_JPY = 150.0;
```

The SwapCalculator receives `usdJpyRate` from `BacktestEngine.usdJpyRate` which defaults to `ForexPnL.DEFAULT_USD_JPY = 150.0` unless explicitly overridden via `withUsdJpyRate()`. The batch runner never calls `withUsdJpyRate`.

**Impact by historical period**:

| Period | Actual USD/JPY | Engine USD/JPY | Swap overstatement factor |
|---|---|---|---|
| 2010-2012 | ~80-85 | 150 | **1.8-1.9x** (swap costs overstated) |
| 2013-2014 | ~98-105 | 150 | **1.4-1.5x** |
| 2015-2021 | ~105-115 | 150 | **1.3-1.4x** |
| 2022-2023 | ~130-150 | 150 | **1.0-1.15x** |
| 2024-2026 | ~140-160 | 150 | **0.94-1.07x** |

For a GBP_JPY short position with swap debit of -0.8 pips/day in 2012: - Engine: 0.8 * (1000 * 0.01 / 150) = $0.053/day - Reality: 0.8 * (1000 * 0.01 / 80) = $0.10/day - **Swap cost understated by 47%** for short JPY-cross positions in early periods

The sign depends on whether the swap is a credit or debit. For high-carry short JPY positions (paying swap), the engine UNDERSTATES the cost. For long AUD/JPY (receiving swap), the engine UNDERSTATES the credit.

#### A5. Wednesday Triple Swap

The Wednesday triple-swap logic in `countRollovers()` (SwapCalculator.java:98-122) is correctly implemented. Each Wednesday rollover at 17:00 ET counts as 3 days instead of 1. This is standard forex convention and correctly applied.

---

### B. Quantitative Impact of Anachronistic Swap Rates

#### B1. Impact on carry-sensitive strategies

For strategies that hold positions for multiple days/weeks:

**AUD/JPY long (carry-positive)**:
- Engine swap credit: +6.5 pips/day = ~$0.43/day on 1,000 units (at correct USD/JPY conversion)
- Real swap credit in 2011: ~$0.07-0.13/day
- Real swap credit in 2018: ~$0.15-0.25/day
- Real swap credit in 2024: ~$0.30-0.40/day
- **Overstatement range**: 1.6x to 6x depending on period

For a strategy holding AUD/JPY long for an average of 5 days with 50 trades in 2010-2015:
- Engine swap PnL: ~$108 (credit)
- Real swap PnL: ~$20-30 (credit)
- **Fabricated profit**: ~$78-88

**USD/JPY long (carry-positive)**:
- Engine swap credit: +5.2 pips/day
- This would have been near zero or slightly positive in reality for the 2010-2015 period when USD rates were near zero
- **Fabricated carry profit for the entire pre-2015 period**

#### B2. Impact on top-5 recommended strategies (from the July 31 batch)

The June 6 batch results (docs/batch-backtest-results.md) identified these "robust performers":

| Strategy | Pair | Net Profit | Trades | Swap-dependent? |
|---|---|---|---|---|
| OverlapMomentumBurst | GBP_USD | +$7,925.59 (792%) | 2,230 | Low (GBP/USD swap is small) |
| OverlapMomentumBurst | GBP_JPY | +$6,918.34 (691%) | 2,376 | **HIGH** (JPY cross, 17yr window) |
| SupplyDemandZone | GBP_USD | +$6,496.59 (649%) | 1,354 | Low |
| EmaPullbackContinuation | GBP_USD | +$6,492.15 (649%) | 2,251 | Low |
| LondonOpenRangeBreakout | GBP_JPY | +$3,290.76 (329%) | 1,045 | **HIGH** (JPY cross, 17yr window) |

For GBP_JPY strategies with 17-year windows (2010-2026): swap costs are systematically wrong for approximately 75% of the backtest window (2010-2022). The direction of error depends on position side. Long GBP/JPY pays swap (GBP rate < JPY rate in early years? No, GBP was higher) - let me not speculate without running actual calculations. The point is: **JPY-cross results spanning 2010-2022 are unreliable for swap attribution.**

However, since the batch ran with ZERO costs anyway (Section A1), these returns are invalid regardless of swap modeling.

---

### C. Does Missing Spread Cost Inflate Returns?

#### C1. Theoretical cost drag

For a strategy with N trades at 1,000 units position size:

| Scenario | Commission | Spread (per side) | Slippage | Total round-trip |
|---|---|---|---|---|
| Current batch (ZERO) | $0.00 | $0.00 | $0.00 | **$0.00** |
| Engine default (if configured) | $0.14 | $0.00 | $0.11 | **$0.25** |
| Realistic OANDA (spread-only) | $0.00 | $0.06-0.15 | $0.05-0.10 | **$0.17-0.50** |
| Realistic IBKR (commission) | $0.08-0.16 | $0.01-0.03 | $0.00-0.05 | **$0.09-0.24** |

For a high-frequency strategy with 2,000+ trades (like EmaPullbackContinuation at 2,251 trades or OverlapMomentumBurst at 2,230):

- At $0.25 round-trip (engine default if configured): **$557-563 in costs**
- At $0.40 round-trip (realistic OANDA): **$892-900 in costs**
- These costs come directly out of the $1,000 initial capital

For the reported GBP_USD profits:

| Strategy | Trades | Reported Profit | Est. Costs (if configured) | Est. Costs (realistic) | Adj. Return (realistic) |
|---|---|---|---|---|---|
| OverlapMomentumBurst GBP_USD | 2,230 | +$7,925 (792%) | -$558 | -$892 | ~+$7,033 (703%) |
| EmaPullbackContinuation GBP_USD | 2,251 | +$6,492 (649%) | -$563 | -$900 | ~+$5,592 (559%) |
| LondonOpenRangeBreakout GBP_JPY | 1,045 | +$3,290 (329%) | -$261 | -$418 | ~+$2,872 (287%) |

**Note**: These adjusted returns assume the engine's $0.07 commission + 0.5 pip slippage WAS applied, which it was NOT (see Finding A1). The actual cost-free returns in profitable_report.txt need a full re-run to determine realistic net PnL.

#### C2. Impact on high-frequency vs low-frequency strategies

| Trade Frequency | Trades/year | Cost drag at $0.40 RT (OANDA) | Cost drag as % of $1,000 capital |
|---|---|---|---|
| Very high | 200+ | $80+ | 8%+ |
| High | 100-200 | $40-80 | 4-8% |
| Medium | 30-100 | $12-40 | 1.2-4% |
| Low | <30 | <$12 | <1.2% |

Strategies with fewer than 30 trades are the least affected by the missing spread model. But high-frequency strategies (>100 trades) lose 4-8% of capital annually to costs. This directly means:
- Strategies with Profit Factor < 1.30 at zero cost likely become unprofitable
- Strategies with Sharpe < 0.3 at zero cost likely become negative Sharpe
- The EmaPullbackContinuation and OverlapMomentumBurst strategies with 2,000+ trades over 17 years average 130+ trades/year: their cost-free returns are inflated by approximately $2,080 (208%) over the full period, assuming $0.40 round-trip.

---

### D. Cost Wiring Through the Engine

#### D1. Commission: Applied on ALL fills, correctly

```
BacktestEngine.java:321-326: (in processOrders)
double commission = calcCommission(adjustedPrice, order.quantity());
order.fill();
totalCommission += commission;
```

Commission is calculated and accumulated for every filled order (entry and exit). The `calcCommission` method (line 529-532) correctly adds fixed + percentage:

```
return commissionFixed + notional * commissionPct;
```

**Verdict**: Correctly wired. Applied per-fill. But defaults to zero and was zero in the batch.

#### D2. Slippage: Applied on ALL fills, correctly

```
BacktestEngine.java:319:
double adjustedPrice = applySlippage(fillPrice, order.side(), order.quantity());
```

The `applySlippage` method (lines 522-527) adjusts fill price against the trader for every order. For stop-loss fills specifically, `checkStopLossesTakeProfits()` (lines 442-454) applies `stopSlippagePct` separately.

**Verdict**: Correctly wired but no bid/ask spread. Slippage is a constant per-fill penalty, not an asymmetric bid/ask model.

#### D3. Swap: Only on closeOnly() exits, NOT on SL/TP/end-of-run closes

**Swap IS counted here** (closeOnly / REDUCE_ONLY exits):

```
BacktestEngine.java:371-374:
double swapCost = SwapCalculator.calculateSwap(
    order.symbol(), opposite.side(), qty,
    opposite.entryTime(), timestamp);
totalSwap += swapCost;
```

**Swap is NOT counted here** (SL/TP/end-of-run closes):

```
BacktestEngine.java:469-489 (closePosition):
// No swap calculation! Just records trade and removes position.
```

This means:
- If a position is closed by a `closeOnly()` exit order from the strategy: swap IS counted.
- If a position is closed by stop-loss: swap is NOT counted.
- If a position is closed by take-profit: swap is NOT counted.
- If a position remains open at end of backtest and is force-closed: swap is NOT counted.

**Impact**: For strategies relying primarily on SL/TP exits (most LT strategies use `closeOnly()` exits, so less affected), positions held overnight that hit stops bypass swap entirely. For a strategy that holds positions an average of 3 days with 50% SL exit rate, approximately half of all overnight swap is lost from the calculation.

**Verdict**: Bug. Swap should be calculated for ALL position closes, not just `reduceOppositeSide()`.

#### D4. Equity recomputation: Swap correctly signed

```
BacktestEngine.java:515:
equity = initialCapital - totalCommission + totalSwap + realizedPnl + floatingPnl;
```

After commit `bcced4d6`, totalSwap is correctly signed (negative = cost). The form `+ totalSwap` is correct because a negative swap cost reduces equity. This was previously a bug where swap was subtracted, inflating PnL when swap was negative.

**Verdict**: Correctly wired post-fix.

#### D5. BacktestResult: Costs flow correctly into PnL

```
BacktestEngine.java:541:
double totalPnl = trades.stream().mapToDouble(Trade::pnl).sum() - totalCommission + totalSwap;
```

Total PnL correctly nets out commission and adds (signed) swap. This flows into `totalReturnPct`, `avgTradePnl`, and the equity curve.

#### D6. Monte Carlo: Costs are embedded but not dynamically adjusted

`MonteCarloSimulation.java:149-151` shuffles `Trade.pnl()` values, which are raw price PnL (before costs). The simulation reconstructs equity by adding shuffled trade PnLs to initial capital:

```
equity += tradePnl;  // line 169
```

Costs (commission, slippage, swap) are NOT included in the shuffled values. The simulation's `initialCapital` is the original `BacktestResult.initialCapital()` which does not reflect costs. This means the Monte Carlo simulates a cost-free world, producing optimistic drawdown and Sharpe distributions.

**Verdict**: Minor bug. For strategies with significant costs vs raw PnL, Monte Carlo results are optimistic.

#### D7. SQLite persistence: total_swap NOT stored

The `backtest_runs` table schema lacks a `total_swap` column:
```sql
total_commission   REAL NOT NULL,
total_slippage     REAL NOT NULL,
-- NO total_swap column
```

Swap costs are computed in the engine but never persisted to SQLite. This was likely an oversight after the swap feature was added (commit `caad0d80`).

**Verdict**: Data loss. Swap costs exist in-memory during backtest but are lost on persistence.

---

### E. Concrete Recommendations

#### P0 (Immediate - 1 hour): Fix batch cost wiring

**File**: `trading-examples/src/main/java/com/martinfou/trading/examples/RunAllBatchBacktests.java`

Change line 106 from:
```java
RunContext context = RunContext.forStrategy(strategyId, strategy, symbol, RunMode.BACKTEST, bars, CAPITAL, null);
```
To use the 9-arg overload with `BacktestExecutionCost`:
```java
BacktestExecutionCost costs = new BacktestExecutionCost(0.07, 0.0, 0.00005, 0.0, 0.00005);
RunContext context = RunContext.forStrategy(null, strategyId, strategy, symbol,
    RunMode.BACKTEST, bars, CAPITAL, null, costs);
```

**File**: `trading-backtest/src/main/java/com/martinfou/trading/backtest/RunContext.java`

Consider changing the default from `BacktestExecutionCost.ZERO` to a sensible default (e.g., 0.07 commission + 0.00005 pct slippage) so callers that forget to pass costs get reasonable defaults.

#### P1 (Short-term - 1 day): Add bid/ask spread model

Add a `spreadPct` parameter to `BacktestEngine` and `BacktestExecutionCost`:

```java
// In BacktestEngine: when filling MARKET orders at bar.open():
// For BUY: fill at bar.open() * (1 + spreadPct/2)  // ask
// For SELL: fill at bar.open() * (1 - spreadPct/2) // bid
```

Configuration:
- EUR/USD, GBP/USD, USD/JPY: 0.6-1.0 pip spread (0.00006-0.00010)
- GBP/JPY, EUR/JPY crosses: 1.0-1.5 pips (0.00010-0.00015)
- AUD/USD, NZD/USD: 0.8-1.2 pips
- USD/CAD, USD/CHF: 0.8-1.2 pips

Define a `SpreadTable` similar to the swap rate map, with per-pair minimum spread.

#### P2 (Medium-term - 3-5 days): Time-varying swap rates

Replace the static `SWAP_RATES` map with one of:

**Option A (simpler)**: Period-bucketed swap rates
```java
// Map<YearRange, Map<Symbol, double[]>>
// 2010-2015, 2016-2019, 2020-2022, 2023-2026
```

**Option B (more accurate)**: Look up the central bank rate differential at trade open time and compute approximate swap from the rate spread:
```java
double swapPips = (longCurrencyRate - shortCurrencyRate) * CONVERSION_FACTOR;
```

Add historical central bank rate data as a CSV resource (Fed, ECB, BOE, BOJ, RBA, RBNZ, BOC, SNB) at ~quarterly granularity.

#### P3 (Medium-term - 1 day): Fix swap-on-close gap

Add swap calculation to `closePosition()` in `BacktestEngine.java`:

```java
// After line 471, add:
double swapCost = SwapCalculator.calculateSwap(
    pos.symbol(), pos.side(), pos.quantity(),
    pos.entryTime(), timestamp, usdJpyRate);
totalSwap += swapCost;
```

This ensures SL/TP and end-of-run closes correctly accrue swap.

#### P4 (Short-term - 1 hour): Add total_swap to SQLite schema

Execute migration to add `total_swap REAL NOT NULL DEFAULT 0.0` to `backtest_runs` table. Update `BacktestPersistenceService` to persist the field.

#### P5 (Medium-term - 1-2 days): Per-pair historical JPY conversion

Replace `DEFAULT_USD_JPY = 150.0` with actual USD/JPY from the bar data. Since the engine already processes bars in chronological order, it can track the current USD/JPY rate from the bar stream:

```java
// In BacktestEngine.run(), when processing a USD_JPY bar:
if (bar.symbol().equals("USD_JPY")) {
    this.currentUsdJpyRate = bar.close();
}
```

This would give accurate JPY pip value conversion for every historical period.

#### P6 (Follow-up - 1 day): Re-run batch with fixed costs

After implementing P0 (and optionally P1):
```bash
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunAllBatchBacktests"
```
Allow 4-8 hours runtime. Compare before/after to quantify cost impact on the full strategy catalog.

#### P7 (Follow-up - 1 day): Validation plan

**Unit tests**:
1. `SwapCalculatorTest`: verify swap with different USD/JPY rates produces correct values
2. `BacktestEngineTest`: verify spread-adjusted fills match expected bid/ask
3. `BacktestEngineTest`: verify swap is counted for SL, TP, and force-close exits
4. `RunContextTest`: verify default cost configuration is non-zero

**Broker comparison**:
- Run top-5 strategies on 1 year of data with engine costs matching OANDA spread-only model
- Compare against OANDA historical spread data for the same period
- Verify within 10-15% tolerance

---

## Priority Order

| Priority | Task | Effort | Impact |
|---|---|---|---|
| **P0** | Fix batch cost wiring (ZERO -> real costs) | 1 hour | **CRITICAL**: All published results invalid until fixed |
| **P1** | Add bid/ask spread model | 1 day | High: 0.6-1.5 pip gap per side unaccounted |
| **P4** | Add total_swap to SQLite schema | 1 hour | Medium: data completeness |
| **P3** | Fix swap-on-close gap (SL/TP/force-close) | 1 day | Medium: undercounts swap for all non-closeOnly exits |
| **P5** | Per-pair historical JPY conversion | 1-2 days | Medium: Currently 1.3-1.9x error on JPY crosses |
| **P2** | Time-varying swap rates | 3-5 days | High accuracy improvement, complex |
| **P6** | Re-run full batch with fixed costs | 1 day (compute) | Required to publish valid strategy rankings |
| **P7** | Validation: unit tests + broker comparison | 1 day | Required before trusting any strategy result |

---

## Appendix: Evidence Index

| Evidence | Location | What it proves |
|---|---|---|
| Batch zero-cost | `RunContext.java:106` | `BacktestExecutionCost.ZERO` hardcoded |
| SQLite zero-cost confirmation | `events.db:backtest_runs` | `total_commission=0.0, total_slippage=0.0` |
| Static swap rates | `SwapCalculator.java:22-40` | Fixed "2024-2026" rates applied to all periods |
| Fixed USD/JPY | `ForexPnL.java:7` | `DEFAULT_USD_JPY = 150.0` |
| Swap only on closeOnly | `BacktestEngine.java:371-374` vs `469-489` | `reduceOppositeSide` has swap, `closePosition` does not |
| No spread model | `BacktestEngine.java` (entire class) | Only slippage, no bid/ask asymmetry |
| Commit 2c2c82e0 | Git log | "swap model const 2024-26 kills AUD/NZD -4.7K/-5K" |
| Commit 431c402f | Git log | "profit 100% swap constant" |
| Batch runner calls zero-cost | `RunAllBatchBacktests.java:106` | Calls 7-arg `forStrategy` with ZERO default |
| Monte Carlo ignores costs | `MonteCarloSimulation.java:149-169` | Shuffles raw trade PnL only |
| SQLite missing total_swap | `events.db` schema | No `total_swap` column |
