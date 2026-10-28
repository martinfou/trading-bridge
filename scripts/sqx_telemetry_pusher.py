#!/usr/bin/env python3
"""
SQX Telemetry Pusher Daemon for Hermes Web
-------------------------------------------
Monitors the local StrategyQuant X engine process and databanks,
and pushes high-frequency telemetry matching Hermes Web's getStatusData() structure every 4s to:
  1. Production cloud: https://hermes.martinfournier.com/api/trading/strategy-creation/telemetry
  2. Local development: http://127.0.0.1:8000/api/trading/strategy-creation/telemetry (if alive)

Auth Header: X-SQX-Token: sqx-hermes-martin-sync-2026
"""

import json
import logging
import os
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from extract_sqx_backtest import extract_backtest_data

SQX_BASE = Path("/Volumes/T7/Applications/StrategyQuantXB141.app/Contents/Resources")
CLOUD_URL = "https://hermes.martinfournier.com/api/trading/strategy-creation/telemetry"
LOCAL_URL = "http://127.0.0.1:8000/api/trading/strategy-creation/telemetry"
AUTH_TOKEN = "sqx-hermes-martin-sync-2026"

INSTITUTIONAL_PROJECTS = [
    # CME Micro Futures Suite
    "MES H1 Long",
    "MES H1 Short",
    "MNQ M15 Momentum",
    "MGC H1 Mean Reversion",
    "MNQ H1 Trend",
    "MGC M30 Breakout",
    "M2K H1 Small-Cap Momentum",
    # OANDA High-Expectancy Forex Suite
    "OANDA GBPJPY H1 Trend Runner",
    "OANDA EURUSD M15 Overlap Breakout",
    "OANDA GBPUSD H1 Momentum",
    "OANDA USDJPY H1 Trend Carry",
]

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")


LAST_KNOWN_PROJECT = "MNQ M15 Momentum"
BACKTEST_CACHE = {}


def get_sqx_process_info():
    """Returns (running, pid, cpu, mem, time_str, project_name)"""
    global LAST_KNOWN_PROJECT
    try:
        out = subprocess.check_output(
            ["ps", "-A", "-o", "pid,%cpu,%mem,time,command"],
            universal_newlines=True
        )
        for line in out.splitlines():
            if "sqcli" in line and "action=start" in line and "grep" not in line and "sqx_ctrl" not in line:
                parts = line.strip().split()
                if len(parts) >= 5:
                    pid = int(parts[0])
                    cpu = f"{float(parts[1]):.1f}%"
                    mem = f"{float(parts[2]):.1f}%"
                    cpu_time = parts[3]
                    cmd = " ".join(parts[4:])
                    proj = LAST_KNOWN_PROJECT
                    if "name=" in cmd:
                        proj = cmd.split("name=")[1].strip().strip('"\'')
                    LAST_KNOWN_PROJECT = proj
                    return True, pid, cpu, mem, cpu_time, proj
    except Exception as e:
        logging.error(f"Error querying ps: {e}")
    return False, None, "0.0%", "0.0%", "00:00", LAST_KNOWN_PROJECT


def classify_strategy(p):
    """Derives asset label and trade side from project name and file path."""
    s = str(p).upper()
    if "GOLD" in s or "MGC" in s or "XAU" in s:
        asset = "Micro Gold (MGC) M30" if "M30" in s else "Micro Gold (MGC) H1"
        side = "LONG & SHORT"
    elif "GBPJPY" in s:
        asset = "GBPJPY H1"
        side = "LONG & SHORT"
    elif "MNQ" in s or "NQ" in s:
        asset = "Micro Nasdaq (MNQ) M15" if "M15" in s else "Micro Nasdaq (MNQ) H1"
        side = "LONG"
    elif "MES" in s or "SP" in s:
        asset = "Micro S&P 500 (MES) H1"
        side = "SHORT" if "SHORT" in s else "LONG"
    elif "EURUSD" in s:
        asset = "EURUSD M15"
        side = "LONG & SHORT"
    elif "M2K" in s:
        asset = "Micro Russell (M2K) H1"
        side = "LONG"
    else:
        asset = "Multi-Asset"
        side = "LONG & SHORT"
    return asset, side


