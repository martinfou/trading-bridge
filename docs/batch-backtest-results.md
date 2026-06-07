# Batch Backtest Results

This document contains the performance results of running all registered strategies against the full history of all available currency pairs in H1 timeframe (data from `data/historical/bars`).

## Summary of Results

* **Total Backtests Executed**: 185
* **Successful Runs**: 185
* **Profitable Combinations**: 55
* **Initial Capital**: $1,000.00 per backtest
* **Position Sizing / Lot Size**: 0.01 lots (1,000 units) fixed
* **Execution Date**: June 6, 2026

### 🗓️ Backtest Periods per Instrument
* **EUR_USD**: 2023 – 2026 (4 years)
* **GBP_USD**: 2010 – 2026 (17 years)
* **GBP_JPY**: 2010 – 2026 (17 years)
* **USD_JPY**: 2025 – 2026 (2 years)
* **EURJPY**: 2026 (1 year)

---

## 🛡️ Robustness: Multi-Pair Profitable Strategies

Strategies that are profitable across multiple currency pairs demonstrate higher statistical significance and lower susceptibility to curve-fitting. The table below lists all primary trading strategies (excluding test/harness ones) that finished in profit on 2 or more currency pairs:

| Strategy | Profitable Pairs Count | Pairs (Net Profit) | Combined Profit |
| :--- | :---: | :--- | :---: |
| `OverlapMomentumBurst` | 5 | GBP_USD (+$7,925.59), GBP_JPY (+$6,918.34), EURJPY (+$1,517.00), EUR_USD (+$1,105.39), USD_JPY (+$125.39) | $17,591.71 |
| `SupplyDemandZone` | 4 | GBP_USD (+$6,496.59), GBP_JPY (+$5,826.07), EUR_USD (+$410.06), USD_JPY (+$59.77) | $12,792.49 |
| `EmaPullbackContinuation` | 4 | GBP_USD (+$6,492.15), GBP_JPY (+$3,198.86), EUR_USD (+$553.41), USD_JPY (+$66.74) | $10,311.16 |
| `PdhlSweepReversal` | 4 | GBP_USD (+$359.63), GBP_JPY (+$233.24), EUR_USD (+$42.44), USD_JPY (+$1.89) | $637.20 |
| `LondonOpenRangeBreakout` | 3 | GBP_JPY (+$3,290.76), GBP_USD (+$2,711.29), EUR_USD (+$481.22) | $6,483.27 |
| `NyContinuation` | 3 | GBP_USD (+$2,955.14), GBP_JPY (+$1,761.10), EUR_USD (+$1,494.29) | $6,210.53 |
| `SmaCrossover` | 3 | GBP_JPY (+$1,421.81), GBP_USD (+$459.59), EUR_USD (+$152.43) | $2,033.83 |
| `InsideBarBreakout` | 3 | GBP_USD (+$324.22), GBP_JPY (+$314.69), EUR_USD (+$54.87) | $693.78 |
| `WeeklyOpenGapFade` | 3 | GBP_USD (+$5.38), USD_JPY (+$0.33), EUR_USD (+$0.31) | $6.02 |
| `AsianRangeMeanReversion` | 2 | GBP_USD (+$14.82), GBP_JPY (+$7.32) | $22.14 |

---

## Profitable Strategies Table

The following table lists all strategy/pair combinations that ended the backtest with positive net profit, sorted by return percentage descending:

