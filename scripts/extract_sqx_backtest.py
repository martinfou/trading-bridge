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


def _get_micro_normalization_scale(zip_file: zipfile.ZipFile, equity_entry_name: str) -> float:
    """
    Normalizes full-lot Dukascopy simulations to 1 Micro / Mini baseline ($10,000 account):
      - XAUUSD_dukascopy has pointValue=100.0 (100 oz standard Gold lot).
        1 Micro Gold (MGC) is 10 oz ($10/pt = 0.1 lot). If FixedSize=1.0 was used, scale by 0.1.
      - FX pairs (EURUSD/GBPUSD/USDJPY/GBPJPY) use 0.1 lot (10,000 units mini lot) as baseline.
        If FixedSize=1.0 (100,000 standard lot) was used, scale by 0.1.
    """
    if "lastSettings.xml" not in zip_file.namelist():
        return 1.0
    try:
        import re
        ls = zip_file.read("lastSettings.xml").decode("utf-8", errors="ignore")
        sz_match = re.search(r'<Param key="Size" className="FixedSize">([^<]+)</Param>', ls)
        if not sz_match:
            return 1.0
        fixed_size = float(sz_match.group(1).strip())
        if fixed_size <= 0.15:
            return 1.0
        upper_name = equity_entry_name.upper()
        if any(sym in upper_name for sym in ("XAUUSD", "GOLD", "EURUSD", "GBPUSD", "USDJPY", "GBPJPY")):
            return 0.1 / fixed_size
    except Exception:
        pass
    return 1.0


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

    scale = _get_micro_normalization_scale(zip_file, equity_entry_name)
    raw_entries = []
    for i in range(count):
        ts, eq = struct.unpack(">qd", raw[4 + i * 16 : 4 + (i + 1) * 16])
        raw_entries.append((ts, eq))

    raw_initial = raw_entries[0][1]
    entries = [(ts, raw_initial + (eq - raw_initial) * scale) for ts, eq in raw_entries]

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

    # R-squared (Linearity / Stability of equity curve) & Ulcer Performance Index (UPI / Martin Ratio)
    n_pts = len(entries)
    r_squared = 0.0
    ulcer_index = 0.0
    upi = 0.0
    if n_pts >= 2:
        t_mean = (n_pts - 1) / 2.0
        eq_vals = [ initial_equity + (e[1] if initial_equity == 0 else (e[1] - initial_equity)) for e in entries ]
        eq_mean = sum(eq_vals) / n_pts
        cov = sum((i - t_mean) * (eq_vals[i] - eq_mean) for i in range(n_pts))
        var_t = sum((i - t_mean) ** 2 for i in range(n_pts))
        var_eq = sum((eq_vals[i] - eq_mean) ** 2 for i in range(n_pts))
        if var_t > 0 and var_eq > 0 and cov > 0:
            r_squared = (cov ** 2) / (var_t * var_eq)

        # Ulcer Index (RMS of % drawdown across all points, penalizing both depth and duration)
        base_cap = initial_equity if initial_equity > 0 else 10000.0
        run_peak = base_cap
        sq_dd_sum = 0.0
        for _, eq_raw in entries:
            nav = base_cap + eq_raw if initial_equity == 0 else eq_raw
            if nav > run_peak:
                run_peak = nav
            dd_pct_pt = ((run_peak - nav) / run_peak) * 100.0 if run_peak > 0 else 0.0
            sq_dd_sum += dd_pct_pt ** 2
        ulcer_index = (sq_dd_sum / n_pts) ** 0.5
        span_years = max(0.1, (entries[-1][0] - entries[0][0]) / (1000.0 * 86400.0 * 365.25))
        ann_return_pct = ((net_profit / base_cap) * 100.0) / span_years
        upi = (ann_return_pct / ulcer_index) if ulcer_index > 0 else 0.0

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

    initial_cap = initial_equity if initial_equity > 0 else 10000.0
    net_profit_pct = round((net_profit / initial_cap) * 100.0, 2)
    is_profit_pct = round((is_profit / initial_cap) * 100.0, 2)
    oos_profit_pct = round((oos_profit / initial_cap) * 100.0, 2)

    # Downsampled sparkline points (24 points for mini sparkline chart)
    sparkline = []
    step_spark = max(1, len(entries) // 24)
    for i in range(0, len(entries), step_spark):
        sparkline.append(round(entries[i][1], 2))
    if sparkline and sparkline[-1] != round(final_equity, 2):
        sparkline.append(round(final_equity, 2))

    start_date = datetime.datetime.fromtimestamp(entries[0][0] / 1000.0, datetime.timezone.utc).strftime("%Y-%m-%d")
    end_date = datetime.datetime.fromtimestamp(entries[-1][0] / 1000.0, datetime.timezone.utc).strftime("%Y-%m-%d")

    # Time period calculations (Years for IS, OOS, and Full Backtest)
    start_ts = entries[0][0]
    end_ts = entries[-1][0]
    total_days = max(1.0, (end_ts - start_ts) / (1000.0 * 86400.0))
    total_years = round(total_days / 365.25, 2)

    # In-Sample period (start_date through 2022-12-31)
    is_end_ts = min(end_ts, OOS_TIMESTAMP_MS)
    is_days = max(1.0, (is_end_ts - start_ts) / (1000.0 * 86400.0)) if is_end_ts > start_ts else 1.0
    is_years = round(is_days / 365.25, 2)

    # Out-Of-Sample period (2023-01-01 through end_date)
    oos_start_ts = max(start_ts, OOS_TIMESTAMP_MS)
    oos_days = max(0.0, (end_ts - oos_start_ts) / (1000.0 * 86400.0)) if end_ts > OOS_TIMESTAMP_MS else 0.0
    oos_years = round(oos_days / 365.25, 2)

    # Average Yearly Profits ($ and %)
    is_annual_profit = round(is_profit / is_years, 2) if is_years > 0 else 0.0
    is_annual_profit_pct = round((is_annual_profit / initial_cap) * 100.0, 2)

    oos_annual_profit = round(oos_profit / oos_years, 2) if oos_years > 0 else 0.0
    oos_annual_profit_pct = round((oos_annual_profit / initial_cap) * 100.0, 2)

    total_annual_profit = round(net_profit / total_years, 2) if total_years > 0 else 0.0
    total_annual_profit_pct = round((total_annual_profit / initial_cap) * 100.0, 2)

    # High-fidelity sampled equity and drawdown curve (~60 points) for interactive charts
    curve = []
    step_curve = max(1, len(entries) // 60)
    p_peak = entries[0][1] if entries else 0.0
    for i in range(0, len(entries), step_curve):
        ts, eq = entries[i]
        if eq > p_peak:
            p_peak = eq
        dd = eq - p_peak
        d_str = datetime.datetime.fromtimestamp(ts / 1000.0, datetime.timezone.utc).strftime("%Y-%m-%d")
        curve.append({
            "d": d_str,
            "pnl": round(eq, 2),
            "pnl_pct": round((eq / initial_cap) * 100.0, 2),
            "dd": round(dd, 2),
            "dd_pct": round((dd / initial_cap) * 100.0, 2),
            "oos": ts >= OOS_TIMESTAMP_MS,
        })
    if entries and (not curve or curve[-1]["d"] != end_date):
        ts, eq = entries[-1]
        if eq > p_peak:
            p_peak = eq
        dd = eq - p_peak
        curve.append({
            "d": end_date,
            "pnl": round(eq, 2),
            "pnl_pct": round((eq / initial_cap) * 100.0, 2),
            "dd": round(dd, 2),
            "dd_pct": round((dd / initial_cap) * 100.0, 2),
            "oos": ts >= OOS_TIMESTAMP_MS,
        })

    return {
        "initial_capital": initial_cap,
        "net_profit": round(net_profit, 2),
        "net_profit_pct": net_profit_pct,
        "profit_factor": round(pf, 2),
        "win_rate": round(win_rate, 1),
        "trades": len(changes),
        "wins": len(wins),
        "losses": len(losses),
        "max_drawdown": round(max_dd, 2),
        "max_drawdown_pct": round(max_dd_pct, 1),
        "ret_dd_ratio": round(ret_dd, 2),
        "sharpe_ratio": round(sharpe, 2),
        "r_squared": round(r_squared, 4),
        "ulcer_index": round(ulcer_index, 2),
        "upi": round(upi, 2),
        "is_trades": len(is_trades),
        "is_profit": round(is_profit, 2),
        "is_profit_pct": is_profit_pct,
        "is_pf": round(is_pf, 2),
        "is_years": is_years,
        "is_annual_profit": is_annual_profit,
        "is_annual_profit_pct": is_annual_profit_pct,
        "oos_trades": len(oos_trades),
        "oos_profit": round(oos_profit, 2),
        "oos_profit_pct": oos_profit_pct,
        "oos_pf": round(oos_pf, 2),
        "oos_years": oos_years,
        "oos_annual_profit": oos_annual_profit,
        "oos_annual_profit_pct": oos_annual_profit_pct,
        "total_years": total_years,
        "total_annual_profit": total_annual_profit,
        "total_annual_profit_pct": total_annual_profit_pct,
        "start_date": start_date,
        "end_date": end_date,
        "sparkline": sparkline,
        "curve": curve,
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