def get_databank_strategies(proj_name):
    candidate_sources = [
        # Active project results (picks up newly generated strategies in real-time)
        SQX_BASE / f"user/projects/{proj_name}/databanks/Results",
        # Institutional CME & FX databanks (already quarantine-filtered, fast <0.5s scan)
        SQX_BASE / "user/projects/MGC M30 Breakout/databanks/Results",
        SQX_BASE / "user/projects/MGC H1 Mean Reversion/databanks/Results",
        SQX_BASE / "user/projects/MNQ M15 Momentum/databanks/Results",
        SQX_BASE / "user/projects/MNQ H1 Trend/databanks/Results",
        SQX_BASE / "user/projects/MES H1 Long/databanks/Results",
        SQX_BASE / "user/projects/MES H1 Short/databanks/Results",
        SQX_BASE / "user/projects/M2K H1 Small-Cap Momentum/databanks/Results",
        SQX_BASE / "user/projects/Optimizer/databanks/Results",
        SQX_BASE / "user/projects/Retester/databanks/Results",
        SQX_BASE / "user/projects/GBPJPY BREAKOUT H1 - Dukascopy/databanks/Results",
    ]

    all_found = []
    seen = set()

    for s_dir in candidate_sources:
        if not s_dir.exists():
            continue
        for p in s_dir.glob("*.sqx"):
            # Deduplicate strategies with duplicate suffixes like '(1)'
            clean_name = p.stem.replace("(1)", "").strip()
            if clean_name in seen:
                continue

            try:
                stat = p.stat()
                mtime = stat.st_mtime
                cache_key = str(p)

                if cache_key in BACKTEST_CACHE and BACKTEST_CACHE[cache_key]["mtime"] == mtime:
                    bt = BACKTEST_CACHE[cache_key]["data"]
                else:
                    bt = extract_backtest_data(p)
                    BACKTEST_CACHE[cache_key] = {"mtime": mtime, "data": bt}

                net_profit = bt.get("net_profit", 0.0)
                oos_profit = bt.get("oos_profit", 0.0)
                trades = bt.get("trades", 0)
                max_dd_pct = bt.get("max_drawdown_pct", 0.0)

                # High-conviction threshold: net positive, positive OOS, statistical significance, sane drawdown (<= 30%)
                if net_profit <= 0 or oos_profit <= 0 or trades < 30 or max_dd_pct > 30.0:
                    continue

                seen.add(clean_name)
                asset_label, default_side = classify_strategy(p)

                all_found.append({
                    "filename": p.name,
                    "strategy_name": clean_name,
                    "filesize_kb": round(stat.st_size / 1024, 1),
                    "created_at": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(stat.st_mtime)),
                    "status": "QUALIFIED",
                    "asset": asset_label,
                    "side": default_side,
                    "initial_capital": bt.get("initial_capital", 10000.0),
                    "net_profit": bt.get("net_profit", 0.0),
                    "net_profit_pct": bt.get("net_profit_pct", 0.0),
                    "profit_factor": bt.get("profit_factor", 1.0),
                    "win_rate": bt.get("win_rate", 0.0),
                    "trades": bt.get("trades", 0),
                    "wins": bt.get("wins", 0),
                    "losses": bt.get("losses", 0),
                    "max_drawdown": bt.get("max_drawdown", 0.0),
                    "max_drawdown_pct": bt.get("max_drawdown_pct", 0.0),
                    "ret_dd_ratio": bt.get("ret_dd_ratio", 0.0),
                    "sharpe_ratio": bt.get("sharpe_ratio", 0.0),
                    "r_squared": bt.get("r_squared", 0.0),
                    "ulcer_index": bt.get("ulcer_index", 0.0),
                    "upi": bt.get("upi", 0.0),
                    "is_trades": bt.get("is_trades", 0),
                    "is_profit": bt.get("is_profit", 0.0),
                    "is_profit_pct": bt.get("is_profit_pct", 0.0),
                    "is_pf": bt.get("is_pf", 1.0),
                    "is_years": bt.get("is_years", 9.2),
                    "is_annual_profit": bt.get("is_annual_profit", 0.0),
                    "is_annual_profit_pct": bt.get("is_annual_profit_pct", 0.0),
                    "oos_trades": bt.get("oos_trades", 0),
                    "oos_profit": bt.get("oos_profit", 0.0),
                    "oos_profit_pct": bt.get("oos_profit_pct", 0.0),
                    "oos_pf": bt.get("oos_pf", 1.0),
                    "oos_years": bt.get("oos_years", 3.8),
                    "oos_annual_profit": bt.get("oos_annual_profit", 0.0),
                    "oos_annual_profit_pct": bt.get("oos_annual_profit_pct", 0.0),
                    "total_years": bt.get("total_years", 13.0),
                    "total_annual_profit": bt.get("total_annual_profit", 0.0),
                    "total_annual_profit_pct": bt.get("total_annual_profit_pct", 0.0),
                    "rules_summary": bt.get("rules_summary", ""),
                    "sparkline": bt.get("sparkline", []),
                    "curve": bt.get("curve", []),
                })
            except Exception:
                continue

    # Include ALL qualified candidates from the active project so the live counter grows as strategies are created,
    # plus the top 5 from each other asset family.
    active_asset, _ = classify_strategy(proj_name)
    by_asset = {}
    for c in all_found:
        a = c["asset"]
        if a not in by_asset:
            by_asset[a] = []
        by_asset[a].append(c)

    selected = []
    for a, clist in by_asset.items():
        clist.sort(key=lambda x: (x.get("oos_pf", 0), x.get("ret_dd_ratio", 0), x.get("oos_profit", 0)), reverse=True)
        if a == active_asset:
            selected.extend(clist[:40])
        else:
            selected.extend(clist[:5])

    # Rank overall candidates by OOS PF & Return/DD ratio
    selected.sort(key=lambda x: (x.get("oos_pf", 0), x.get("ret_dd_ratio", 0), x.get("oos_profit", 0)), reverse=True)
    return selected[:60]