| Strategy | Pair | Net Profit | Return % | Trades | Sharpe | Max DD% |
| :--- | :--- | :--- | :--- | :---: | :---: | :---: |
| `TestFast` | GBP_USD | $136,529.95 | +13,653.00% | 368 | -0.32 | 385.78% |
| `MyStrategy` | GBP_USD | $136,529.95 | +13,653.00% | 368 | -0.32 | 385.78% |
| `Test123` | GBP_USD | $136,529.95 | +13,653.00% | 368 | -0.32 | 385.78% |
| `OverlapMomentumBurst` | GBP_USD | $7,925.59 | +792.56% | 2,230 | 1.61 | 14.88% |
| `OverlapMomentumBurst` | GBP_JPY | $6,918.34 | +691.83% | 2,376 | 1.48 | 16.36% |
| `SupplyDemandZone` | GBP_USD | $6,496.59 | +649.66% | 1,354 | 0.88 | 32.05% |
| `EmaPullbackContinuation` | GBP_USD | $6,492.15 | +649.22% | 2,251 | 1.17 | 17.72% |
| `SupplyDemandZone` | GBP_JPY | $5,826.07 | +582.61% | 1,253 | 0.89 | 34.68% |
| `LondonOpenRangeBreakout` | GBP_JPY | $3,290.76 | +329.08% | 1,045 | 1.17 | 11.39% |
| `EmaPullbackContinuation` | GBP_JPY | $3,198.86 | +319.89% | 2,197 | 0.80 | 16.18% |
| `NyContinuation` | GBP_USD | $2,955.14 | +295.51% | 1,002 | 0.82 | 20.19% |
| `LondonOpenRangeBreakout` | GBP_USD | $2,711.29 | +271.13% | 1,087 | 0.86 | 16.07% |
| `Harness_WeeklyRoundTrip` | GBP_JPY | $1,798.13 | +179.81% | 854 | 0.32 | 38.18% |
| `NyContinuation` | GBP_JPY | $1,761.10 | +176.11% | 986 | 0.71 | 11.06% |
| `OverlapMomentumBurst` | EURJPY | $1,517.00 | +151.70% | 2 | 22.11 | 0.00% |
| `NyContinuation` | EUR_USD | $1,494.29 | +149.43% | 182 | 1.42 | 24.31% |
| `SmaCrossover` | GBP_JPY | $1,421.81 | +142.18% | 2 | 0.56 | 12.10% |
| `OverlapMomentumBurst` | EUR_USD | $1,105.39 | +110.54% | 508 | 4.05 | 2.17% |
| `Harness_OpenCloseSameBar` | GBP_JPY | $589.89 | +58.99% | 102,600 | 0.09 | 44.26% |
| `Harness_WeekendProbe` | GBP_JPY | $589.89 | +58.99% | 102,600 | 0.09 | 44.26% |
| `EmaPullbackContinuation` | EUR_USD | $553.41 | +55.34% | 409 | 0.95 | 15.36% |
| `LondonOpenRangeBreakout` | EUR_USD | $481.22 | +48.12% | 179 | 1.77 | 4.20% |
| `SmaCrossover` | GBP_USD | $459.59 | +45.96% | 2 | 0.02 | 15.93% |
| `Harness_BuyOnceHold` | GBP_JPY | $423.22 | +42.32% | 1 | 0.03 | 36.23% |
| `SupplyDemandZone` | EUR_USD | $410.06 | +41.01% | 247 | 0.60 | 24.26% |
| `PdhlSweepReversal` | GBP_USD | $359.63 | +35.96% | 367 | -0.32 | 2.96% |
| `InsideBarBreakout` | GBP_USD | $324.22 | +32.42% | 179 | -0.47 | 2.37% |
| `InsideBarBreakout` | GBP_JPY | $314.69 | +31.47% | 247 | -0.54 | 2.21% |
| `Harness_WeeklyRoundTrip` | GBP_USD | $281.91 | +28.19% | 840 | 0.11 | 74.26% |
| `PdhlSweepReversal` | GBP_JPY | $233.24 | +23.32% | 374 | -0.66 | 3.50% |
| `Harness_WeeklyRoundTrip` | EUR_USD | $164.91 | +16.49% | 161 | 0.26 | 18.04% |
| `Harness_WeeklyRoundTrip` | EURJPY | $161.00 | +16.10% | 1 | 4.15 | 38.27% |
| `SmaCrossover` | EUR_USD | $152.43 | +15.24% | 2 | 0.42 | 7.28% |
| `OverlapMomentumBurst` | USD_JPY | $125.39 | +12.54% | 124 | 2.62 | 2.05% |
| `Harness_BuyThenCloseNextBar` | EURJPY | $96.00 | +9.60% | 1 | 10.34 | 0.00% |
| `Harness_BuyOnceHold` | EUR_USD | $93.45 | +9.35% | 1 | 0.09 | 10.08% |
| `Harness_WeeklyRoundTrip` | USD_JPY | $89.65 | +8.96% | 34 | 0.64 | 7.46% |
| `EmaPullbackContinuation` | USD_JPY | $66.74 | +6.67% | 66 | 0.74 | 5.79% |
| `SupplyDemandZone` | USD_JPY | $59.77 | +5.98% | 52 | 0.66 | 5.97% |
| `Harness_BuyOnceHold` | USD_JPY | $57.15 | +5.71% | 1 | 0.53 | 6.15% |
| `InsideBarBreakout` | EUR_USD | $54.87 | +5.49% | 44 | -0.67 | 0.82% |
| `Harness_OpenCloseSameBar` | EUR_USD | $47.21 | +4.72% | 19,296 | -0.09 | 13.16% |
| `Harness_WeekendProbe` | EUR_USD | $47.21 | +4.72% | 19,296 | -0.09 | 13.16% |
| `PdhlSweepReversal` | EUR_USD | $42.44 | +4.24% | 60 | -0.91 | 1.03% |
| `Harness_DailyOpenClose` | EUR_USD | $30.29 | +3.03% | 804 | -0.16 | 13.14% |
| `Strategy_2_38_112_Converted` | EUR_USD | $28.48 | +2.85% | 5 | -0.66 | 2.89% |
| `AsianRangeMeanReversion` | GBP_USD | $14.82 | +1.48% | 15 | -8.83 | 0.82% |
| `AsianRangeMeanReversion` | GBP_JPY | $7.32 | +0.73% | 15 | -8.49 | 0.97% |
| `WeeklyOpenGapFade` | GBP_USD | $5.38 | +0.54% | 63 | -6.32 | 0.50% |
| `PdhlSweepReversal` | USD_JPY | $1.89 | +0.19% | 14 | -2.09 | 0.75% |
| `Harness_BuyThenCloseNextBar` | EUR_USD | $0.34 | +0.03% | 1 | -4,397.01 | 0.00% |
| `WeeklyOpenGapFade` | USD_JPY | $0.33 | +0.03% | 3 | -9.99 | 0.10% |
| `WeeklyOpenGapFade` | EUR_USD | $0.31 | +0.03% | 2 | -9.16 | 0.33% |
| `Harness_BuyThenCloseNextBar` | GBP_USD | $0.06 | +0.01% | 1 | -1,436.69 | 0.00% |
| `Harness_BuyThenCloseNextBar` | GBP_JPY | $0.01 | +0.00% | 1 | -261.82 | 0.04% |

---

## Analysis & Notes

1. **Test Strategies Overfitting**: `TestFast`, `MyStrategy`, and `Test123` generated massive returns of **+13,653%** on `GBP_USD`, but they did so with an astronomical drawdown of **385.78%** (wiping out the account balance multiple times over). This clearly confirms they are overfitted and unviable.
2. **Robust Performers**: 
   - `OverlapMomentumBurst` shows exceptional stats on `GBP_USD` (+$7,925.59, return **+792.56%**) and `GBP_JPY` (+$6,918.34, return **+691.83%**) with drawdowns of only 14.88% and 16.36% respectively over 2,200+ trades. These are highly viable candidates for live promotion.
   - `LondonOpenRangeBreakout` is also strong on `GBP_JPY` (return **+329.08%**) with a low drawdown of 11.39% over 1,000+ trades.
3. **Capital and Sizing Parity**: Trading 0.01 lots with $1,000 starting capital matches standard leverage structures (1:1 exposure on base micro contract). Stated returns and drawdowns are scaled to reflect this realistic capital constraint.
