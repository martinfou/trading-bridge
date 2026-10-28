#!/usr/bin/env python3
"""
SQX Control CLI Utility
-----------------------
Quick command-line interface to manage StrategyQuant X projects,
databanks, and find top profitable strategies.

Usage:
    python3 scripts/sqx_ctrl.py list
    python3 scripts/sqx_ctrl.py start "MES H1 Long"
    python3 scripts/sqx_ctrl.py stop "MES H1 Long"
    python3 scripts/sqx_ctrl.py status
    python3 scripts/sqx_ctrl.py top [--project PROJ] [--limit 10] [--min-oos 0]
"""

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

# Paths
SQ_HOME = Path("/Volumes/T7/Applications/StrategyQuantXB141.app/Contents/Resources")
SQCLI = SQ_HOME / "sqcli"
SCRIPTS_DIR = Path(__file__).resolve().parent

if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

try:
    from extract_sqx_backtest import extract_backtest_data
except ImportError:
    from scripts.extract_sqx_backtest import extract_backtest_data


def run_sqcli(*args):
    """Executes a command via sqcli."""
    if not SQCLI.exists():
        print(f"Error: sqcli not found at {SQCLI}")
        return False
    cmd = [str(SQCLI)] + list(args)
    print(f"Executing: {' '.join(cmd)}")
    res = subprocess.run(cmd)
    return res.returncode == 0


def cmd_list(args):
    """Lists all available projects."""
    projects_dir = SQ_HOME / "user/projects"
    if not projects_dir.exists():
        print(f"Projects directory not found: {projects_dir}")
        return
    print("\n--- INSTALLED STRATEGYQUANT X PROJECTS ---")
    for p in sorted(projects_dir.iterdir()):
        if p.is_dir() and not p.name.startswith("."):
            cfx = list(p.glob("*.cfx"))
            results_dir = p / "databanks/Results"
            sqx_count = len(list(results_dir.glob("*.sqx"))) if results_dir.exists() else 0
            print(f" • {p.name:<35} | Results: {sqx_count:4d} strategies | Config: {'OK' if cfx else 'No .cfx'}")
    print()


def cmd_status(args):
    """Checks process and engine status."""
    out = subprocess.check_output(["ps", "-A", "-o", "pid,%cpu,%mem,time,command"], universal_newlines=True)
    found = False
    for line in out.splitlines():
        if "sqcli" in line and "action=start" in line and "grep" not in line and "sqx_ctrl" not in line:
            parts = line.strip().split()
            if len(parts) >= 5:
                pid = parts[0]
                cpu = parts[1]
                mem = parts[2]
                elapsed = parts[3]
                cmd = " ".join(parts[4:])
                proj = "Unknown"
                if "name=" in cmd:
                    proj = cmd.split("name=")[1].strip().strip('"\'')
                print(f"✓ StrategyQuant X Engine is RUNNING")
                print(f"  Project:    {proj}")
                print(f"  PID:        {pid}")
                print(f"  CPU Usage:  {cpu}%")
                print(f"  Memory:     {mem}%")
                print(f"  Time:       {elapsed}")
                found = True
    if not found:
        print("StrategyQuant X engine is currently IDLE (no sqcli generation process running).")


def cmd_start(args):
    """Starts a project in background."""
    proj = args.project
    out = subprocess.check_output(["ps", "-A", "-o", "pid,command"], universal_newlines=True)
    for line in out.splitlines():
        if "sqcli" in line and "action=start" in line and "grep" not in line and "sqx_ctrl" not in line:
            print(f"Warning: A generation process is already running:")
            print(f"  {line.strip()}")
            ans = input("Stop existing process first? (y/n): ") if sys.stdin.isatty() else "y"
            if ans.strip().lower() == "y":
                pid = int(line.strip().split()[0])
                print(f"Terminating PID {pid}...")
                try:
                    os.kill(pid, 15)
                    time.sleep(2)
                except Exception as e:
                    print(f"Error killing PID {pid}: {e}")
            else:
                return

    print(f"Starting headless SQX generation for: {proj}")
    log_dir = SQ_HOME / "user/log"
    log_dir.mkdir(parents=True, exist_ok=True)
    safe_slug = proj.replace(" ", "_").replace("/", "_")
    log_file = log_dir / f"sqcli_generation_{safe_slug}.log"
    f = open(log_file, "a")

    proc = subprocess.Popen(
        [str(SQCLI), "-project", "action=start", f"name={proj}"],
        stdout=f,
        stderr=subprocess.STDOUT,
        start_new_session=True
    )
    time.sleep(1.5)
    if proc.poll() is None:
        print(f"✓ Started SQX generation engine in background (PID: {proc.pid})")
        print(f"  Project: {proj}")
        print(f"  Log file: {log_file}")
    else:
        print(f"Engine process terminated immediately with return code {proc.returncode}.")
        print(f"Check logs at: {log_file}")


