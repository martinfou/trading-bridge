# Comprehensive Strategy Backtest & Portfolio Audit Report

**Audit Date:** 2026-11-07  
**Scope:** All **601** strategies across 3 inventories (`110` Built-in Java strategies, `491` Generated Evolutionary strategies in SQLite, and `26` Live-Configured entries in `config/live-config.json` / Docker)  
**Benchmark & Cost Engine:** `RealCostModel` (realistic per-pair half-spreads + slippage) + `SwapCalculator` (daily overnight rollovers with Wednesday 3x triple-swap) + IBKR Micro Futures fees (`$0.62`/side + `0.00005` slippage)  
**Evaluation Windows:** Full (`2010–2026`), In-Sample (`2010–2021`), Out-of-Sample (`2022–2026`)

---

## 1. Executive Verdict

**VERDICT: CONDITIONAL GO (Portfolio Restructuring & Pruning Required)**

Out of **601** total strategy definitions audited under authoritative transaction costs (`RealCostModel` + `SwapCalculator`) and Walk-Forward Out-of-Sample (`2022–2026`) validation:

| Inventory Source | Total Audited | **KEEP** (Core / Promote) | **CONDITIONAL KEEP** (Overlay / Secondary) | **GET RID OF** (Retire / Prune) |
| :--- | :---: | :---: | :---: | :---: |
| **1. Evolutionary Futures Databank** (`data/runtime/generator/strategies.db`) | **491** | **30** (`8` Promoted + `22` Reserve) | **4** (`4` Promoted MES/EMD) | **457** |
| **2. Built-in Java Strategy Catalog** (`trading-strategies`) | **110** | **8** (`1` Futures + `7` FX) | **4** (Annual Seasonality Overlays) | **98** |
| **3. Live Config & Docker Lanes** (`config/live-config.json`) | **26** | **6** | **2** | **18** |

### Key Critical Findings
1. **CME Micro Gold (`MGC`) and Micro Nasdaq (`MNQ`) Evolutionary Strategies Are the Crown Jewels:** The top promoted strategies in `data/runtime/generator/strategies.db` (`MgcM30Breakout49205` with PF **2.69** / OOS PF **2.23** / DD **5.21%**, `MgcH1Breakout38023` with PF **2.21** / OOS PF **2.09**, `MnqH1Breakout31697` with PF **2.88** / OOS PF **3.21**, and high-frequency `MgcH1Momentum18703` / `MnqH1Breakout69250` / `MnqH1Breakout65681` with 349–367 trades) pass every institutional gate including 500-iteration Monte Carlo (`0.0%` loss probability) and parameter perturbation.
2. **Two Unregistered Built-in Strategies Beat 93% of Registered Catalog Strategies:** `MesTurtleStrategy` (`MesTurtle_LongOnly` — 55/20 Donchian breakout on `MGC` PF **1.51**, OOS PF **1.91**; `MNQ` PF **1.16**; `MES` PF **1.09** / 2020–2026 PF **1.47**) and `MondayReversionStrategy` (`MondayReversion` — profitable IS and OOS across `USD_CAD` PF **1.19**/OOS **1.40**, `GBP_USD` PF **1.17**/OOS **1.37**, and `EUR_USD` PF **1.14**/OOS **1.12** with `<10%` drawdown) are not currently registered in `StrategyCatalog.java` and should be registered immediately.
3. **98 of 110 Built-in FX Strategies Fail Realistic Spread + Overnight Swap Modeling:** Prior zero-cost or raw-spread screening overstated strategies like `OverlapMomentumBurst` and `CompositeMomentumRanking`. Once `RealCostModel` + `SwapCalculator` are enforced, `OverlapMomentumBurst` collapses Out-of-Sample (`2022–2026` OOS PF **0.77** on `GBP_JPY`, **0.85** on `GBP_USD`), and **5 currently deployed OANDA dev/paper strategies** (`VWPReversion`, `ConsecutiveBarExhaustion`, `LtRSI3Momentum`, `CompositeMomentumRanking`, `NfpWeekStrategy`) exhibit negative expectancy or 0-volume bar blindness (`VWPReversion` produces `0` trades on H1 historical bars and lost `-38.98 CAD` in live paper trading).
4. **`config/live-config.json` Contains 18 Stale / Broken Entries:** Includes 7 deleted `GBP_JPY` SQX strategies with negative Sharpe (`-0.27` to `-0.62`), 3 duplicate Gold aliases (`sq-gold-m30`, `gold_breakout_m30`, `sq_gold_m30`) that silently fall back to the losing `LtRangeBreakout` strategy in `SqStrategyEvaluator.java`, and `MonthWeekPhase` running on `USD_JPY` (PF **0.72**, OOS PF **0.54**) instead of `GBP_USD` (PF **1.15**, OOS PF **1.13**).

---

## 2. Quantitative Scorecard — Top Strategies to KEEP

### 2A. Generated Futures Strategies (`data/runtime/generator/strategies.db`) — KEEP (34 Total: 12 Promoted + 22 Elite/Institutional Reserve)

All strategies below were validated on CME Micro Futures (`MGC`, `MNQ`, `MES`, `EMD`) with IBKR realistic commissions (`$0.62`/contract + `0.00005` slippage), 70/30 IS/OOS Walk-Forward split, 500-iteration Monte Carlo (`0.0%` probability of loss), and ±10–20% parameter perturbation.

