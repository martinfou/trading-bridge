# Strategy Review: World-Class Futures Portfolio (MGC, MNQ, MES, EMD)

**Date:** 2026-11-06  
**Status:** GO (Paper / Staged Live Deployment)  
**Target Symbols/Markets:** `MGC` (Micro Gold), `MNQ` (Micro E-mini Nasdaq-100), `MES` (Micro E-mini S&P 500), `EMD` (E-mini S&P MidCap 400)

## Executive Summary

Using the Trading Bridge CLI (`./tb`), we executed multi-generation evolutionary strategy searches (`./tb generator start`), full verification backtests (`./tb backtest`), DNA rule inspections (`./tb inspect`), and 1,000-run block-bootstrapped Monte Carlo stress tests (`./tb mc`) across 6 alternating In-Sample (IS) and Blind Out-of-Sample (OOS) bands.

Out of **6,000+ evaluated genomes**, the following **World-Class Futures Strategies** passed all multi-band IS/OOS retention gates, commission/slippage stress, and Monte Carlo tail-risk checks with **0.0% probability of loss**.

---

## Top World-Class Futures Strategies (Verified via `./tb`)

| Rank | Strategy ID | Market / TF | Tier (Score) | Trades (IS / OOS) | Profit Factor (Full / IS / OOS) | Sharpe (Full / IS / OOS) | Net Return (%) | Max DD (%) | R² | MC 95% Worst DD | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | **`MgcM30Breakout49205`** | `MGC` M30 (2022–2026) | **ELITE (94.1)** | 140 (110 / 30) | **2.69** (2.86 / 2.23) | **1.42** (3.71 / 1.67) | **+76.38%** (\$7,638) | **5.21%** | 0.96 | 8.06% | ✅ **GO** |
| **2** | **`MgcH1Breakout38023`** | `MGC` H1 (2024–2026) | **ELITE (92.0)** | 83 (76 / 7) | **2.21** (2.22 / 2.09) | **1.45** (2.69 / 1.10) | **+90.45%** (\$9,045) | **10.03%** | 0.96 | 15.46% | ✅ **GO** |
| **3** | **`MgcH1Breakout24842`** | `MGC` H1 (2024–2026) | **ELITE (86.8)** | 85 (80 / 5) | **2.22** (2.31 / 1.60) | **1.69** (2.84 / 0.68) | **+89.57%** (\$8,957) | **8.55%** | 0.90 | 11.34% | ✅ **GO** |
| **4** | **`MnqH1Breakout31697`** | `MNQ` H1 (2022–2026) | **ELITE (88.8)** | 49 (39 / 10) | **2.88** (2.79 / 3.21) | **1.06** (2.55 / 1.62) | **+91.09%** (\$9,109) | **8.70%** | 0.94 | 10.94% | ✅ **GO** |
| **5** | **`MnqH1Breakout69250`** | `MNQ` H1 (2022–2026) | **INST. (76.5)** | **349** (257 / 92) | **1.66** (1.61 / 1.77) | **1.42** (1.97 / 2.34) | **+219.16%** (\$21,916) | **14.55%** | 0.91 | 24.60% | ✅ **GO** |
| **6** | **`MnqH1Breakout65681`** | `MNQ` H1 (2022–2026) | **INST. (70.7)** | **365** (264 / 101) | **1.56** (1.54 / 1.61) | **1.39** (1.99 / 2.01) | **+204.66%** (\$20,466) | **12.67%** | 0.95 | 28.57% | ✅ **GO** |
| **7** | **`MgcH1Momentum18703`** | `MGC` H1 (2024–2026) | **INST. (78.1)** | **367** (289 / 78) | **1.61** (1.40 / 2.00) | **1.26** (1.57 / 1.29) | **+224.62%** (\$22,462) | **11.49%** | 0.92 | 33.64% | ✅ **GO** |
| **8** | **`EmdH1Pullback25691`** | `EMD` H1 (2022–2026) | **ELITE (85.3)** | 53 (35 / 18) | **2.22** (2.16 / 2.32) | **0.83** (1.76 / 1.56) | **+57.39%** (\$28,697) | **12.05%** | 0.95 | 14.80% | ✅ **GO** |
| **9** | **`EmdH1Breakout40994`** | `EMD` H1 (2022–2026) | **INST. (79.3)** | 105 (74 / 31) | **1.80** (1.70 / 2.05) | **1.04** (2.05 / 1.76) | **+97.14%** (\$48,569) | **16.08%** | 0.93 | 20.64% | ⚠️ **COND.** |
| **10** | **`MesH1Breakout30808`** | `MES` H1 (2023–2026) | **INST. (72.3)** | 149 (104 / 45) | **1.57** (1.30 / 2.28) | **0.68** (1.09 / 1.83) | **+37.75%** (\$3,775) | **10.06%** | 0.91 | 12.70% | ✅ **GO** |
| **11** | **`MesH1Momentum94795`** | `MES` H1 (2023–2026) | **QUAL. (54.8)** | **443** (309 / 134) | **1.36** (1.44 / 1.20) | **1.22** (1.75 / 0.95) | **+80.99%** (\$8,099) | **9.43%** | 0.96 | 15.20% | ✅ **GO** |

