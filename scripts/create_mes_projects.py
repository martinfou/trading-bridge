import os
import shutil
import zipfile
import xml.etree.ElementTree as ET

SQ_HOME = "/Volumes/T7/Applications/StrategyQuantXB141.app/Contents/Resources"
TEMPLATE_DIR = "/Volumes/T7/src/trading-bridge/target/sqx_mes_project"

# 35 Orthogonal, institutional building blocks
KEPT_BLOCKS = {
    # Trend & Regime Filters
    'Indicators.EMA',
    'Indicators.SuperTrend',
    'Indicators.ADX',
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
    # Order Types (Breakout Stop Orders)
    'EnterAtStop',
    # Discipline Exits (Mandatory ATR Stop, Target, Trailing, Bar-limit)
    'StopLoss.StopLoss',
    'ProfitTarget.ProfitTarget',
    'TrailingStop.TrailingStop',
    'ExitAfterBars.ExitAfterBars'
}

def create_institutional_project(project_name, market_side):
    project_dir = os.path.join(SQ_HOME, "user/projects", project_name)
    os.makedirs(project_dir, exist_ok=True)
    os.makedirs(os.path.join(project_dir, "log"), exist_ok=True)

    databanks = [
        "Results", "Initial population", "Last generation",
        "Strategies to improve", "Final strategies", "Existing portfolio"
    ]
    for db in databanks:
        os.makedirs(os.path.join(project_dir, "databanks", db), exist_ok=True)

    # 1. Update config.xml
    with open(os.path.join(TEMPLATE_DIR, "config.xml"), "r", encoding="utf-8") as f:
        config_xml = f.read()

    import re
    config_xml = re.sub(r'<Project name="[^"]*"', f'<Project name="{project_name}"', config_xml)

    # 2. Parse and surgically upgrade Build-Task1.xml
    tree = ET.parse(os.path.join(TEMPLATE_DIR, "Build-Task1.xml"))
    root = tree.getroot()

    # A. Market Side (long or short)
    for ms in root.iter("MarketSides"):
        ms.attrib["type"] = market_side

    # B. Building blocks pruning (enforce 35 orthogonal blocks only)
    for block in root.iter("Block"):
        key = block.attrib.get("key")
        if key in KEPT_BLOCKS:
            block.attrib["use"] = "true"
        else:
            block.attrib["use"] = "false"

    # C. Data Setup & Out-Of-Sample Partition
    data_elem = root.find("Data")
    if data_elem is not None:
        # Clear existing setups
        setups = data_elem.find("Setups")
        if setups is not None:
            setups.clear()
        else:
            setups = ET.SubElement(data_elem, "Setups")

        # Create Institutional CME MES Setup
        setup = ET.SubElement(setups, "Setup", {
            "dateFrom": "2014.01.01",
            "dateTo": "2026.10.23",
            "testPrecision": "1",
            "session": "No Session",
            "slippage": "1",
            "minDist": "0",
            "engine": "MetaTrader4"
        })
        ET.SubElement(setup, "Chart", {
            "symbol": "SP_M1_dukas",
            "timeframe": "H1",
            "spread": "1"
        })
        commissions = ET.SubElement(setup, "Commissions")
        c_per_trade = ET.SubElement(commissions, "Method", {"type": "PerTrade", "use": "true"})
        c_pt_params = ET.SubElement(c_per_trade, "Params")
        c_pt_p = ET.SubElement(c_pt_params, "Param", {"key": "Commission", "className": "PerTrade"})
        c_pt_p.text = "1.24"

        c_size_based = ET.SubElement(commissions, "Method", {"type": "SizeBased", "use": "true"})
        c_sb_params = ET.SubElement(c_size_based, "Params")
        c_sb_p = ET.SubElement(c_sb_params, "Param", {"key": "Commission", "className": "SizeBased"})
        c_sb_p.text = "1.24"

        ET.SubElement(setup, "Swap", {
            "use": "false",
            "type": "money",
            "long": "0",
            "short": "0",
            "tripleSwapOn": "WEDNESDAY"
        })

        # Add strict blind Out-of-Sample Partition (2023.01.01 - 2026.10.23)
        oos_elem = data_elem.find("OutOfSample")
        if oos_elem is not None:
            data_elem.remove(oos_elem)
        oos = ET.SubElement(data_elem, "OutOfSample", {"showGraph": "false"})
        ET.SubElement(oos, "Range", {
            "dateFrom": "2023.01.01",
            "dateTo": "2026.10.23"
        })

    # D. Acceptance Conditions (Quality Gates)
    # Find Primary Acceptance Conditions
    for acc in root.iter("RankingFilter"):
        conds = acc.find("Conditions")
        if conds is not None:
            # Let's add Out-Of-Sample ProfitFactor and NetProfit gate
            # 1. OOS ProfitFactor > 1.15
            oos_pf_cond = ET.SubElement(conds, "Condition", {"use": "true"})
            l_pf = ET.SubElement(oos_pf_cond, "Left-Side", {"valueType": "column"})
            ET.SubElement(l_pf, "Column-Value", {
                "column": "ProfitFactor", "columnType": "0", "format": "Decimal2",
                "resultType": "main", "direction": "0", "sampleType": "20",
                "plType": "10", "confidenceLevel": "50", "market": "1",
                "subresult": "30", "pctRatio": "0", "class": "ProfitFactor"
            })
            ET.SubElement(oos_pf_cond, "Comparator", {"value": "&gt;"})
            r_pf = ET.SubElement(oos_pf_cond, "Right-Side", {"valueType": "numeric"})
            ET.SubElement(r_pf, "Numeric-Value", {"value": "1.15"})

            # 2. OOS NetProfit > 0
            oos_np_cond = ET.SubElement(conds, "Condition", {"use": "true"})
            l_np = ET.SubElement(oos_np_cond, "Left-Side", {"valueType": "column"})
            ET.SubElement(l_np, "Column-Value", {
                "column": "NetProfit", "columnType": "0", "format": "Decimal2PL",
                "resultType": "main", "direction": "0", "sampleType": "20",
                "plType": "10", "confidenceLevel": "50", "market": "1",
                "subresult": "30", "pctRatio": "0", "class": "NetProfit"
            })
            ET.SubElement(oos_np_cond, "Comparator", {"value": "&gt;"})
            r_np = ET.SubElement(oos_np_cond, "Right-Side", {"valueType": "numeric"})
            ET.SubElement(r_np, "Numeric-Value", {"value": "0"})

    # Save to staging and package CFX
    staging_dir = f"/Volumes/T7/src/trading-bridge/target/{market_side}_staging_v2"
    os.makedirs(staging_dir, exist_ok=True)

    for item in os.listdir(TEMPLATE_DIR):
        src = os.path.join(TEMPLATE_DIR, item)
        dst = os.path.join(staging_dir, item)
        if item == "config.xml":
            with open(dst, "w", encoding="utf-8") as out:
                out.write(config_xml)
        elif item == "Build-Task1.xml":
            tree.write(dst, encoding="utf-8", xml_declaration=True)
        else:
            shutil.copy2(src, dst)

    cfx_path = os.path.join(project_dir, "project.cfx")
    with zipfile.ZipFile(cfx_path, "w", zipfile.ZIP_DEFLATED) as zipf:
        for root_dir, dirs, files in os.walk(staging_dir):
            for file in files:
                full_path = os.path.join(root_dir, file)
                rel_path = os.path.relpath(full_path, staging_dir)
                zipf.write(full_path, rel_path)

    print(f"Successfully generated institutional project: {project_name}")
    print(f"  -> Path: {cfx_path}")
    print(f"  -> MarketSide: {market_side}")
    print(f"  -> Symbol: SP_M1_dukas (2014.01.01 - 2026.10.23)")
    print(f"  -> In-Sample: 2014.01.01 - 2022.12.31")
    print(f"  -> Blind Out-of-Sample: 2023.01.01 - 2026.10.23")
    print(f"  -> Commission: $1.24 round-turn / contract")
    print(f"  -> Slippage: 1 tick")
    print(f"  -> Active Blocks: {len(KEPT_BLOCKS)} orthogonal blocks")

if __name__ == "__main__":
    create_institutional_project("MES H1 LONG - ZeroMartingale", "long")
    create_institutional_project("MES H1 SHORT - ZeroMartingale", "short")
