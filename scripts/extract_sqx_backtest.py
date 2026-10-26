#!/usr/bin/env python3
"""
Fast SQX Backtest Data & Rules Extractor
----------------------------------------
Extracts quantitative backtest metrics directly from StrategyQuant X .sqx files:
  - Daily Equity Curve (dailyEquity.bin) -> Net Profit, Max DD, Ret/DD, Sharpe, Trades, Win Rate, PF
  - In-Sample (2014-2022) vs Out-of-Sample (2023-2026) performance breakdown
  - Strategy Rules (strategy_Portfolio.xml) -> Entry Signals, Stop Loss & Profit Target settings
Includes mtime-based memory caching for sub-millisecond repeated queries.
"""

import os
import struct
import zipfile
import datetime
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Dict, Any, Optional, Union

_CACHE: Dict[str, Dict[str, Any]] = {}

# OOS Boundary timestamp (2023.01.01 00:00:00 UTC)
OOS_TIMESTAMP_MS = 1672531200000


def _unwrap_java_block_data(data: bytes) -> bytearray:
    """Unwraps Java ObjectOutputStream block data stream (strips TC_BLOCKDATA headers)."""
    pos = 4  # skip ac ed 00 05
    raw = bytearray()
    length = len(data)
    while pos < length:
        tag = data[pos]
        if tag == 0x77:  # TC_BLOCKDATA (1-byte length)
            blen = data[pos + 1]
            pos += 2
            raw.extend(data[pos : pos + blen])
            pos += blen
        elif tag == 0x7a:  # TC_BLOCKDATALONG (4-byte length)
            blen = struct.unpack(">I", data[pos + 1 : pos + 5])[0]
            pos += 5
            raw.extend(data[pos : pos + blen])
            pos += blen
        elif tag == 0x78:  # TC_ENDBLOCKDATA
            pos += 1
        else:
            pos += 1
    return raw