def cmd_stop(args):
    """Stops a project."""
    proj = args.project
    print(f"Stopping project generation for: {proj}")
    # Attempt graceful stop via sqcli
    subprocess.run([str(SQCLI), "-project", "action=stop", f"name={proj}"])
    time.sleep(1)
    # Check for lingering process
    out = subprocess.check_output(["ps", "-A", "-o", "pid,command"], universal_newlines=True)
    for line in out.splitlines():
        if "sqcli" in line and "action=start" in line and proj in line:
            pid = int(line.strip().split()[0])
            print(f"Terminating lingering process PID: {pid}")
            try:
                os.kill(pid, 15)
            except Exception:
                pass
    print(f"✓ Stopped generation for {proj}")


def cmd_top(args):
    """Finds and ranks top profitable strategies across databanks."""
    projects_dir = SQ_HOME / "user/projects"
    target_dir = projects_dir / args.project if args.project else projects_dir

    print(f"Scanning for profitable strategies in: {target_dir.name}...")
    candidates = []

    for p in target_dir.glob("**/*.sqx"):
        try:
            bt = extract_backtest_data(p)
            net_profit = bt.get("net_profit", 0)
            oos_profit = bt.get("oos_profit", 0)
            trades = bt.get("trades", 0)
            pf = bt.get("profit_factor", 0)
            oos_pf = bt.get("oos_pf", 0)
            dd = bt.get("max_drawdown", 0)
            ret_dd = bt.get("ret_dd_ratio", 0)
            win_rate = bt.get("win_rate", 0)

            if net_profit > 0 and trades >= args.min_trades and oos_profit >= args.min_oos:
                rel = str(p.relative_to(projects_dir))
                candidates.append({
                    "name": p.stem,
                    "project": rel.split("/")[0],
                    "databank": rel.split("/")[2] if len(rel.split("/")) > 2 else "root",
                    "net_profit": net_profit,
                    "is_profit": bt.get("is_profit", 0),
                    "oos_profit": oos_profit,
                    "pf": pf,
                    "oos_pf": oos_pf,
                    "dd": dd,
                    "ret_dd": ret_dd,
                    "trades": trades,
                    "win_rate": win_rate,
                    "rules": bt.get("rules_summary", "")
                })
        except Exception:
            continue

    # Sort by Ret/DD and OOS Profit
    candidates.sort(key=lambda x: (x["oos_profit"] > 0, x["ret_dd"], x["oos_profit"]), reverse=True)

    print(f"\nFound {len(candidates)} profitable strategies matching filters.")
    print(f"{'#':<3} | {'Project / Databank':<32} | {'Strategy Name':<25} | {'Total PnL':<9} | {'IS PnL':<9} | {'OOS PnL':<9} | {'PF':<5} | {'OOS PF':<6} | {'Max DD':<8} | {'Ret/DD':<7} | {'Trades':<6}")
    print("-" * 140)
    for idx, c in enumerate(candidates[:args.limit], 1):
        loc = f"{c['project'][:18]}/{c['databank'][:11]}"
        print(f"{idx:<3} | {loc:<32} | {c['name'][:25]:<25} | ${c['net_profit']:<8,.0f} | ${c['is_profit']:<8,.0f} | ${c['oos_profit']:<8,.0f} | {c['pf']:<5.2f} | {c['oos_pf']:<6.2f} | ${c['dd']:<7,.0f} | {c['ret_dd']:<7.2f} | {c['trades']:<6}")
        if args.verbose:
            print(f"     Rules: {c['rules']}")
            print()


def main():
    parser = argparse.ArgumentParser(description="StrategyQuant X Control Tool")
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("list", help="List installed SQX projects")
    subparsers.add_parser("status", help="Check SQX process status")

    start_p = subparsers.add_parser("start", help="Start generating a project")
    start_p.add_argument("project", help="Project name (e.g. 'MES H1 Long')")

    stop_p = subparsers.add_parser("stop", help="Stop generating a project")
    stop_p.add_argument("project", help="Project name (e.g. 'MES H1 Long')")

    top_p = subparsers.add_parser("top", help="Find and rank top profitable strategies")
    top_p.add_argument("--project", default=None, help="Filter by specific project name")
    top_p.add_argument("--limit", type=int, default=15, help="Number of strategies to display")
    top_p.add_argument("--min-oos", type=float, default=0.0, help="Minimum OOS net profit")
    top_p.add_argument("--min-trades", type=int, default=30, help="Minimum trade count")
    top_p.add_argument("-v", "--verbose", action="store_true", help="Print rules and indicators")

    args = parser.parse_args()

    if args.command == "list":
        cmd_list(args)
    elif args.command == "status":
        cmd_status(args)
    elif args.command == "start":
        cmd_start(args)
    elif args.command == "stop":
        cmd_stop(args)
    elif args.command == "top":
        cmd_top(args)


if __name__ == "__main__":
    main()