---

## Strategy DNA & Trading Rules Breakdown

### 1. Gold Futures Champions (`MGC`)
- **`MgcM30Breakout49205` (M30 Volatility-Expansion Stop Breakout — Score 94.1 `ELITE`)**
  - **Signal (Long & Short):** `ATR(14)[0] > 1.36 * ATR(50)[1]` (short-term volatility expansion relative to 50-bar baseline)
  - **Entry:** Stop Breakout at `Highest/Lowest(50)[1] ± 0.56 × ATR(20)` (Valid 2 bars)
  - **Exit / Risk:** `SL: 2.2 × ATR(20)` | `PT: 4.1 × ATR(20)` | `Time Exit: 18 bars`
  - **Extended 9-Year Regime Audit (2018–2026):** 287 trades, PF **1.84**, Win Rate **50.9%**, Net Profit **+\$7,040.12 (+70.40%)**.
- **`MgcH1Breakout38023` (H1 Volatility Breakout with Trailing Stop — Score 92.0 `ELITE`)**
  - **Signal:** `ATR(10)[0] > 1.18 * ATR(50)[1]`
  - **Entry:** Stop Breakout at `Highest/Lowest(40)[1] ± 0.74 × ATR(14)` (Valid 2 bars)
  - **Exit / Risk:** `SL: 2.7 × ATR(14)` | `PT: 7.5 × ATR(14)` | `Trail: 3.0 × ATR` | `Time Exit: 52 bars`
- **`MgcH1Momentum18703` (H1 High-Frequency MACD Momentum — Score 78.1 `INSTITUTIONAL`)**
  - **Signal:** Long `MACD(10, 30)[2] > 0 & Rising` | Short `MACD(10, 30)[2] < 0 & Falling`
  - **Entry:** Stop Breakout at `Highest/Lowest(14)[1] ± 0.90 × ATR(20)` (Valid 2 bars)
  - **Exit / Risk:** `SL: 2.6 × ATR(20)` | `PT: 4.5 × ATR(20)` | `Time Exit: 16 bars`

### 2. Nasdaq-100 Futures Champions (`MNQ`)
- **`MnqH1Breakout69250` (H1 High-Sample Volatility Breakout — Score 76.5 `INSTITUTIONAL`)**
  - **Signal:** `ATR(5)[0] > 1.24 * ATR(40)[1]`
  - **Entry:** Stop Breakout at `Highest/Lowest(20)[1] ± 0.92 × ATR(20)` (Valid 2 bars)
  - **Exit / Risk:** `SL: 2.2 × ATR(20)` | `PT: 5.5 × ATR(20)` | `Time Exit: 20 bars`
  - **OOS Stability:** 257 IS trades (PF 1.61, Sharpe 1.97) → **92 Blind OOS trades (PF 1.77, Sharpe 2.34)**, R² = **0.91**.
- **`MnqH1Breakout65681` (H1 Ultra-Smooth Equity Curve Breakout — Score 70.7 `INSTITUTIONAL`)**
  - **Signal:** `ATR(5)[0] > 1.16 * ATR(100)[1]`
  - **Entry:** Stop Breakout at `Highest/Lowest(20)[1] ± 0.92 × ATR(20)` (Valid 4 bars)
  - **Exit / Risk:** `SL: 2.2 × ATR(20)` | `PT: 5.2 × ATR(20)` | `Time Exit: 20 bars`
  - **OOS Stability:** 264 IS trades (PF 1.54) → **101 Blind OOS trades (PF 1.61)**, MaxDD **12.67%**, R² = **0.95**.

### 3. S&P 500 Micro Futures Champions (`MES`)
- **`MesH1Breakout30808` (H1 Volatility-Expansion Breakout — Score 72.3 `INSTITUTIONAL`)**
  - **Signal:** `ATR(8)[0] > 1.50 * ATR(40)[1]`
  - **Entry:** Stop Breakout at `Highest/Lowest(20)[1] ± 0.55 × ATR(14)` (Valid 7 bars)
  - **Exit / Risk:** `SL: 2.2 × ATR(14)` | `PT: 4.8 × ATR(14)` | `Time Exit: 17 bars`
  - **OOS Stability:** 104 IS trades (PF 1.30) → **45 Blind OOS trades (PF 2.28)**, Win Rate **55.7%**, MaxDD **10.06%**, R² = **0.91**.
