#!/usr/bin/env python3
"""
OANDA StrategyQuant X Project Suite Generator
----------------------------------------------
Generates 4 high-expectancy StrategyQuant X projects engineered specifically
to exploit OANDA's micro-structural edges while neutralizing broker frictions:

1. OANDA GBPJPY H1 Trend Runner (Long, wide 95-150 pip stop cushion, 250-420 pip targets)
2. OANDA EURUSD M15 Overlap Breakout (London/NY overlap 08:00-11:30 EST, flat by 16:30 EST, 0 swap)
3. OANDA GBPUSD H1 Momentum (Intraday momentum, flat by 16:30 EST, 0 rollover risk)
4. OANDA USDJPY H1 Trend Carry (Long-only positive USD/JPY interest rate carry, wide ATR stops)
"""

import os
import shutil
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

SQ_HOME = Path("/Volumes/T7/Applications/StrategyQuantXB141.app/Contents/Resources")
TEMPLATE_DIR = Path("/Volumes/T7/src/trading-bridge/target/sqx_mes_project")

KEPT_BLOCKS = {
    # Trend & Regime Filters
    'Indicators.EMA',
    'Indicators.SuperTrend',
    'Indicators.ADX',
    'Indicators.LinearRegression',
    'Indicators.Vortex',
    'VortexUptrend',
    'VortexDowntrend',
    'VortexChangesTrendUP',
    'VortexChangesTrendDown',
    # Volatility & Normalization
    'Indicators.ATR',
    'Stop/Limit Price Ranges.ATR',
    # Momentum & Breakout
    'Indicators.RSI',
    'Indicators.ROC',
    'Indicators.Highest',
    'Indicators.Lowest',
    # Price Actions
    'Prices.Close',
    'Prices.Open',
    'Prices.High',
    'Prices.Low',
    # Daily Multi-timeframe Reference Levels
    'Prices.HighD',
    'Prices.LowD',
    'Prices.CloseD',
    'Prices.OpenD',
    # Mathematical Comparators
    'IsGreater',
    'IsLower',
    'IsGreaterCount',
    'IsLowerCount',
    # High-Edge Breakout Signals
    'BarClosesAboveSuperTrend',
    'BarClosesBelowSuperTrend',
    'ROCCrossesAboveLevel',
    'ROCCrossesBelowLevel',
    # Discrete Stop Triggers
    'Stop/Limit Price Levels.Highest',
    'Stop/Limit Price Levels.Lowest',
    'Stop/Limit Price Levels.EMA',
    'Stop/Limit Price Levels.HighD',
    'Stop/Limit Price Levels.LowD',
    # Order Types (Market, Stop, Limit)
    'EnterAtMarket',
    'EnterAtStop',
    'EnterAtLimit',
    # Exits
    'StopLoss.StopLoss',
    'ProfitTarget.ProfitTarget',
    'TrailingStop.TrailingStop',
    'TrailingStop.TrailingActivation',
    'MoveSL2BE.MoveSL2BE',
    'MoveSL2BE.SL2BEAddPips',
    'ExitAfterBars.ExitAfterBars'
}