| Strategy ID | Symbol / TF | Archetype | Tier (Score) | Full PF | IS PF | OOS PF | Sharpe | Max DD% | Trades (IS/OOS) | $R^2$ | Net Profit | Action |
| :--- | :--- | :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| `MgcM30Breakout49205` | `MGC` `M30` | `BREAKOUT` | `ELITE` (94.1) | **2.69** | 2.86 | **2.23** | 1.42 | **5.21%** | 140 (110/30) | 0.96 | \$7,638.40 | **KEEP (Core Promoted — `sq-mgc-m30-breakout-0-24542`)** |
| `MgcH1Breakout38023` | `MGC` `H1` | `BREAKOUT` | `ELITE` (92.0) | **2.21** | 2.22 | **2.09** | 1.45 | 10.03% | 83 (76/7) | 0.96 | \$9,045.08 | **KEEP (Core Promoted)** |
| `MgcH1Breakout51270` | `MGC` `H1` | `BREAKOUT` | `ELITE` (89.1) | **2.04** | 2.10 | **1.81** | 1.50 | 12.02% | 99 (88/11) | 0.93 | \$9,786.24 | **KEEP (Core Promoted)** |
| `MnqH1Breakout31697` | `MNQ` `H1` | `BREAKOUT` | `ELITE` (88.8) | **2.88** | 2.79 | **3.21** | 1.06 | **8.70%** | 49 (39/10) | 0.94 | \$9,109.24 | **KEEP (Core Promoted)** |
| `MgcH1Breakout24842` | `MGC` `H1` | `BREAKOUT` | `ELITE` (86.8) | **2.22** | 2.31 | **1.60** | 1.69 | **8.55%** | 85 (80/5) | 0.90 | \$8,956.60 | **KEEP (Core Promoted)** |
| `MgcH1Momentum18703` | `MGC` `H1` | `MOMENTUM` | `INSTITUTIONAL` (78.0) | **1.61** | 1.40 | **2.00** | 1.26 | 11.49% | 367 (289/78) | 0.92 | \$22,461.92 | **KEEP (Core Promoted — High Trade Count)** |
| `MnqH1Breakout69250` | `MNQ` `H1` | `BREAKOUT` | `INSTITUTIONAL` (76.5) | **1.66** | 1.61 | **1.77** | 1.42 | 14.55% | 349 (257/92) | 0.91 | \$21,915.74 | **KEEP (Core Promoted — High Trade Count)** |
| `MnqH1Breakout65681` | `MNQ` `H1` | `BREAKOUT` | `INSTITUTIONAL` (70.7) | **1.56** | 1.54 | **1.61** | 1.39 | 12.67% | 365 (264/101) | 0.95 | \$20,465.90 | **KEEP (Core Promoted — High Trade Count)** |
| `EmdH1Pullback25691` | `EMD` `H1` | `PULLBACK` | `ELITE` (85.3) | **2.22** | 2.16 | **2.32** | 0.83 | 12.05% | 53 (35/18) | 0.95 | \$28,697.40 | **CONDITIONAL KEEP (Promoted — Low Sharpe 0.83)** |
| `EmdH1Breakout40994` | `EMD` `H1` | `BREAKOUT` | `INSTITUTIONAL` (79.2) | **1.80** | 1.70 | **2.05** | 1.04 | 16.08% | 105 (74/31) | 0.93 | \$48,569.00 | **CONDITIONAL KEEP (Promoted — DD 16.08%)** |
| `MesH1Breakout30808` | `MES` `H1` | `BREAKOUT` | `INSTITUTIONAL` (72.2) | **1.57** | 1.30 | **2.28** | 0.68 | 10.06% | 149 (104/45) | 0.91 | \$3,775.24 | **CONDITIONAL KEEP (Promoted — Low Sharpe 0.68)** |
| `MesH1Momentum94795` | `MES` `H1` | `MOMENTUM` | `QUALIFIED` (54.8) | **1.36** | 1.44 | **1.20** | 1.22 | **9.43%** | 443 (309/134) | 0.96 | \$8,099.43 | **CONDITIONAL KEEP (Promoted — OOS PF 1.20)** |
| `MgcM30Breakout89854` | `MGC` `M30` | `BREAKOUT` | `ELITE` (87.9) | **2.10** | 2.18 | **1.90** | 1.34 | 12.86% | 191 (142/49) | 0.85 | \$11,223.16 | **KEEP (Active Alias `sq-mgc-m30-breakout-1-29-102`)** |
| `MgcM30Breakout92728` | `MGC` `M30` | `BREAKOUT` | `ELITE` (84.1) | **2.12** | 2.30 | **1.62** | 1.09 | **5.10%** | 148 (116/32) | 0.92 | \$6,203.48 | **KEEP (Active Alias `sq-mgc-m30-breakout-0-32921`)** |
| `MgcM30Breakout56516` | `MGC` `M30` | `BREAKOUT` | `ELITE` (83.7) | **1.84** | 1.82 | **1.90** | 1.13 | **8.61%** | 179 (137/42) | 0.86 | \$8,111.04 | **KEEP (Unpromoted Reserve)** |
| `MgcM30Breakout33076` | `MGC` `M30` | `BREAKOUT` | `ELITE` (83.7) | **2.14** | 2.35 | **1.61** | 1.09 | **5.08%** | 151 (118/33) | 0.92 | \$6,356.76 | **KEEP (Unpromoted Reserve)** |
| `MnqM15MeanRev81824` | `MNQ` `M15` | `MEAN_REVERSION` | `ELITE` (83.5) | **2.02** | 2.23 | **1.63** | 1.35 | 11.49% | 105 (75/30) | 0.92 | \$5,179.80 | **KEEP (Promote Candidate — Best M15 MeanRev)** |
| `MgcH1Breakout64082` | `MGC` `H1` | `BREAKOUT` | `INSTITUTIONAL` (81.5) | **1.78** | 1.72 | **2.14** | 1.17 | 11.59% | 111 (101/10) | 0.89 | \$7,140.36 | **KEEP (Unpromoted Reserve)** |
| `MgcH1Breakout83740` | `MGC` `H1` | `BREAKOUT` | `INSTITUTIONAL` (81.3) | **1.93** | 2.00 | **1.50** | 1.55 | **9.11%** | 97 (91/6) | 0.94 | \$8,347.72 | **KEEP (Unpromoted Reserve)** |
| `MgcH1Momentum47739` | `MGC` `H1` | `MOMENTUM` | `INSTITUTIONAL` (79.9) | **1.67** | 1.43 | **2.30** | 1.43 | 12.74% | 179 (144/35) | 0.87 | \$9,065.04 | **KEEP (Unpromoted Reserve)** |
| `MnqH1Breakout83955` | `MNQ` `H1` | `BREAKOUT` | `INSTITUTIONAL` (79.4) | **1.77** | 1.71 | **2.01** | 1.07 | 14.95% | 120 (95/25) | 0.95 | \$13,825.20 | **KEEP (Unpromoted Reserve)** |
| `MnqH1Breakout69324` | `MNQ` `H1` | `BREAKOUT` | `INSTITUTIONAL` (78.8) | **1.85** | 1.92 | **1.65** | 1.16 | 11.72% | 164 (131/33) | 0.97 | \$11,055.14 | **KEEP (Unpromoted Reserve)** |
| `MgcH1Breakout22681` | `MGC` `H1` | `BREAKOUT` | `INSTITUTIONAL` (76.8) | **1.66** | 1.63 | **1.78** | 1.30 | 12.60% | 161 (140/21) | 0.92 | \$8,347.36 | **KEEP (Unpromoted Reserve)** |
| `MnqH1Breakout10204` | `MNQ` `H1` | `BREAKOUT` | `INSTITUTIONAL` (76.0) | **1.64** | 1.41 | **2.40** | 1.07 | 13.35% | 233 (171/62) | 0.90 | \$11,852.08 | **KEEP (Unpromoted Reserve)** |
| `MgcH1Momentum34403` | `MGC` `H1` | `MOMENTUM` | `INSTITUTIONAL` (75.8) | **1.69** | 1.72 | **1.62** | 1.55 | 13.16% | 174 (145/29) | 0.94 | \$14,121.24 | **KEEP (Unpromoted Reserve)** |
| `MgcM30Breakout32046` | `MGC` `M30` | `BREAKOUT` | `INSTITUTIONAL` (74.3) | **1.74** | 1.77 | **1.67** | 1.00 | 10.00% | 171 (129/42) | 0.89 | \$7,633.96 | **KEEP (Unpromoted Reserve)** |
| `MgcM30Breakout74711` | `MGC` `M30` | `BREAKOUT` | `INSTITUTIONAL` (72.8) | **1.88** | 2.05 | **1.39** | 1.10 | **8.69%** | 165 (128/37) | 0.91 | \$7,073.40 | **KEEP (Unpromoted Reserve)** |
| `MgcM30Breakout18996` | `MGC` `M30` | `BREAKOUT` | `INSTITUTIONAL` (72.7) | **1.80** | 1.92 | **1.46** | 1.27 | 10.80% | 257 (196/61) | 0.89 | \$8,508.32 | **KEEP (Unpromoted Reserve)** |
| `MgcH1Momentum75557` | `MGC` `H1` | `MOMENTUM` | `INSTITUTIONAL` (69.8) | **1.54** | 1.50 | **1.69** | 1.30 | 14.67% | 200 (176/24) | 0.90 | \$10,154.00 | **KEEP (Unpromoted Reserve)** |
| `MgcH1Momentum27488` | `MGC` `H1` | `MOMENTUM` | `QUALIFIED` (67.2) | **1.56** | 1.56 | **1.56** | 1.32 | 13.80% | 182 (164/18) | 0.88 | \$8,322.32 | **KEEP (Unpromoted Reserve)** |
| `MnqH1Breakout48882` | `MNQ` `H1` | `BREAKOUT` | `QUALIFIED` (66.8) | **1.60** | 1.63 | **1.51** | 1.01 | 11.49% | 174 (128/46) | 0.97 | \$9,664.74 | **KEEP (Unpromoted Reserve)** |
| `MgcM30Breakout63363` | `MGC` `M30` | `BREAKOUT` | `QUALIFIED` (64.2) | **1.65** | 1.77 | **1.34** | 1.21 | 12.69% | 282 (213/69) | 0.93 | \$9,710.32 | **KEEP (Unpromoted Reserve)** |
| `MgcH1Momentum92335` | `MGC` `H1` | `MOMENTUM` | `QUALIFIED` (61.8) | **1.51** | 1.55 | **1.37** | 1.27 | 12.50% | 179 (157/22) | 0.93 | \$9,963.04 | **KEEP (Unpromoted Reserve)** |
| `MgcH1Breakout95483` | `MGC` `H1` | `BREAKOUT` | `QUALIFIED` (59.1) | **1.58** | 1.67 | **1.31** | 1.16 | 14.98% | 124 (108/16) | 0.88 | \$6,662.24 | **KEEP (Unpromoted Reserve)** |