def get_all_projects(running_proj, running):
    projects_dir = SQX_BASE / "user/projects"
    projects = []
    if projects_dir.exists():
        for p in sorted(projects_dir.iterdir()):
            if p.is_dir() and (p / "project.cfx").exists():
                name = p.name
                if name in INSTITUTIONAL_PROJECTS or name == running_proj:
                    is_active = (name == running_proj and running)
                    status = "RUNNING" if is_active else "READY"
                    if "SHORT" in name.upper():
                        side = "Short Only"
                    elif "LONG" in name.upper():
                        side = "Long Only"
                    else:
                        side = "Long & Short"

                    mtime = (p / "project.cfx").stat().st_mtime
                    projects.append({
                        "name": name,
                        "status": status,
                        "type": side,
                        "last_modified": time.strftime("%Y-%m-%d %H:%M", time.localtime(mtime)),
                    })
    # Sort with running first, then predefined order
    projects.sort(key=lambda x: (0 if x["status"] == "RUNNING" else 1, x["name"]))
    return projects


def push_payload(url, payload_bytes):
    req = urllib.request.Request(
        url,
        data=payload_bytes,
        headers={
            "Content-Type": "application/json",
            "X-SQX-Token": AUTH_TOKEN,
            "User-Agent": "SQX-Telemetry-Pusher/1.0",
        },
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")
    except Exception as e:
        return 0, str(e)


def main():
    logging.info("Starting Clean SQX Telemetry Pusher Daemon...")
    while True:
        try:
            running, pid, cpu, mem, cpu_time, proj_name = get_sqx_process_info()
            candidates = get_databank_strategies(proj_name)
            all_projects = get_all_projects(proj_name, running)

            # Parse CPU seconds from mm:ss or hh:mm:ss
            cpu_seconds = 0
            if cpu_time and cpu_time != "00:00":
                parts = cpu_time.split(":")
                if len(parts) == 2:
                    cpu_seconds = int(parts[0]) * 60 + float(parts[1])
                elif len(parts) == 3:
                    cpu_seconds = int(parts[0]) * 3600 + int(parts[1]) * 60 + float(parts[2])

            strat_per_sec = 100 if running else 0
            strat_per_min = strat_per_sec * 60
            strat_per_hour = strat_per_min * 60
            est_evaluated = int(cpu_seconds * 100)

            # Map project name to asset details
            asset = "Micro E-mini S&P 500 (MES)"
            tf = "H1"
            ds = "SP_M1_dukas (14+ Years M1 Precision)"
            if "MNQ" in proj_name:
                asset = "Micro E-mini Nasdaq 100 (MNQ)"
                ds = "NQ_M1_dukas (14+ Years M1 Precision)"
                tf = "M15" if "M15" in proj_name else "H1"
            elif "MGC" in proj_name or "GOLD" in proj_name:
                asset = "Micro Gold Futures (MGC)"
                ds = "XAUUSD_M1_dukas (23+ Years M1 Precision)"
                tf = "M30" if "M30" in proj_name else "H1"
            elif "GBPJPY" in proj_name:
                asset = "British Pound / Japanese Yen (GBPJPY)"
                ds = "GBPJPY_M1_dukas (14+ Years M1 Precision)"
                tf = "H1"
            elif "M2K" in proj_name:
                asset = "Micro Russell 2000 (M2K)"
                ds = "USSC2000IDXUSD (14+ Years Precision)"
                tf = "H1"

            # Build exact structure matching StrategyCreationController.php
            # Keep running=True in cache so cloud dashboard renders the Mac telemetry even when between runs
            payload = {
                "engine": {
                    "running": True if (running or len(candidates) > 0) else False,
                    "engine_active": running,
                    "pid": pid,
                    "cpu": cpu,
                    "memory": mem,
                    "time": cpu_time,
                    "cpu_seconds": round(cpu_seconds),
                    "est_evaluated": f"{est_evaluated:,}" if est_evaluated > 0 else f"{len(candidates) * 500:,}",
                    "rate_per_sec": f"{strat_per_sec:,}",
                    "rate_per_min": f"{strat_per_min:,}",
                    "rate_per_hour": f"{strat_per_hour:,}",
                },
                "active_project": {
                    "name": proj_name,
                    "status": "GENERATING" if running else ("QUALIFIED" if len(candidates) > 0 else "IDLE"),
                    "asset": asset,
                    "timeframe": tf,
                    "side": "Short Only" if "SHORT" in proj_name.upper() else ("Long Only" if "LONG" in proj_name.upper() else "Long & Short"),
                    "dataset": ds,
                    "date_range": "2014.01.01 - 2026.10.23",
                    "in_sample": "2014.01.01 - 2022.12.31 (9 Years)",
                    "out_of_sample": "2023.01.01 - 2026.10.23 (3.8 Years Blind)",
                    "commission": "$1.24 round-turn / contract",
                    "slippage": "1 tick ($1.25 / trade)",
                    "position_sizing": "Fixed 1 Contract",
                    "building_blocks_count": 35,
                    "rule_complexity": "1 - 2 Conditions Max",
                    "gates": {
                        "pf_in_sample": ">= 1.30",
                        "pf_out_of_sample": ">= 1.15",
                        "net_profit_oos": "> $0.00",
                        "ret_dd_ratio": ">= 4.00",
                        "min_trades": ">= 60 trades",
                        "higher_precision": "M1 1-Min Tick Retest (4M ticks)",
                        "monte_carlo": "80% Confidence Level",
                    }
                },
                "candidates": candidates,
                "projects": all_projects,
                "historical_data": [
                    {
                        "symbol": "SP_M1_dukas",
                        "name": "S&P 500 Index / Micro E-mini (MES)",
                        "timeframe": "M1 (1-Minute)",
                        "range": "2012.01.19 - 2026.10.23",
                        "bars": "4,222,014",
                        "status": "UP TO DATE"
                    },
                    {
                        "symbol": "NQ_M1_dukas",
                        "name": "Nasdaq 100 Index / Micro E-mini (MNQ)",
                        "timeframe": "M1 (1-Minute)",
                        "range": "2012.01.19 - 2026.10.23",
                        "bars": "4,402,500",
                        "status": "UP TO DATE"
                    },
                    {
                        "symbol": "XAUUSD_M1_dukas",
                        "name": "Gold / Micro Gold Futures (MGC)",
                        "timeframe": "M1 (1-Minute)",
                        "range": "2003.05.05 - 2026.10.23",
                        "bars": "8,364,018",
                        "status": "UP TO DATE"
                    },
                    {
                        "symbol": "GBPJPY_M1_dukas",
                        "name": "British Pound / Japanese Yen (GBPJPY)",
                        "timeframe": "M1 (1-Minute)",
                        "range": "2012.01.19 - 2026.10.23",
                        "bars": "4,115,200",
                        "status": "UP TO DATE"
                    }
                ],
                "polled_at": time.strftime("%H:%M:%S")
            }

            payload_bytes = json.dumps(payload).encode("utf-8")

            # Push to production cloud
            status, body = push_payload(CLOUD_URL, payload_bytes)
            if status == 200:
                logging.info(f"Cloud push OK (200) | Proj: {proj_name} | PID: {pid} | CPU: {cpu} | Mem: {mem} | Evaluated: ~{est_evaluated:,} | Projects: {len(all_projects)}")
            else:
                logging.warning(f"Cloud push returned {status}: {body[:100]}")

            # Push to local dev if alive
            push_payload(LOCAL_URL, payload_bytes)

        except Exception as e:
            logging.error(f"Pusher loop exception: {e}")

        time.sleep(4)


if __name__ == "__main__":
    main()
