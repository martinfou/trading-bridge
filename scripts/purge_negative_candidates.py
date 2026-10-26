#!/usr/bin/env python3
"""
Purge Negative Net Profit Strategies from SQX Results Databanks
---------------------------------------------------------------
Scans databanks/Results across all projects and moves any strategy with
net_profit <= 0 into a databanks/Quarantined_Negatives directory.
"""

import shutil
from pathlib import Path
from scripts.extract_sqx_backtest import extract_backtest_data

SQ_HOME = Path("/Volumes/T7/Applications/StrategyQuantXB141.app/Contents/Resources")
PROJECTS_DIR = SQ_HOME / "user/projects"


def purge_negatives():
    total_quarantined = 0
    total_kept = 0

    for proj in sorted(PROJECTS_DIR.iterdir()):
        if not proj.is_dir():
            continue
        results_dir = proj / "databanks/Results"
        if not results_dir.exists():
            continue

        quarantine_dir = proj / "databanks/Quarantined_Negatives"
        files = list(results_dir.glob("*.sqx"))
        if not files:
            continue

        print(f"\nScanning {proj.name} ({len(files)} strategies)...")
        proj_quarantined = 0

        for f in files:
            try:
                data = extract_backtest_data(f)
                net_profit = data.get("net_profit", 0.0)
                if net_profit <= 0:
                    quarantine_dir.mkdir(parents=True, exist_ok=True)
                    target = quarantine_dir / f.name
                    shutil.move(f, target)
                    proj_quarantined += 1
                    total_quarantined += 1
                    print(f"  [QUARANTINED] {f.name} -> Net Profit: ${net_profit:.2f}, IS: ${data.get('is_profit', 0):.2f}, OOS: ${data.get('oos_profit', 0):.2f}")
                else:
                    total_kept += 1
            except Exception as e:
                print(f"  [ERROR] Failed to inspect {f.name}: {e}")

        print(f"  Result: {proj_quarantined} quarantined, {len(files) - proj_quarantined} kept.")

    print(f"\n==========================================")
    print(f"SUMMARY: {total_quarantined} strategies quarantined, {total_kept} profitable strategies kept.")
    print(f"==========================================")


if __name__ == "__main__":
    purge_negatives()