---

### 2B. Built-in Java Strategies (`trading-strategies`) — KEEP (8 Core + 4 Conditional Seasonality Overlays)

All 110 built-in Java strategies were tested across `EUR_USD`, `GBP_USD`, `GBP_JPY`, `USD_JPY`, `USD_CHF`, `USD_CAD` (`2010–2026` H1, ~145,488 bars/pair) and `MES`, `MNQ`, `MGC` (`2014–2026` H1) with `RealCostModel` + `SwapCalculator`. Only **8** strategies pass strict IS (`>= 1.00`), OOS (`>= 1.05`), Full PF (`>= 1.05`), MaxDD (`<= 35%`), and Trade Count (`>= 30`) gates:

| Strategy ID | Catalog Family | Best Verified Symbol(s) | Full PF | IS PF (`10–21`) | OOS PF (`22–26`) | Win Rate | Max DD% | Trades (IS/OOS) | Net Profit | Action |
| :--- | :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| `MesTurtle_LongOnly` (`MesTurtleStrategy`) | `UNREGISTERED` *(Register!)* | `MGC` (`H1`)<br>`MNQ` (`H1`)<br>`MES` (`H1`) | **1.51**<br>**1.16**<br>**1.09** | 1.00<br>1.26<br>1.23 | **1.91**<br>**1.12**<br>**1.01** | 40.5%<br>42.5%<br>42.0% | 29.8%<br>38.0%<br>32.2% | 967 (558/409)<br>510 (302/208)<br>528 (315/213) | +\$29,530.54<br>+\$10,940.10<br>+\$3,084.03 | **KEEP (Register in `StrategyCatalog`)** |
| `MondayReversion` (`MondayReversionStrategy`) | `UNREGISTERED` *(Register!)* | `USD_CAD` (`H1`)<br>`GBP_USD` (`H1`)<br>`EUR_USD` (`H1`) | **1.19**<br>**1.17**<br>**1.14** | 1.11<br>1.11<br>1.14 | **1.40**<br>**1.37**<br>**1.12** | 54.0%<br>52.9%<br>54.6% | **9.5%**<br>**9.9%**<br>**7.9%** | 865 (625/240)<br>865 (625/240)<br>861 (625/236) | +\$238.42<br>+\$289.64<br>+\$189.06 | **KEEP (Register in `StrategyCatalog` — Best Multi-Pair FX Strategy)** |
| `LondonOpenRangeBreakout` | `PROP` | `EUR_USD` (`H1`)<br>`GBP_JPY` (`H1`) | **1.17**<br>**1.10** | 1.12<br>1.06 | **1.35**<br>**1.25** | 40.3%<br>39.3% | 17.9%<br>31.9% | 1051 (777/274)<br>1072 (787/285) | +\$457.88<br>+\$283.83 | **KEEP (Best Registered Prop FX Strategy)** |
| `MonthWeekPhase` | `CREATIVE` | `GBP_USD` (`H1`) | **1.15** | 1.15 | **1.13** | 53.8% | **13.0%** | 446 (320/126) | +\$158.74 | **KEEP on `GBP_USD` ONLY** *(Remove from `USD_JPY` where PF=0.72)* |
| `LtDoubleMA` | `LONG_TERM` | `USD_CHF` (`H1`)<br>`GBP_JPY` (`H1`) | **1.14**<br>**1.08** | 1.09<br>1.07 | **1.26**<br>**1.09** | 38.0%<br>37.6% | **9.4%**<br>**8.5%** | 584 (404/180)<br>572 (396/176) | +\$129.01<br>+\$92.07 | **KEEP (`USD_CHF` / `GBP_JPY`)** |
| `LtCrossMomentum` | `LONG_TERM` | `USD_CHF` (`H1`) | **1.13** | 1.13 | **1.13** | 40.3% | **7.6%** | 944 (678/266) | +\$193.00 | **KEEP (`USD_CHF`)** |
| `LtRSIMeanRev` | `LONG_TERM` | `USD_CHF` (`H1`) | **1.12** | 1.04 | **1.36** | 64.9% | **7.8%** | 967 (698/269) | +\$89.28 | **KEEP (`USD_CHF`)** |
| `DlrMeanReversion` | `PROP` | `USD_CAD` (`H1`) | **1.09** | 1.08 | **1.09** | 51.0% | **6.4%** | 671 (531/140) | +\$70.75 | **KEEP (`USD_CAD`)** |
| `DateWindowSeasonal` | `UNREGISTERED` | `GBP_JPY` (`H1`) | **7.36** | 7.80 | **6.33** | 81.2% | **4.9%** | 16 (11/5) | +\$538.94 | **CONDITIONAL KEEP (Annual Overlay — 1 trade/yr)** |
| `SeasonalCalendar` | `UNREGISTERED` | `GBP_USD` (`H1`)<br>`GBP_JPY` (`H1`) | **3.90**<br>**2.98** | 10.39<br>2.69 | **1.38**<br>**5.31** | 64.7%<br>70.6% | **9.0%**<br>10.1% | 17 (12/5)<br>17 (12/5) | +\$352.94<br>+\$306.82 | **CONDITIONAL KEEP (Annual Overlay — 1 trade/yr)** |
| `GbpUsdSpringBullish` | `CREATIVE` | `GBP_USD` (`H1`) | **2.66** | 3.67 | **1.32** | 70.6% | **5.8%** | 17 (12/5) | +\$155.12 | **CONDITIONAL KEEP (Annual Overlay — 1 trade/yr)** |
| `UsdCadAutumnBullish` | `CREATIVE` | `USD_CAD` (`H1`)<br>`GBP_JPY` (`H1`) | **1.72**<br>**1.95** | 1.27<br>2.12 | **4.44**<br>**1.55** | 62.5%<br>62.5% | **6.5%**<br>12.2% | 16 (12/4)<br>16 (12/4) | +\$92.25<br>+\$175.04 | **CONDITIONAL KEEP (Annual Overlay — 1 trade/yr)** |