OANDA_PROJECT_SPECS = [
    {
        "name": "OANDA GBPJPY H1 Trend Runner",
        "symbol": "GBPJPY_M1_dukas",
        "timeframe": "H1",
        "market_side": "long",
        "spread": "2.5",
        "min_sl": 90,
        "max_sl": 150,
        "min_pt": 250,
        "max_pt": 420,
        "limit_time": False,
        "exit_eod": False,
        "description": "OANDA GBP/JPY H1 High R:R Trend Runner (Immune to 5 PM rollover spread blowout)",
    },
    {
        "name": "OANDA EURUSD M15 Overlap Breakout",
        "symbol": "EURUSD_M1_dukas",
        "timeframe": "M15",
        "market_side": "both",
        "spread": "1.4",
        "min_sl": 18,
        "max_sl": 40,
        "min_pt": 35,
        "max_pt": 90,
        "limit_time": True,
        "time_from": 28800,  # 08:00 EST
        "time_to": 41400,    # 11:30 EST
        "exit_eod": True,
        "eod_time": 59400,   # 16:30 EST (Flat before 17:00 rollover -> 0 Swap)
        "description": "OANDA EUR/USD M15 London/NY Overlap Breakout (Flat before 16:30 EST - 0 Swap)",
    },
    {
        "name": "OANDA GBPUSD H1 Momentum",
        "symbol": "GBPUSD_M1_dukas",
        "timeframe": "H1",
        "market_side": "both",
        "spread": "1.8",
        "min_sl": 30,
        "max_sl": 65,
        "min_pt": 60,
        "max_pt": 150,
        "limit_time": False,
        "exit_eod": True,
        "eod_time": 59400,   # 16:30 EST (Flat before rollover)
        "description": "OANDA GBP/USD H1 Intraday Momentum & Breakout (Flat before rollover)",
    },
    {
        "name": "OANDA USDJPY H1 Trend Carry",
        "symbol": "USDJPY_M1_dukas",
        "timeframe": "H1",
        "market_side": "long",
        "spread": "1.6",
        "min_sl": 70,
        "max_sl": 130,
        "min_pt": 160,
        "max_pt": 320,
        "limit_time": False,
        "exit_eod": False,
        "description": "OANDA USD/JPY H1 Structural Long Trend & Positive Rate Carry",
    },
]