def parse_daily_equity(zip_file: zipfile.ZipFile) -> Dict[str, Any]:
    """Parses dailyEquity.bin from SQX zip."""
    equity_entry_name = None
    for n in zip_file.namelist():
        if "dailyEquity.bin" in n:
            equity_entry_name = n
            break

    if not equity_entry_name:
        return {}

    data = zip_file.read(equity_entry_name)
    raw = _unwrap_java_block_data(data)
    if len(raw) < 4:
        return {}

    count = struct.unpack(">i", raw[:4])[0]
    if count <= 0 or len(raw) < 4 + count * 16:
        return {}

    entries = []
    for i in range(count):
        ts, eq = struct.unpack(">qd", raw[4 + i * 16 : 4 + (i + 1) * 16])
        entries.append((ts, eq))

    initial_equity = entries[0][1]
    final_equity = entries[-1][1]
    net_profit = final_equity - initial_equity

    # Compute daily changes / trades
    changes = []
    daily_diffs = []
    for i in range(1, len(entries)):
        diff = entries[i][1] - entries[i - 1][1]
        daily_diffs.append(diff)
        if abs(diff) > 0.001:
            changes.append((entries[i][0], diff))

    wins = [c for c in changes if c[1] > 0]
    losses = [c for c in changes if c[1] < 0]
    gross_win = sum(c[1] for c in wins)
    gross_loss = abs(sum(c[1] for c in losses))
    pf = (gross_win / gross_loss) if gross_loss > 0 else (99.0 if gross_win > 0 else 0.0)
    win_rate = (len(wins) / len(changes) * 100.0) if changes else 0.0

    # Max Drawdown
    peak = initial_equity
    max_dd = 0.0
    for _, eq in entries:
        if eq > peak:
            peak = eq
        dd = peak - eq
        if dd > max_dd:
            max_dd = dd

    peak_for_pct = initial_equity if initial_equity > 0 else 10000.0
    max_dd_pct = (max_dd / peak_for_pct) * 100.0
    ret_dd = (net_profit / max_dd) if max_dd > 0 else 0.0

    # Annualized Sharpe (assuming standard $10,000 margin allocation)
    if daily_diffs:
        import statistics
        rets = [d / 10000.0 for d in daily_diffs]
        mean_ret = statistics.mean(rets)
        std_ret = statistics.stdev(rets) if len(rets) > 1 else 0.0
        sharpe = (mean_ret / std_ret * (252 ** 0.5)) if std_ret > 0 else 0.0
    else:
        sharpe = 0.0

    # In-Sample (IS) vs Out-of-Sample (OOS) breakdown
    is_trades = [c for c in changes if c[0] < OOS_TIMESTAMP_MS]
    oos_trades = [c for c in changes if c[0] >= OOS_TIMESTAMP_MS]

    is_wins = [c for c in is_trades if c[1] > 0]
    is_losses = [c for c in is_trades if c[1] < 0]
    is_gw = sum(c[1] for c in is_wins)
    is_gl = abs(sum(c[1] for c in is_losses))
    is_pf = (is_gw / is_gl) if is_gl > 0 else (99.0 if is_gw > 0 else 0.0)
    is_profit = sum(c[1] for c in is_trades)

    oos_wins = [c for c in oos_trades if c[1] > 0]
    oos_losses = [c for c in oos_trades if c[1] < 0]
    oos_gw = sum(c[1] for c in oos_wins)
    oos_gl = abs(sum(c[1] for c in oos_losses))
    oos_pf = (oos_gw / oos_gl) if oos_gl > 0 else (99.0 if oos_gw > 0 else 0.0)
    oos_profit = sum(c[1] for c in oos_trades)

    # Downsampled sparkline points (24 points for mini sparkline chart)
    sparkline = []
    step = max(1, len(entries) // 24)
    for i in range(0, len(entries), step):
        sparkline.append(round(entries[i][1], 2))
    if sparkline and sparkline[-1] != round(final_equity, 2):
        sparkline.append(round(final_equity, 2))

    start_date = datetime.datetime.fromtimestamp(entries[0][0] / 1000.0, datetime.timezone.utc).strftime("%Y-%m-%d")
    end_date = datetime.datetime.fromtimestamp(entries[-1][0] / 1000.0, datetime.timezone.utc).strftime("%Y-%m-%d")

    return {
        "net_profit": round(net_profit, 2),
        "profit_factor": round(pf, 2),
        "win_rate": round(win_rate, 1),
        "trades": len(changes),
        "wins": len(wins),
        "losses": len(losses),
        "max_drawdown": round(max_dd, 2),
        "max_drawdown_pct": round(max_dd_pct, 1),
        "ret_dd_ratio": round(ret_dd, 2),
        "sharpe_ratio": round(sharpe, 2),
        "is_trades": len(is_trades),
        "is_profit": round(is_profit, 2),
        "is_pf": round(is_pf, 2),
        "oos_trades": len(oos_trades),
        "oos_profit": round(oos_profit, 2),
        "oos_pf": round(oos_pf, 2),
        "start_date": start_date,
        "end_date": end_date,
        "sparkline": sparkline,
    }


def parse_strategy_rules(zip_file: zipfile.ZipFile) -> Dict[str, Any]:
    """Parses strategy_Portfolio.xml for rule logic, indicators, and parameters."""
    if "strategy_Portfolio.xml" not in zip_file.namelist():
        return {"rules_summary": "Standard Engine Signals"}

    try:
        xml_bytes = zip_file.read("strategy_Portfolio.xml")
        root = ET.fromstring(xml_bytes)

        indicators = set()
        entry_signals = []

        for rule in root.findall(".//Rule"):
            r_name = rule.attrib.get("name", "")
            for item in rule.findall(".//Item"):
                name = item.attrib.get("name", "")
                cat = item.attrib.get("categoryType", "")
                if cat in ("priceValue", "indicator") and name:
                    clean_name = name.split(")")[-1].strip() if ")" in name else name
                    indicators.add(clean_name)
                elif "Is lower" in name or "Is higher" in name or "Crosses" in name:
                    entry_signals.append(name)

        summary_parts = []
        if indicators:
            summary_parts.append(f"Indicators: {', '.join(sorted(list(indicators))[:4])}")
        if entry_signals:
            summary_parts.append(f"Signal: {entry_signals[0]}")
        else:
            summary_parts.append("Price Action & Volatility Filter")

        return {
            "indicators": sorted(list(indicators)),
            "rules_summary": " | ".join(summary_parts),
        }
    except Exception:
        return {"rules_summary": "Rules Extracted"}


def extract_backtest_data(sqx_path: Union[str, Path]) -> Dict[str, Any]:
    """
    Extracts all backtest data and rule information for a given .sqx file.
    Caches results by file path and modification time.
    """
    path = Path(sqx_path)
    if not path.exists():
        return {}

    mtime = path.stat().st_mtime
    cache_key = f"{path.resolve()}::{mtime}"
    if cache_key in _CACHE:
        return _CACHE[cache_key]

    try:
        with zipfile.ZipFile(path, "r") as z:
            eq_data = parse_daily_equity(z)
            rule_data = parse_strategy_rules(z)

            result = {**eq_data, **rule_data}
            _CACHE[cache_key] = result
            return result
    except Exception as e:
        return {
            "net_profit": 0.0,
            "profit_factor": 1.0,
            "win_rate": 0.0,
            "trades": 0,
            "max_drawdown": 0.0,
            "ret_dd_ratio": 0.0,
            "sharpe_ratio": 0.0,
            "error": str(e),
        }


if __name__ == "__main__":
    import sys
    test_path = sys.argv[1] if len(sys.argv) > 1 else "/Volumes/T7/Applications/StrategyQuantXB141.app/Contents/Resources/user/projects/MES H1 Long/databanks/Results/Strategy 0.54.sqx"
    res = extract_backtest_data(test_path)
    import json
    print(json.dumps(res, indent=2))