---

## 3. Complete List of Strategies to GET RID OF (Retire / Prune)

### 3A. `config/live-config.json` & `docker-compose.yml` Entries to Remove Immediately (18 of 26 Entries)

| Live Config Key / Lane | Currently Configured Symbol | Failure Mode & Empirical Evidence | Action |
| :--- | :--- | :--- | :--- |
| `vwpreversion` (`VWPReversion`) | `USD_CAD` (`docker-compose.yml` + `live-config.json`) | **0 trades** across all 6 FX pairs on H1 historical bars (H1 `.bars` have 0 volume, breaking VWAP weighting); lost `-38.98 CAD` (`33.3%` WR) in live paper trading. | **GET RID OF IMMEDIATELY** |
| `consecbar` (`ConsecutiveBarExhaustion`) | `GBP_JPY` (`docker-compose.yml` + `live-config.json`) | Negative expectancy on every pair after spread+swap (`GBP_JPY` Full PF **0.79**, IS **0.79**, OOS **0.78**, Net **-\$852.92**, DD **85.6%**; lost `-23.02 CAD` in live paper). | **GET RID OF IMMEDIATELY** |
| `ltrsi3` (`LtRSI3Momentum`) | `EUR_USD` (`docker-compose.yml` + `live-config.json`) | Negative expectancy on all 6 FX pairs (`EUR_USD` Full PF **0.75**, IS **0.74**, OOS **0.77**, Net **-\$1,078.10**, DD **108.2%**). | **GET RID OF IMMEDIATELY** |
| `compmomentum` (`CompositeMomentumRanking`) | `USD_JPY` (`docker-compose.yml` + `live-config.json`) | Loses money on configured `USD_JPY` (`Full PF 0.90`, `IS PF 0.91`, `OOS PF 0.89`, `DD 78.0%`). Even on `GBP_JPY`, suffers **68.0% MaxDD** and **-\$375.41 OOS net loss** once swaps are charged. | **GET RID OF IMMEDIATELY** |
| `nfpweek` (`NfpWeekStrategy`) | `EUR_USD` (`live-config.json`) | Negative expectancy across all 6 FX pairs (`EUR_USD` PF **0.85**, Net **-\$695.01**; best pair `USD_CHF` PF **0.98**, Net **-\$621.87**, DD **69.1%**). | **GET RID OF IMMEDIATELY** |
| `monthweekphase` on `USD_JPY` | `USD_JPY` (`docker-compose.yml` + `live-config.json`) | Misconfigured symbol: on `USD_JPY` it has Full PF **0.72** (IS **0.80** / OOS **0.54**, Net **-\$173.40**). Only profitable on `GBP_USD` (Full PF **1.15**, OOS **1.13**). | **SWITCH SYMBOL TO `GBP_USD` OR REMOVE** |
| `2_31_175`, `2_36_190`, `2_38_112`, `2_14_147`, `2_15_195`, `2_32_120`, `2_31_177` (7 legacy SQX `GBP_JPY` strategies) | `GBP_JPY` (`live-config.json`) | Negative Sharpe (`-0.27` to `-0.62`), `50%–115%` MaxDD after realistic costs; backing Java classes were already deleted in commit `95ba36f1`. | **PURGE DEAD KEYS FROM `live-config.json`** |
| `sq-gold-m30`, `gold_breakout_m30`, `sq_gold_m30` (3 duplicate aliases) | `XAU_USD` (`live-config.json`) | In `SqStrategyEvaluator.java`, these aliases silently fall back to `LtRangeBreakout` (which loses money: PF **0.75–0.87**, DD **76.9%**). | **PURGE ALIASES FROM `live-config.json`** |
| `sq-mnq-m15-momentum-0-4248`, `sq-mnq-m15-breakout-0-1499`, `sq-mnq-m15-momentum-0-6737`, `sq-mes-h1-meanrev-0-3553`, `sq-mes-h1-pullback-0-2700`, `sq-mgc-m30-breakout-0-34297`, `sq-mgc-m30-breakout-0-11248`, `sq-gbpjpy-h1-breakout-*` | `MNQ` / `MES` / `MGC` / `GBP_JPY` (`live-config.json`) | Legacy pre-SQLite generator IDs that no longer exist in `data/runtime/generator/strategies.db` or `generated/` Java classes. | **REPLACE WITH PROMOTED SQLITE IDS** |

---