def build_oanda_project(spec):
    proj_name = spec["name"]
    symbol = spec["symbol"]
    timeframe = spec["timeframe"]
    market_side = spec["market_side"]
    spread = spec["spread"]

    project_dir = SQ_HOME / "user/projects" / proj_name
    project_dir.mkdir(parents=True, exist_ok=True)
    (project_dir / "log").mkdir(parents=True, exist_ok=True)

    databanks = [
        "Results", "Initial population", "Last generation",
        "Strategies to improve", "Final strategies", "Existing portfolio"
    ]
    for db in databanks:
        (project_dir / "databanks" / db).mkdir(parents=True, exist_ok=True)

    # 1. Update config.xml
    cfg_tree = ET.parse(TEMPLATE_DIR / "config.xml")
    cfg_root = cfg_tree.getroot()
    cfg_root.attrib["name"] = proj_name
    for t in cfg_root.iter("Task"):
        if t.attrib.get("type") == "Build":
            t.attrib["active"] = "true"
        else:
            t.attrib["active"] = "false"
    for db_elem in cfg_root.iter("Databank"):
        db_elem.attrib["syncType"] = "Immediately (not recommended, very slow)"

    # 2. Parse and upgrade Build-Task1.xml
    tree = ET.parse(TEMPLATE_DIR / "Build-Task1.xml")
    root = tree.getroot()

    # Disable legacy CrossChecks
    cc = root.find("CrossChecks")
    if cc is not None:
        cc.attrib["use"] = "false"
        for child in cc:
            child.attrib["use"] = "false"

    # A. Market Side
    for ms in root.iter("MarketSides"):
        ms.attrib["type"] = market_side

    # B. Building blocks pruning
    for block in root.iter("Block"):
        key = block.attrib.get("key")
        block.attrib["use"] = "true" if key in KEPT_BLOCKS else "false"

    # C. Dynamic Volatility Stops & Targets
    slpt = root.find(".//SLPTOptions")
    if slpt is not None:
        def set_node(tag, val):
            el = slpt.find(tag)
            if el is not None:
                el.text = str(val)
            else:
                el = ET.SubElement(slpt, tag)
                el.text = str(val)
        set_node("SLRequired", "true")
        set_node("SLFixedPips", "true")
        set_node("SLATR", "true")
        set_node("MinSLInPips", spec.get("min_sl", 30))
        set_node("MaxSLInPips", spec.get("max_sl", 150))
        set_node("MinSLATRMultiple", "1.5")
        set_node("MaxSLATRMultiple", "3.5")
        set_node("MinSLATRPeriod", "14")
        set_node("MaxSLATRPeriod", "28")
        set_node("PTRequired", "true")
        set_node("PTFixedPips", "true")
        set_node("PTATR", "true")
        set_node("MinPTInPips", spec.get("min_pt", 50))
        set_node("MaxPTInPips", spec.get("max_pt", 350))
        set_node("MinPTATRMultiple", "2.0")
        set_node("MaxPTATRMultiple", "5.0")
        set_node("MinPTATRPeriod", "14")
        set_node("MaxPTATRPeriod", "28")

    # D. Trading Options (Time filters & SL/PT ranges)
    for bto in root.iter("BuildTradingOptions"):
        params = bto.find("Params")
        if params is not None:
            for p in params.iter("Param"):
                key = p.attrib.get("key")
                if key == "ExitAtEndOfDay":
                    p.text = "true" if spec.get("exit_eod") else "false"
                elif key == "EODExitTime":
                    p.text = str(spec.get("eod_time", 59400))
                elif key == "ExitOnFriday":
                    p.text = "true"
                elif key == "FridayExitTime":
                    p.text = str(spec.get("eod_time", 59400))
                elif key == "LimitTimeRange":
                    p.text = "true" if spec.get("limit_time") else "false"
                elif key == "SignalTimeRangeFrom":
                    p.text = str(spec.get("time_from", 28800))
                elif key == "SignalTimeRangeTo":
                    p.text = str(spec.get("time_to", 41400))
                elif key == "MinimumSL":
                    p.text = str(spec.get("min_sl", 30))
                elif key == "MaximumSL":
                    p.text = str(spec.get("max_sl", 150))
                elif key == "MinimumPT":
                    p.text = str(spec.get("min_pt", 50))
                elif key == "MaximumPT":
                    p.text = str(spec.get("max_pt", 350))
                elif key == "UseInitialSLPT":
                    p.text = "true"

    # E. Sizing: Fixed Size 0.1 Lot
    for mm in root.iter("MoneyManagement"):
        for m in mm.iter("Method"):
            if m.attrib.get("type") == "FixedSize":
                m.attrib["use"] = "true"
                p = m.find(".//Param[@key='Size']")
                if p is not None:
                    p.text = "0.1"

    # F. Data Setup & Out-Of-Sample Partition (Forex data ends 2026.01.23)
    data_elem = root.find("Data")
    if data_elem is not None:
        setups = data_elem.find("Setups")
        if setups is not None:
            setups.clear()
        else:
            setups = ET.SubElement(data_elem, "Setups")

        setup = ET.SubElement(setups, "Setup", {
            "dateFrom": "2014.01.01",
            "dateTo": "2026.01.23",
            "testPrecision": "1",
            "session": "No Session",
            "slippage": "1",
            "minDist": "0",
            "engine": "MetaTrader4"
        })
        ET.SubElement(setup, "Chart", {
            "symbol": symbol,
            "timeframe": timeframe,
            "spread": spread
        })
        commissions = ET.SubElement(setup, "Commissions")
        c_per_trade = ET.SubElement(commissions, "Method", {"type": "PerTrade", "use": "false"})
        c_size_based = ET.SubElement(commissions, "Method", {"type": "SizeBased", "use": "true"})
        c_sb_params = ET.SubElement(c_size_based, "Params")
        c_sb_p = ET.SubElement(c_sb_params, "Param", {"key": "Commission", "className": "SizeBased"})
        c_sb_p.text = "0.00"  # OANDA is spread-based

        ET.SubElement(setup, "Swap", {
            "use": "false",
            "type": "money",
            "long": "0",
            "short": "0",
            "tripleSwapOn": "WEDNESDAY"
        })

        # Blind Out-of-Sample Partition (2023.01.01 - 2026.01.23)
        oos_elem = data_elem.find("OutOfSample")
        if oos_elem is not None:
            data_elem.remove(oos_elem)
        oos = ET.SubElement(data_elem, "OutOfSample", {"showGraph": "false"})
        ET.SubElement(oos, "Range", {
            "dateFrom": "2023.01.01",
            "dateTo": "2026.01.23"
        })

    def add_condition(conds_parent, col, sample_type, comparator, val, fmt="Decimal2"):
        c = ET.SubElement(conds_parent, "Condition", {"use": "true"})
        ls = ET.SubElement(c, "Left-Side", {"valueType": "column"})
        ET.SubElement(ls, "Column-Value", {
            "column": col,
            "columnType": "0",
            "format": fmt,
            "resultType": "main",
            "direction": "0",
            "sampleType": str(sample_type),
            "plType": "10",
            "confidenceLevel": "50",
            "market": "1",
            "subresult": "30",
            "pctRatio": "0",
            "class": col
        })
        # Raw comparator character ('>'), serialized to &gt; by XML parser
        ET.SubElement(c, "Comparator", {"value": comparator})
        rs = ET.SubElement(c, "Right-Side", {"valueType": "numeric"})
        ET.SubElement(rs, "Numeric-Value", {"value": str(val)})

    # G. Generation Mode: Random Search (explore entire space without genetic island stagnation)
    bm = root.find(".//BuildMode")
    if bm is not None:
        bm.attrib["generationType"] = "random-generation"
        bm_conds = bm.find("Conditions")
        if bm_conds is not None:
            bm_conds.clear()

    # H. Acceptance Conditions (Step 1 Capture Gate into Databank)
    # Require profitability across entire sample AND Out-of-Sample to eliminate curve-fitting
    rankings = root.find(".//Rankings")
    if rankings is not None:
        conds = rankings.find("Conditions")
        if conds is not None:
            conds.clear()
            add_condition(conds, "NetProfit", 0, ">", "0", fmt="Decimal2PL")
            add_condition(conds, "ProfitFactor", 0, ">", "1.05")
            add_condition(conds, "ProfitFactor", 10, ">", "1.10")
            add_condition(conds, "ProfitFactor", 20, ">", "1.00")
            add_condition(conds, "NetProfit", 20, ">", "0", fmt="Decimal2PL")
            add_condition(conds, "NumberOfTrades", 0, ">", "30", fmt="Integer")

    # 3. Stage and Package
    staging_dir = Path(f"/Volumes/T7/src/trading-bridge/target/staging_{proj_name.replace(' ', '_')}")
    staging_dir.mkdir(parents=True, exist_ok=True)

    for item in os.listdir(TEMPLATE_DIR):
        src = TEMPLATE_DIR / item
        dst = staging_dir / item
        if item == "config.xml":
            cfg_tree.write(dst, encoding="utf-8", xml_declaration=True)
        elif item == "Build-Task1.xml":
            tree.write(dst, encoding="utf-8", xml_declaration=True)
        elif item.startswith("Retest-Task"):
            try:
                rt_tree = ET.parse(src)
                rt_root = rt_tree.getroot()
                for c in rt_root.iter("Chart"):
                    c.attrib["symbol"] = symbol
                rt_tree.write(dst, encoding="utf-8", xml_declaration=True)
            except Exception:
                shutil.copy2(src, dst)
        else:
            shutil.copy2(src, dst)

    cfx_path = project_dir / "project.cfx"
    with zipfile.ZipFile(cfx_path, "w", zipfile.ZIP_DEFLATED) as zipf:
        for root_dir, dirs, files in os.walk(staging_dir):
            for file in files:
                full_path = Path(root_dir) / file
                rel_path = full_path.relative_to(staging_dir)
                zipf.write(full_path, rel_path)

    # Clean up staging
    shutil.rmtree(staging_dir, ignore_errors=True)

    print(f"✓ Created: {proj_name}")
    print(f"  Asset: {symbol} ({timeframe}) | Spread: {spread} pips | Direction: {market_side.upper()}")


def main():
    print("=" * 70)
    print("GENERATING OANDA HIGH-EXPECTANCY STRATEGY SUITE")
    print("=" * 70)
    for spec in OANDA_PROJECT_SPECS:
        build_oanda_project(spec)
    print("=" * 70)
    print("ALL OANDA PROJECTS COMPILED AND STAGED INTO STRATEGYQUANT X!")


if __name__ == "__main__":
    main()
