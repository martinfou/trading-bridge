---
name: strategyquant-x-manager
description: 'Manage, configure, automate, run, and audit StrategyQuant X (SQX) strategy generation pipelines, project suites, databanks, and backtest results. Use when generating, scanning, filtering, backtesting, or deploying SQX algorithmic trading strategies.'
---

# StrategyQuant X (SQX) Manager Skill

Comprehensive automation, orchestration, and evaluation workflow for StrategyQuant X (Build 141+) running on macOS / UNIX.

## 1. Architecture & Environment

* **SQX Installation Path (`SQ_HOME`)**:
  `/Volumes/T7/Applications/StrategyQuantXB141.app/Contents/Resources`
* **Command Line Interface (`sqcli`)**:
  `/Volumes/T7/Applications/StrategyQuantXB141.app/Contents/Resources/sqcli`
* **Local HTTP Control API**:
  `http://localhost:5052/call?cmd=<command>` (active whenever `sqcli` is executing)
* **Projects Directory**:
  `$SQ_HOME/user/projects/<project_name>/`
* **Databank Storage**:
  `$SQ_HOME/user/projects/<project_name>/databanks/<databank_name>/`

---

## 2. Core CLI Operations

### Project Lifecycle
```bash
# List all installed projects
$SQ_HOME/sqcli -project action=list

# Start strategy generation on a project
$SQ_HOME/sqcli -project action=start name="MES H1 Long"

# Check generation status
$SQ_HOME/sqcli -project action=status name="MES H1 Long"

# Pause or Resume generation
$SQ_HOME/sqcli -project action=pause name="MES H1 Long"
$SQ_HOME/sqcli -project action=resume name="MES H1 Long"

# Stop generation gracefully
$SQ_HOME/sqcli -project action=stop name="MES H1 Long"

# Load or Save project configuration (.cfx)
$SQ_HOME/sqcli -project action=loadconfig name="MES H1 Long" file="path/to/project.cfx"
$SQ_HOME/sqcli -project action=saveconfig name="MES H1 Long" file="path/to/project.cfx"
```

### Databank Management
```bash
# List all databanks for a project
$SQ_HOME/sqcli -databank action=list project="MES H1 Long"

# Count strategies in a databank
$SQ_HOME/sqcli -databank action=count project="MES H1 Long" name=Results

# Sync databanks to file system (.sqx files)
$SQ_HOME/sqcli -databank action=synctofiles project="MES H1 Long" name=Results

# Export databank to CSV or XLSX
$SQ_HOME/sqcli -databank action=export project="MES H1 Long" name=Results file="exports/mes_results.csv"
```

---

## 3. Institutional Project Suite Generator

The institutional project suite script generates clean, robust SQX project configurations (`.cfx` files) with strict quality guardrails:
* **Position Sizing**: 100% Fixed 1-Contract (Zero Martingale / Anti-Grid).
* **Partitioning**: 70% In-Sample (2014–2022) / 30% Blind Out-of-Sample (2023–2026).
* **Friction & Costs**: Realistic CME commissions ($1.24 round-turn) + 1 tick slippage.
* **Search Space Pruning**: Restricted to 35 orthogonal building blocks (ATR stops, SuperTrend, EMA, Daily Reference Levels).

```bash
# Generate CME Micro Futures Suite (MES, MNQ, MGC, M2K)
python3 scripts/create_institutional_project_suite.py

# Generate OANDA High-Expectancy Forex Suite (GBPJPY, EURUSD, GBPUSD, USDJPY)
python3 scripts/create_oanda_project_suite.py
```

---

## 4. Quantitative Strategy Extraction & Filtering

To inspect `.sqx` files without launching the SQX GUI:
Use [`scripts/extract_sqx_backtest.py`](file:///Volumes/T7/src/trading-bridge/scripts/extract_sqx_backtest.py):

```python
from extract_sqx_backtest import extract_backtest_data
from pathlib import Path

sqx_path = Path("/Volumes/T7/Applications/StrategyQuantXB141.app/Contents/Resources/user/projects/MES H1 Long/databanks/Results/Strategy 0.4192.sqx")
metrics = extract_backtest_data(sqx_path)

print(f"Net Profit: ${metrics['net_profit']:,.2f}")
print(f"Profit Factor: {metrics['profit_factor']:.2f}")
print(f"IS Annual Profit: ${metrics['is_annual_profit']:,.2f}")
print(f"OOS Annual Profit: ${metrics['oos_annual_profit']:,.2f}")
print(f"Max Drawdown: ${metrics['max_drawdown']:,.2f} ({metrics['max_drawdown_pct']:.1f}%)")
print(f"Ret/DD: {metrics['ret_dd_ratio']:.2f}")
print(f"Rules: {metrics['rules_summary']}")
```

---

## 5. Telemetry & Hermes Web Integration

To push live strategy generation status, candidate metrics, and equity curves to Hermes Web:
* Run daemon: `PYTHONPATH=scripts python3 scripts/sqx_telemetry_pusher.py`
* Telemetry Endpoint: `https://hermes.martinfournier.com/api/trading/strategy-creation/telemetry`
* Backtesting UI: `https://hermes.martinfournier.com/trading/backtesting`

---

## 6. Execution Workflow: Finding New Strategies

1. **Verify or Update Historical Market Data**:
   Ensure M1 tick / bar data is updated:
   ```bash
   $SQ_HOME/sqcli -data action=update
   ```
2. **Build / Deploy Project Suite**:
   Compile fresh `.cfx` configurations for target assets:
   ```bash
   python3 scripts/create_institutional_project_suite.py
   ```
3. **Launch Headless Background Search**:
   ```bash
   $SQ_HOME/sqcli -project action=start name="MES H1 Long"
   ```
4. **Audit and Extract Discovered Candidates**:
   Scan databanks using `scripts/extract_sqx_backtest.py` to filter by:
   - Positive OOS Net Profit (`oos_profit > 0`)
   - OOS Profit Factor >= 1.20
   - Ret/DD >= 4.0
   - Trades count >= 50