### 3B. All 98 Built-in Java Strategies to GET RID OF (Full Empirical Breakdown)

Every strategy below failed one or more hard gates under `RealCostModel` + `SwapCalculator` across `2010–2026` (`IS: 2010–2021`, `OOS: 2022–2026`). Even taking each strategy's **single best symbol**, none pass institutional deployment standards:

| Strategy ID | Catalog Family | Best Symbol | Trades | Full PF | IS PF | OOS PF | Best Symbol Net PnL | Max DD% | Verdict & Primary Rejection Reason |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| `BearishMonthsFade` | `UNREGISTERED` | `EUR_USD` | 50 | 2.00 | 3.37 | 0.53 | \$443.41 | 10.4% | **GET RID OF** (Collapsed Out-of-Sample: OOS PF 0.53) |
| `GoldTurtlePartialExit` | `UNREGISTERED` | `MES` | 1523 | 1.60 | 1.67 | 1.29 | \$5,033,295.90 | 191.5% | **GET RID OF** (Position sizing / blowup bug: DD 191.5%) |
| `GoldTurtlePyramid` | `UNREGISTERED` | `MES` | 948 | 1.49 | 1.53 | 1.25 | \$6,868,789.35 | 233.3% | **GET RID OF** (Position sizing / blowup bug: DD 233.3%) |
| `GoldTurtleTrend` | `UNREGISTERED` | `MGC` | 1844 | 1.20 | 0.96 | 1.34 | \$25,345.06 | 62.1% | **GET RID OF** (Failed IS PF 0.96 + excessive DD 62.1% from short side) |
| `GoldWeekdayEffect` | `UNREGISTERED` | `MNQ` | 1319 | 1.07 | 1.12 | 1.04 | \$8,482.56 | 50.6% | **GET RID OF** (Excessive DD 50.6% + weak OOS PF 1.04) |
| `MesTurtle_Symmetric` | `UNREGISTERED` | `MGC` | 1844 | 1.20 | 0.96 | 1.34 | \$25,298.06 | 62.4% | **GET RID OF** (Symmetric short-side drag: DD 62.4% vs Long-Only DD 29.8%) |
| `NfpWeek` | `UNREGISTERED` | `USD_CHF` | 4468 | 0.98 | 0.96 | 1.02 | -\$621.87 | 69.1% | **GET RID OF** (Negative expectancy across all pairs; DD 69.1%) |
| `StreakReversal` | `UNREGISTERED` | ALL | 0 | 0.00 | 0.00 | 0.00 | \$0.00 | 0.0% | **GET RID OF** (Zero trades across all pairs) |
| `TurtleCrossAsset` | `UNREGISTERED` | `USD_JPY` | 1375 | 24.67 | 0.95 | 62.92 | \$97,215.79 | 33.7% | **GET RID OF** (Failed In-Sample: IS PF 0.95; JPY unit-scaling artifact) |
| `ADXTrendFilter` | `CREATIVE` | `EUR_USD` | 4768 | 0.81 | 0.82 | 0.79 | -\$901.84 | 90.8% | **GET RID OF** (Negative expectancy after spread+swap) |
| `ATRChannelTrail` | `CREATIVE` | `GBP_JPY` | 8623 | 1.02 | 0.93 | 1.24 | \$80.35 | 123.3% | **GET RID OF** (Failed IS PF 0.93 + blowup DD 123.3%) |
| `ATRExpansionMomentum` | `CREATIVE` | `GBP_JPY` | 1706 | 1.05 | 1.06 | 1.00 | -\$992.17 | 18.6% | **GET RID OF** (Negative net PnL after swap; OOS PF 1.00) |
| `BollingerMeanReversion` | `CREATIVE` | `USD_CAD` | 3827 | 0.90 | 0.90 | 0.89 | -\$464.79 | 51.0% | **GET RID OF** (Negative expectancy after spread+swap) |
| `BollingerSqueezeBreakout` | `CREATIVE` | ALL | 0 | 0.00 | 0.00 | 0.00 | \$0.00 | 0.0% | **GET RID OF** (Zero trades across all pairs) |
| `COTSentimentContrarian` | `CREATIVE` | `GBP_JPY` | 250 | 0.95 | 0.96 | 0.91 | -\$19.17 | 5.6% | **GET RID OF** (Negative expectancy after spread+swap) |
| `ChaikinMoneyFlow` | `CREATIVE` | `GBP_JPY` | 8715 | 0.98 | 0.87 | 1.26 | -\$278.07 | 159.2% | **GET RID OF** (Failed In-Sample: IS PF 0.87; DD 159.2%) |
| `ChandelierExitTrend` | `CREATIVE` | `GBP_JPY` | 6282 | 1.02 | 0.88 | 1.41 | \$121.74 | 120.1% | **GET RID OF** (Failed IS PF 0.88 + blowup DD 120.1%) |
| `CompositeMomentumRanking` | `CREATIVE` | `GBP_JPY` | 1121 | 1.44 | 1.57 | 1.07 | \$1,080.76 | 68.0% | **GET RID OF** (68.0% MaxDD + OOS net PnL -\$375.41 after swaps) |
| `ConsecutiveBarExhaustion` | `CREATIVE` | `EUR_USD` | 3678 | 0.84 | 0.85 | 0.80 | -\$528.26 | 54.8% | **GET RID OF** (Negative expectancy after spread+swap on all pairs) |
| `CreativeInsideBarBreakout` | `CREATIVE` | `GBP_JPY` | 4790 | 1.06 | 0.81 | 1.78 | \$315.01 | 104.1% | **GET RID OF** (Failed IS PF 0.81 + blowup DD 104.1%) |
| `DonchianChannelBreakout` | `CREATIVE` | ALL | 0 | 0.00 | 0.00 | 0.00 | \$0.00 | 0.0% | **GET RID OF** (Zero trades — self-inclusive channel bug) |
| `DualEmaMomentum` | `CREATIVE` | `GBP_USD` | 1190 | 0.98 | 0.98 | 0.99 | -\$258.77 | 45.3% | **GET RID OF** (Negative expectancy after spread+swap) |
| `DualTimeframeConviction` | `CREATIVE` | `GBP_JPY` | 9225 | 0.93 | 0.81 | 1.25 | -\$1,002.91 | 225.3% | **GET RID OF** (Failed In-Sample: IS PF 0.81; DD 225.3%) |
| `EMAPullback` | `CREATIVE` | `GBP_USD` | 3279 | 0.86 | 0.88 | 0.80 | -\$776.36 | 78.2% | **GET RID OF** (Negative expectancy after spread+swap) |
| `FalseBreakoutReversal` | `CREATIVE` | `EUR_USD` | 3045 | 0.88 | 0.87 | 0.93 | -\$430.28 | 47.0% | **GET RID OF** (Negative expectancy after spread+swap) |
| `FisherTransformRSI` | `CREATIVE` | `GBP_USD` | 5 | 6.03 | 0.00 | 7.30 | \$6.02 | 0.2% | **GET RID OF** (Statistically dead: only 5 trades in 17 years, 0 IS wins) |
| `FractalPattern` | `CREATIVE` | `GBP_JPY` | 20152 | 0.76 | 0.65 | 1.08 | -\$3,210.84 | 442.7% | **GET RID OF** (Overtrading churn: 20,152 trades, DD 442.7%) |
| `FridayBear` | `CREATIVE` | `GBP_USD` | 2 | 1.00 | 1.00 | 1.00 | \$0.73 | 0.1% | **GET RID OF** (Statistically dead: 2 trades in 17 years) |
| `GapFader` | `CREATIVE` | `USD_JPY` | 2 | 1.00 | 0.99 | 1.00 | -\$24.06 | 2.8% | **GET RID OF** (Statistically dead: 2 trades in 17 years) |
| `HeikinAshiTrend` | `CREATIVE` | `EUR_USD` | 7224 | 0.74 | 0.75 | 0.74 | -\$1,310.48 | 130.8% | **GET RID OF** (Negative expectancy after spread+swap) |
| `IBSMeanReversion` | `CREATIVE` | `GBP_USD` | 6619 | 0.93 | 0.94 | 0.90 | -\$667.93 | 69.8% | **GET RID OF** (Negative expectancy after spread+swap) |
| `IchimokuCloud` | `CREATIVE` | `GBP_JPY` | 5613 | 1.02 | 0.76 | 1.78 | \$65.20 | 127.1% | **GET RID OF** (Failed IS PF 0.76 + blowup DD 127.1%) |
| `JPYSpecMomentum` | `CREATIVE` | `EUR_USD` | 4687 | 0.85 | 0.89 | 0.74 | -\$694.92 | 72.5% | **GET RID OF** (Negative expectancy after spread+swap) |
| `KeltnerChannelReversal` | `CREATIVE` | `GBP_USD` | 9069 | 0.84 | 0.85 | 0.81 | -\$1,497.78 | 149.5% | **GET RID OF** (Negative expectancy after spread+swap) |
| `LondonOpenVol` | `CREATIVE` | `USD_JPY` | 1590 | 0.88 | 0.83 | 0.96 | -\$96.25 | 9.9% | **GET RID OF** (Negative expectancy after spread+swap) |
| `LondonSessionEMAPullback` | `CREATIVE` | `GBP_JPY` | 637 | 0.91 | 0.90 | 0.92 | -\$74.66 | 10.2% | **GET RID OF** (Negative expectancy after spread+swap) |
| `MACDHistogramDivergence` | `CREATIVE` | `GBP_USD` | 8913 | 0.83 | 0.82 | 0.85 | -\$2,220.77 | 221.9% | **GET RID OF** (Negative expectancy after spread+swap) |
| `MayExhaustionFade` | `CREATIVE` | `GBP_USD` | 584 | 0.85 | 0.88 | 0.78 | -\$91.60 | 9.3% | **GET RID OF** (Negative expectancy after spread+swap) |
| `MidMonthExhaustion` | `CREATIVE` | `USD_CAD` | 5559 | 0.83 | 0.83 | 0.84 | -\$884.24 | 88.8% | **GET RID OF** (Negative expectancy after spread+swap) |
| `MomentumAcceleration` | `CREATIVE` | `GBP_USD` | 1834 | 0.90 | 0.86 | 1.03 | -\$212.18 | 28.0% | **GET RID OF** (Failed In-Sample: IS PF 0.86) |
| `MomentumDivergence` | `CREATIVE` | `EUR_USD` | 2775 | 0.85 | 0.84 | 0.88 | -\$520.74 | 52.2% | **GET RID OF** (Negative expectancy after spread+swap) |
| `MonthPhaseMomentum` | `CREATIVE` | `EUR_USD` | 9338 | 0.84 | 0.85 | 0.80 | -\$1,486.66 | 144.2% | **GET RID OF** (Negative expectancy after spread+swap) |
| `MonthlyRotation` | `CREATIVE` | `GBP_JPY` | 738 | 1.04 | 1.11 | 0.87 | \$22.13 | 4.7% | **GET RID OF** (Collapsed Out-of-Sample: OOS PF 0.87) |
| `NYMidSessionMomentum` | `CREATIVE` | `GBP_USD` | 1152 | 0.91 | 0.87 | 1.01 | -\$0.49 | 0.1% | **GET RID OF** (Failed In-Sample: IS PF 0.87) |
| `OpeningRangeBreakout` | `CREATIVE` | `GBP_JPY` | 1885 | 0.91 | 0.90 | 0.93 | -\$195.37 | 19.8% | **GET RID OF** (Negative expectancy after spread+swap) |
| `OpeningRangeContinuation` | `CREATIVE` | `USD_CHF` | 972 | 0.97 | 1.02 | 0.83 | -\$38.45 | 15.5% | **GET RID OF** (Collapsed Out-of-Sample: OOS PF 0.83) |
| `ParabolicSAR` | `CREATIVE` | `USD_CAD` | 159 | 0.88 | 0.88 | 0.63 | -\$11.55 | 2.0% | **GET RID OF** (Negative expectancy after spread+swap) |
| `PivotPointReversal` | `CREATIVE` | `USD_JPY` | 2616 | 39.70 | 0.58 | 68.73 | \$96,711.85 | 57.9% | **GET RID OF** (Failed IS PF 0.58 + DD 57.9% sizing artifact) |
| `PostNewsAbsorption` | `CREATIVE` | `GBP_USD` | 2229 | 0.76 | 0.74 | 0.83 | -\$559.18 | 56.7% | **GET RID OF** (Negative expectancy after spread+swap) |
| `PostNewsContinuation` | `CREATIVE` | `EUR_USD` | 2062 | 0.79 | 0.78 | 0.82 | -\$477.62 | 50.2% | **GET RID OF** (Negative expectancy after spread+swap) |
| `PriceDeviationMeanReversion` | `CREATIVE` | `USD_CHF` | 4385 | 0.87 | 0.87 | 0.86 | -\$630.69 | 68.2% | **GET RID OF** (Negative expectancy after spread+swap) |
| `RSIDivergence` | `CREATIVE` | `GBP_USD` | 11610 | 0.79 | 0.81 | 0.73 | -\$1,976.62 | 197.9% | **GET RID OF** (Negative expectancy after spread+swap) |
| `RenkoFilteredMomentum` | `CREATIVE` | `USD_JPY` | 19603 | 5.06 | 0.77 | 0.26 | \$87,282.42 | 282.4% | **GET RID OF** (IS PF 0.77, OOS PF 0.26, DD 282.4% sizing blowup) |
| `SeasonalMeanReversion` | `CREATIVE` | `USD_CHF` | 504 | 0.91 | 0.86 | 1.02 | -\$45.17 | 6.8% | **GET RID OF** (Failed In-Sample: IS PF 0.86) |
| `SeasonalPullback` | `CREATIVE` | `USD_CHF` | 342 | 1.17 | 1.27 | 0.93 | \$75.84 | 8.3% | **GET RID OF** (Collapsed Out-of-Sample: OOS PF 0.93) |
| `SessionBreakoutMomentum` | `CREATIVE` | `USD_CHF` | 4333 | 0.96 | 0.97 | 0.92 | -\$392.34 | 55.4% | **GET RID OF** (Negative expectancy after spread+swap) |
| `SessionCloseReversal` | `CREATIVE` | `USD_JPY` | 837 | 0.82 | 0.79 | 0.88 | -\$67.49 | 7.9% | **GET RID OF** (Negative expectancy after spread+swap) |
| `SessionMomentumFlow` | `CREATIVE` | `EUR_USD` | 525 | 1.09 | 1.19 | 0.81 | \$41.32 | 6.6% | **GET RID OF** (Collapsed Out-of-Sample: OOS PF 0.81) |
| `SessionOverlapBreakout` | `CREATIVE` | ALL | 0 | 0.00 | 0.00 | 0.00 | \$0.00 | 0.0% | **GET RID OF** (Zero trades across all pairs) |
| `SessionTransition` | `CREATIVE` | `USD_JPY` | 1939 | 0.84 | 0.73 | 1.07 | -\$151.50 | 19.3% | **GET RID OF** (Failed In-Sample: IS PF 0.73) |
| `StochasticMeanReversion` | `CREATIVE` | `USD_CHF` | 3182 | 0.96 | 0.99 | 0.87 | -\$176.20 | 30.6% | **GET RID OF** (Negative expectancy after spread+swap) |
| `SwingLevelReversal` | `CREATIVE` | `EUR_USD` | 2387 | 0.84 | 0.83 | 0.77 | -\$352.96 | 38.0% | **GET RID OF** (Negative expectancy after spread+swap) |
| `ThursdayRangeExpansion` | `CREATIVE` | `USD_JPY` | 631 | 1.09 | 1.20 | 0.96 | \$52.31 | 6.7% | **GET RID OF** (Collapsed Out-of-Sample: OOS PF 0.96) |
| `TrendRetestEntry` | `CREATIVE` | `EUR_USD` | 3837 | 0.87 | 0.92 | 0.75 | -\$547.82 | 58.5% | **GET RID OF** (Negative expectancy after spread+swap) |
| `TripleEMACrossover` | `CREATIVE` | `GBP_JPY` | 7292 | 0.96 | 0.79 | 1.43 | -\$369.84 | 167.5% | **GET RID OF** (Failed In-Sample: IS PF 0.79; DD 167.5%) |
| `TrueRangeMomentum` | `CREATIVE` | `EUR_USD` | 3510 | 0.86 | 0.88 | 0.80 | -\$497.66 | 53.3% | **GET RID OF** (Negative expectancy after spread+swap) |
| `VWAPMomentum` | `CREATIVE` | `GBP_USD` | 8449 | 0.84 | 0.85 | 0.81 | -\$1,554.32 | 155.6% | **GET RID OF** (Negative expectancy after spread+swap) |
| `VWPReversion` | `CREATIVE` | ALL | 0 | 0.00 | 0.00 | 0.00 | \$0.00 | 0.0% | **GET RID OF** (Zero trades on H1 0-vol bars; lost -38.98 CAD live) |
| `VolClusterMomentum` | `CREATIVE` | `USD_JPY` | 6527 | 0.90 | 0.80 | 1.08 | -\$343.45 | 44.6% | **GET RID OF** (Failed In-Sample: IS PF 0.80) |
| `VolContractionBreakout` | `CREATIVE` | `GBP_USD` | 8267 | 0.94 | 0.95 | 0.91 | -\$4.18 | 0.5% | **GET RID OF** (Negative expectancy after spread+swap) |
| `VolatilitySpikeFade` | `CREATIVE` | `EUR_USD` | 3725 | 0.72 | 0.77 | 0.58 | -\$572.40 | 58.5% | **GET RID OF** (Negative expectancy after spread+swap) |
| `VolumeProfileVA` | `CREATIVE` | `USD_JPY` | 10233 | 7.45 | 0.87 | 15.13 | \$93,396.06 | 108.0% | **GET RID OF** (Failed IS PF 0.87 + DD 108.0% blowup) |
| `VwapPremiumReversion` | `CREATIVE` | `USD_CAD` | 6799 | 0.92 | 0.91 | 0.93 | -\$1,149.85 | 113.0% | **GET RID OF** (Negative expectancy after spread+swap) |
| `WeekdaySession` | `CREATIVE` | `GBP_JPY` | 2844 | 0.95 | 0.94 | 0.99 | -\$2,359.38 | 40.8% | **GET RID OF** (Negative expectancy after spread+swap) |
| `WeekendContinuation` | `CREATIVE` | ALL | 0 | 0.00 | 0.00 | 0.00 | \$0.00 | 0.0% | **GET RID OF** (Zero trades across all pairs) |
| `WickReversal` | `CREATIVE` | `GBP_JPY` | 9753 | 0.89 | 0.76 | 1.25 | -\$1,280.16 | 257.5% | **GET RID OF** (Failed In-Sample: IS PF 0.76; DD 257.5%) |
| `ZScoreReversion` | `CREATIVE` | `USD_CAD` | 2180 | 0.84 | 0.77 | 1.06 | -\$465.05 | 49.4% | **GET RID OF** (Failed In-Sample: IS PF 0.77) |
| `SmaCrossover` | `EXAMPLE` | `USD_JPY` | 2 | 1.00 | 1.00 | 1.00 | -\$385,427.96 | 27.0% | **GET RID OF** (Toy example strategy; 2 trades in 17 years) |
| `LtBollingerSqueeze` | `LONG_TERM` | `USD_CHF` | 2789 | 0.99 | 0.96 | 1.10 | -\$119.08 | 29.0% | **GET RID OF** (Failed In-Sample: IS PF 0.96; negative net PnL) |
| `LtEfficiencyRatio` | `LONG_TERM` | `GBP_JPY` | 2026 | 0.72 | 0.73 | 0.68 | -\$357.06 | 36.2% | **GET RID OF** (Negative expectancy after spread+swap) |
| `LtPullbackEntry` | `LONG_TERM` | `USD_CHF` | 1883 | 0.98 | 1.00 | 0.91 | -\$83.52 | 17.7% | **GET RID OF** (Collapsed Out-of-Sample: OOS PF 0.91) |
| `LtRSI3Momentum` | `LONG_TERM` | `GBP_JPY` | 5013 | 0.81 | 0.79 | 0.87 | -\$905.33 | 90.6% | **GET RID OF** (Negative expectancy on all pairs; currently in Docker!) |
| `LtRangeBreakout` | `LONG_TERM` | `GBP_USD` | 3588 | 0.87 | 0.92 | 0.73 | -\$752.16 | 76.9% | **GET RID OF** (Negative expectancy; fallback target of `sq-gold-m30`) |
| `LtSqueezeMomentum` | `LONG_TERM` | `USD_CHF` | 3254 | 0.94 | 0.93 | 0.94 | -\$457.72 | 47.7% | **GET RID OF** (Negative expectancy after spread+swap) |
| `LtVolRegime` | `LONG_TERM` | `USD_CAD` | 760 | 1.03 | 1.03 | 1.01 | \$15.62 | 23.0% | **GET RID OF** (Sub-1.05 PF; \$0.02/trade expectancy, 23% DD) |
| `AsianRangeMeanReversion` | `PROP` | `GBP_USD` | 15 | 0.54 | 0.66 | 0.42 | -\$8.47 | 1.4% | **GET RID OF** (Negative expectancy + only 15 trades in 17 years) |
| `BollingerBreakoutMomentum` | `PROP` | `GBP_JPY` | 4331 | 0.77 | 0.76 | 0.79 | -\$849.29 | 84.9% | **GET RID OF** (Negative expectancy after spread+swap) |
| `ConnorsRsi2` | `PROP` | `GBP_JPY` | 4300 | 1.25 | 1.38 | 0.92 | -\$999.07 | 209.2% | **GET RID OF** (OOS PF 0.92 + DD 209.2% account blowup) |
| `EmaCloudTrendFilter` | `PROP` | `EUR_USD` | 3567 | 1.45 | 1.11 | 1.07 | -\$768,313.57 | 210.7% | **GET RID OF** (Compounding position-sizing bug: DD 210.7%) |
| `EngulfingReversal` | `PROP` | `USD_CAD` | 1743 | 0.86 | 0.84 | 0.89 | -\$426.94 | 48.1% | **GET RID OF** (Negative expectancy after spread+swap) |
| `InsideBarBreakout` | `PROP` | `EUR_USD` | 230 | 1.04 | 1.11 | 0.80 | \$10.70 | 5.5% | **GET RID OF** (Collapsed Out-of-Sample: OOS PF 0.80) |
| `NyContinuation` | `PROP` | `USD_CHF` | 1111 | 1.19 | 1.01 | 1.76 | \$13.99 | 55.0% | **GET RID OF** (Excessive DD 55.0% for \$13.99 net profit) |
| `OverlapMomentumBurst` | `PROP` | `GBP_USD` | 2290 | 1.11 | 1.21 | 0.85 | \$596.56 | 52.4% | **GET RID OF** (Collapsed Out-of-Sample 2022–2026: OOS PF 0.85, DD 52.4%) |
| `PdhlSweepReversal` | `PROP` | `USD_JPY` | 295 | 0.96 | 0.90 | 1.12 | -\$20.84 | 6.3% | **GET RID OF** (Failed In-Sample: IS PF 0.90; negative net PnL) |
| `RSI3MeanReversion` | `PROP` | `USD_CAD` | 4560 | 0.93 | 0.94 | 0.89 | -\$462.00 | 54.7% | **GET RID OF** (Negative expectancy after spread+swap) |
| `RSIPulseMomentum` | `PROP` | `GBP_JPY` | 5055 | 0.77 | 0.76 | 0.81 | -\$993.52 | 99.3% | **GET RID OF** (Negative expectancy after spread+swap) |
| `SupplyDemandZone` | `PROP` | `GBP_JPY` | 1220 | 1.18 | 1.31 | 0.83 | \$1,038.35 | 44.0% | **GET RID OF** (Collapsed Out-of-Sample: OOS PF 0.83, DD 44.0%) |
| `WeeklyOpenGapFade` | `PROP` | `USD_CHF` | 14 | 0.00 | 0.00 | 0.00 | -\$18.81 | 1.9% | **GET RID OF** (0% win rate across 14 trades) |

