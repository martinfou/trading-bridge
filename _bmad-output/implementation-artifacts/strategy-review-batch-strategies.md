# Strategy Review: Batch Trend-Following & Breakout Strategies

**Date:** 2026-08-06  
**Status:** 🔴 **NO-GO**  
**Target Symbol/Market:** EUR/USD (Forex)  

---

## Executive Summary

An automated review of the latest batch backtest optimization run (5 candidate strategies evaluated across `EUR/USD`) reveals that **all 5 strategies fail mandatory baseline benchmarks**. The evaluated strategies (`EMA_23`, `SMA_58`, `SMA_14`, `SMA_32`, and `SMA_25`) exhibit negative Sharpe ratios (ranging from -5,071 to -79,956), sub-0.60 profit factors, and insufficient trade sample sizes (2 to 56 trades total). 

None of the current parameter combinations pass the risk filters required for live deployment or paper trading. Immediate parameter re-optimization or strategy logic redesign is required.

---

## Key Performance Indicators (KPIs)

Below is the aggregate performance summary for the top evaluated strategy (`Trend_following_3_EMA23`) compared against production benchmarks:

| Metric | Empirical Value | Target Benchmark | Pass/Fail |
| :--- | :--- | :--- | :--- |
| **Sharpe Ratio** | -16,469.76 | > 1.50 | ❌ **FAIL** |
| **Profit Factor** | 0.56 | > 1.40 | ❌ **FAIL** |
| **Max Drawdown** | -0.01% | < 15.0% | ✅ Pass |
| **Win Rate** | 41.07% | > 50.0% | ❌ **FAIL** |
| **Total Trades** | 56 | > 100 | ❌ **FAIL** |
| **Robustness Score** | 25.5 / 100 | > 70.0 | ❌ **FAIL** |

### Top Candidate Comparison Table

| Rank | Strategy Name | Type | Sharpe Ratio | Profit Factor | Win Rate % | Total Trades | Status |
| :---: | :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | `EMA_23` | Breakout | -16,469.76 | 0.56 | 41.1% | 56 | 🔴 NO-GO |
| 2 | `SMA_58` | Breakout | -5,626.43 | 0.23 | 50.0% | 2 | 🔴 NO-GO |
| 3 | `SMA_14` | Breakout | -5,618.75 | 0.21 | 50.0% | 2 | 🔴 NO-GO |
| 4 | `SMA_32` | Breakout | -5,071.65 | 0.21 | 50.0% | 2 | 🔴 NO-GO |
| 5 | `SMA_25` | Trend | -79,956.88 | 0.31 | 42.9% | 7 | 🔴 NO-GO |

---

## Overfitting & Walk-Forward Audit

- **WFA Configuration**: 180-day In-Sample (IS) window / 60-day Out-of-Sample (OOS) window on `EUR_USD`.
- **Parameter Sensitivity**: Extremely high. Strategies suffer severe performance collapse on OOS windows due to static parameter thresholds (`emaFastPeriod`: 10–30, `emaSlowPeriod`: 100–200, `atrPeriod`: 10–20).
- **Sample Size Warning**: 4 out of 5 strategies generated 7 or fewer trades across the 180-day backtest period, rendering metrics statistically insignificant.

---

## Key Risks & Weaknesses

1. **Negative Expectancy**: Profit Factor across all candidates remains below 0.60, meaning gross losses significantly exceed gross profits.
2. **Trade Under-Generation**: Low trade count (2–7 trades) indicates overly restrictive entry filters or incorrect timeframe bar aggregation.
3. **Execution Slippage & Spread Vulnerability**: Given the low profit factor, spread costs on `EUR_USD` will exacerbate capital erosion.

---

## Actionable Next Steps & Recommendations

1. **Widen Parameter Grid Search**: Expand `emaFastPeriod` (5 to 50) and `emaSlowPeriod` (50 to 300) in `wfa-config.json`.
2. **Implement Dynamic Volatility Filters**: Replace static price thresholds with ATR-normalized breakout filters to prevent false triggers during low-volatility regimes.
3. **Re-run Walk-Forward Analysis**: Re-execute WFA with a lower entry threshold to generate at least 100+ trades per backtest window before re-evaluating for production deployment.