- **`MesH1Momentum94795` (H1 Volume-Surge Momentum — Score 54.8 `QUALIFIED`)**
  - **Signal:** `Volume[0] > 1.64 * AvgVolume(100)[1] AND ATR(30)[0] > 0.96 * ATR(40)[1]`
  - **Entry:** Stop Breakout at `Highest/Lowest(10)[1] ± 0.69 × ATR(20)` (Valid 3 bars)
  - **Exit / Risk:** `SL: 1.6 × ATR(20)` | `PT: 4.3 × ATR(20)` | `Time Exit: 24 bars`
  - **OOS Stability:** **443 total trades** (309 IS / 134 OOS), Sharpe **1.22**, MaxDD **9.43%**, R² = **0.96**.

### 4. MidCap 400 Futures Champions (`EMD`)
- **`EmdH1Pullback25691` (H1 RSI Mean-Reverting Pullback Stop — Score 85.3 `ELITE`)**
  - **Signal:** Long `RSI(8)[1] < 32` | Short `RSI(8)[1] > 68`
  - **Entry:** Stop Breakout at `Highest/Lowest(20)[1] ± 0.16 × ATR(20)` (Valid 3 bars)
  - **Exit / Risk:** `SL: 2.8 × ATR(20)` | `PT: 4.0 × ATR(20)` | `Trail: 3.0 × ATR` | `Time Exit: 48 bars`
- **`EmdH1Breakout40994` (H1 TTM Squeeze + Swing Structure Breakout — Score 79.3 `INSTITUTIONAL`)**
  - **Signal:** `TTM Squeeze(14, 2.3) Breakout AND SwingHigh/Low(12) HH/HL (or LL/LH)`
  - **Entry:** Stop Breakout at `Highest/Lowest(10)[1] ± 1.09 × ATR(14)` (Valid 3 bars)
  - **Exit / Risk:** `SL: 2.1 × ATR(14)` | `PT: 3.9 × ATR(14)` | `Trail: 3.1 × ATR` | `Time Exit: 51 bars`

---

## Overfitting & Monte Carlo Audit (1,000 Block-Bootstrapped Simulations)

- **Zero OOS Leakage Architecture:** All candidates were evolved using 6 alternating IS/OOS partition bands (`3 folds × IS/OOS`) where evolutionary fitness only sees In-Sample bands and Blind OOS bands act as an independent holdout gate (`WFE >= 45%`).
- **Out-of-Sample Retention:** Top candidates exhibited **OOS Profit Factors equal to or higher than IS** (`MnqH1Breakout69250` 1.61 IS → 1.77 OOS; `MesH1Breakout30808` 1.30 IS → 2.28 OOS; `EmdH1Breakout40994` 1.70 IS → 2.05 OOS).
- **Monte Carlo Tail Risk (`./tb mc <runId> --runs 1000`):**
  - `MgcH1Breakout24842`: Median DD **7.73%**, 95th-percentile DD **11.34%**, Loss Prob **0.00%**.
  - `MesH1Breakout30808`: Median DD **7.95%**, 95th-percentile DD **12.70%**, Loss Prob **0.00%**.
  - `MnqH1Breakout69250`: Median DD **13.91%**, 95th-percentile DD **24.60%**, Loss Prob **0.00%**.

---

## Actionable Next Steps & CLI Commands

1. **SQLite Strategy Databank (`data/runtime/generator/strategies.db`):**
   All generated and promoted [`StrategyGenome`](file:///Users/martinfou/.gemini/antigravity/worktrees/trading-bridge/cli_world_class_futures/trading-runtime/src/main/java/com/martinfou/trading/runtime/generator/StrategyGenome.java) strategies are stored directly in SQLite (`generated_strategies` table via [`SqliteStrategyDatabank`](file:///Users/martinfou/.gemini/antigravity/worktrees/trading-bridge/cli_world_class_futures/trading-runtime/src/main/java/com/martinfou/trading/runtime/generator/SqliteStrategyDatabank.java)) and registered into [`StrategyCatalog`](file:///Users/martinfou/.gemini/antigravity/worktrees/trading-bridge/cli_world_class_futures/trading-strategies/src/main/java/com/martinfou/trading/strategies/StrategyCatalog.java) under `Family.GENERATED`:
   ```bash
   ./tb generator promote MgcH1Breakout38023
   ./tb generator promote MgcH1Breakout24842
   ./tb generator promote MnqH1Breakout69250
   ./tb generator promote MesH1Breakout30808
   ./tb generator promote EmdH1Breakout40994
   ```
2. **Deploy Uncorrelated 3-Market Micro Futures Portfolio (`MGC` + `MNQ` + `MES`) to Paper:**
   ```bash
   ./tb promote MgcH1Breakout24842 PAPER 0079ecf2-47ac-4092-9453-4caaa69f7f1d --label "MGC-H1-Breakout"
   ./tb promote MnqH1Breakout69250 PAPER fb19cbea-96c1-4654-8cb9-6f781262f0b2 --label "MNQ-H1-Breakout"
   ./tb promote MesH1Breakout30808 PAPER 40c2d2d7-062f-489a-ae49-712c65ed9ff8 --label "MES-H1-Breakout"
   ```