---

## 4. Recommended Target Portfolio & Remediation Plan

1. **Deploy / Keep Core CME Micro Futures Portfolio (IBKR):**
   - **Gold (`MGC`):** `MgcM30Breakout49205` (`sq-mgc-m30-breakout-0-24542`), `MgcH1Breakout38023`, `MgcH1Breakout51270`, `MgcH1Breakout24842`, `MgcH1Momentum18703`, `MgcM30Breakout89854` (`sq-mgc-m30-breakout-1-29-102`), `MgcM30Breakout92728` (`sq-mgc-m30-breakout-0-32921`), and built-in `MesTurtleStrategy` (`MesTurtle_LongOnly` on `MGC`).
   - **Nasdaq (`MNQ`):** `MnqH1Breakout31697`, `MnqH1Breakout69250`, `MnqH1Breakout65681`, and `MnqM15MeanRev81824`.
   - **S&P 500 (`MES`):** Built-in `MesTurtleStrategy` (`MesTurtle_LongOnly` on `MES`), `MesH1Breakout30808`, `MesH1Momentum94795`.
2. **Deploy / Keep Core FX Portfolio (OANDA):**
   - Register `MondayReversionStrategy` and `MesTurtleStrategy` in `StrategyCatalog.java`.
   - Run **only** the 7 verified FX strategies on their validated pairs:
     - `MondayReversion` on `GBP_USD`, `USD_CAD`, `EUR_USD`
     - `LondonOpenRangeBreakout` on `EUR_USD` (and optionally `GBP_JPY`)
     - `MonthWeekPhase` on `GBP_USD` *(move off `USD_JPY` immediately)*
     - `LtDoubleMA`, `LtCrossMomentum`, `LtRSIMeanRev` on `USD_CHF`
     - `DlrMeanReversion` on `USD_CAD`
3. **Prune Dead Weight:**
   - Remove `vwpreversion`, `consecbar`, `ltrsi3`, `compmomentum`, `nfpweek`, the 7 deleted `GBP_JPY` SQX keys, and the 3 `sq-gold-m30` fallback aliases from `config/live-config.json` and `docker-compose.yml`.
   - Run `./scripts/prune-db.sh` to prune the 457 lower-tier generated candidates from `data/runtime/generator/strategies.db`.
