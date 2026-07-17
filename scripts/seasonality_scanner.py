#!/usr/bin/env python3
"""
Seasonality Scanner — Multi-asset forex seasonality detector
Analyzes 20+ years of H1 data for profitable seasonal patterns.
"""

import csv
import os
import math
import sys
from collections import defaultdict
from datetime import datetime, timezone, timedelta
from typing import Dict, List, Tuple

DATA_DIR = os.path.expanduser("~/projects/trading-bridge/data/historical/dukascopy")

# Pairs to analyze — we only have per-year files for these 10
ALL_PAIRS = [
    "audusd", "eurusd", "gbpusd", "nzdusd", "usdcad", "usdchf", "usdjpy",
    "gbpjpy", "xauusd"
]

# Those with full 2006-2026 coverage
FULL_COVERAGE = ["audusd", "eurusd", "gbpusd", "nzdusd", "usdcad", "usdchf", "usdjpy", "xauusd"]
# gbpjpy has its own combined file

DAILY_BARS = 24  # H1 data: 24 bars per day
HOURS_PER_BAR = 1

def load_pair(pair: str) -> List[dict]:
    """Load all H1 CSV files for a pair, return sorted bar dicts."""
    bars = []
    
    # Try combined file first
    combined = os.path.join(DATA_DIR, f"{pair}-h1-bid-2006-2026.csv")
    if os.path.exists(combined):
        with open(combined) as f:
            reader = csv.DictReader(f)
            for row in reader:
                ts_str = row.get('timestamp', '')
                if ts_str:
                    # ms timestamps (13+ digits) or ISO format
                    try:
                        ts = int(ts_str) // 1000 if len(ts_str) >= 13 and ts_str.lstrip('-').isdigit() else 0
                    except:
                        ts = 0
                else:
                    ts = 0
                bars.append({
                    'ts': ts,
                    'open': float(row['open']),
                    'high': float(row['high']),
                    'low': float(row['low']),
                    'close': float(row['close']),
                })
        # Filter out invalid timestamps (pre-2000 or absurdly large)
        bars = [b for b in bars if 1000000000 <= b['ts'] <= 2000000000]
        return bars
    
    # Load yearly files
    years_found = set()
    for year in range(2006, 2027):
        fname = f"{pair}-h1-bid-{year}-01-01-{year}-12-31.csv"
        fpath = os.path.join(DATA_DIR, fname)
        if not os.path.exists(fpath):
            # Try partial year (2025-2026)
            for suffix in ["2025-05-19", "2026-05-20", "2025-12-31", "2026-05-20"]:
                fname2 = f"{pair}-h1-bid-{year}-01-01-{suffix}.csv"
                fpath2 = os.path.join(DATA_DIR, fname2)
                if os.path.exists(fpath2):
                    fpath = fpath2
                    break
            else:
                continue
        
        with open(fpath) as f:
            reader = csv.DictReader(f)
            for row in reader:
                # Parse timestamp
                ts_str = row.get('timestamp', '')
                if ts_str:
                    try:
                        dt = datetime.fromisoformat(ts_str.replace('Z', '+00:00'))
                        ts = int(dt.timestamp())
                    except:
                        # Maybe it's a millisecond timestamp
                        try:
                            ts = int(ts_str) // 1000
                        except:
                            ts = 0
                else:
                    ts = 0
                bars.append({
                    'ts': ts,
                    'open': float(row['open']),
                    'high': float(row['high']),
                    'low': float(row['low']),
                    'close': float(row['close']),
                })
        years_found.add(year)
    
    # Filter out invalid timestamps (pre-2000 or absurdly large)
    bars = [b for b in bars if 1000000000 <= b['ts'] <= 2000000000]
    bars.sort(key=lambda b: b['ts'])
    return bars

def build_daily_ohlc(bars: List[dict]) -> List[dict]:
    """Aggregate H1 bars into daily bars."""
    if not bars:
        return []
    
    days = []
    current_day = None
    day_open = day_high = day_low = day_close = 0
    day_ts = 0
    first = True
    
    for b in bars:
        if b['ts'] == 0:
            continue
        dt = datetime.fromtimestamp(b['ts'], tz=timezone.utc)
        day_key = dt.date()
        
        if current_day is None:
            current_day = day_key
            day_ts = b['ts']
            day_open = b['open']
            day_high = b['high']
            day_low = b['low']
            day_close = b['close']
        elif day_key != current_day:
            days.append({
                'ts': day_ts,
                'date': current_day,
                'open': day_open,
                'high': day_high,
                'low': day_low,
                'close': day_close,
            })
            current_day = day_key
            day_ts = b['ts']
            day_open = b['close']  # use last bar's close as new open
            day_high = b['high'] if b['high'] > day_close else day_close
            day_low = b['low'] if b['low'] < day_close else day_close
            day_close = b['close']
        else:
            day_high = max(day_high, b['high'])
            day_low = min(day_low, b['low'])
            day_close = b['close']
    
    # Don't forget the last day
    if current_day is not None:
        days.append({
            'ts': day_ts,
            'date': current_day,
            'open': day_open,
            'high': day_high,
            'low': day_low,
            'close': day_close,
        })
    
    return days

def compute_returns(days: List[dict], window_fn) -> List[Tuple]:
    """
    Apply a window function to each day, return list of (window_label, return, year, month, day).
    window_fn: (day_idx, days) -> (label, is_active) where is_active means "trade this window"
    """
    results = []
    for i in range(1, len(days)):
        label, is_active = window_fn(i, days)
        if is_active:
            ret = (days[i]['close'] - days[i-1]['close']) / days[i-1]['close'] * 100
            results.append((label, ret, days[i]['date'].year, days[i]['date'].month, days[i]['date'].day))
    return results

def analyze_pattern(results: List[Tuple], min_occurrences: int = 10) -> dict:
    """Compute stats for a set of (label, return, year, month, day) results."""
    if len(results) < min_occurrences:
        return None
    
    returns = [r[1] for r in results]
    wins = sum(1 for r in returns if r > 0)
    total = len(returns)
    avg_ret = sum(returns) / total
    win_rate = wins / total * 100
    
    # Sharpe-ish: avg / std * sqrt(252)
    if total > 1:
        std = math.sqrt(sum((r - avg_ret)**2 for r in returns) / (total - 1))
        sharpe = avg_ret / std * math.sqrt(252) if std > 0 else 0
    else:
        sharpe = 0
    
    # Best / worst
    best = max(returns)
    worst = min(returns)
    
    # Consistency: % of years with positive avg return
    yearly_returns = defaultdict(list)
    for r in results:
        yearly_returns[r[2]].append(r[3])
    pos_years = sum(1 for yr, vals in yearly_returns.items() if sum(v > 0 for v, _ in [(x, 0) for x in vals]) > len(vals)/2)
    total_years = len(yearly_returns)
    consistency = pos_years / total_years * 100 if total_years > 0 else 0
    
    return {
        'count': total,
        'avg_return': avg_ret,
        'win_rate': win_rate,
        'sharpe': sharpe,
        'best': best,
        'worst': worst,
        'consistency': consistency,
        'years': total_years,
    }

# ── Window Functions ──────────────────────────────────────────

def window_month(i, days):
    """Label by calendar month."""
    d = days[i]['date']
    label = f"Month_{d.month:02d}"
    return label, True

def window_weekday(i, days):
    """Label by day of week (0=Mon, 6=Sun)."""
    dow = days[i]['date'].weekday()
    names = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
    return names[dow], True

def window_day_of_month(i, days):
    """Label by day of month number."""
    d = days[i]['date']
    return f"DoM_{d.day:02d}", True

def window_turn_of_month(i, days):
    """Last 3 + first 3 trading days of month."""
    d = days[i]['date']
    is_first_3 = d.day <= 3
    # Check if it's last 3 trading days
    # Simple heuristic: day 26+ 
    is_last_3 = d.day >= 26
    if is_first_3:
        return "First_3", True
    elif is_last_3:
        return "Last_3", True
    return "", False

def window_quarter_end(i, days):
    """Last 5 trading days of March, June, September, December."""
    d = days[i]['date']
    if d.month in [3, 6, 9, 12] and d.day >= 24:
        return f"QE_{d.month:02d}", True
    return "", False

def window_month_week(i, days):
    """Week of month (1-5) × month."""
    d = days[i]['date']
    week = (d.day - 1) // 7 + 1
    if week > 5:
        week = 5
    label = f"M{d.month:02d}_W{week}"
    return label, True

def window_month_weekday(i, days):
    """Month × weekday combo."""
    d = days[i]['date']
    dow_names = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
    dow = dow_names[d.weekday()]
    return f"M{d.month:02d}_{dow}", True

def window_week_of_year(i, days):
    """ISO week number."""
    iso = days[i]['date'].isocalendar()
    return f"ISO_W{iso[1]:02d}", True

def window_month_half(i, days):
    """First half (1-15) vs second half (16-EO M) of month."""
    d = days[i]['date']
    half = "First_Half" if d.day <= 15 else "Second_Half"
    return f"M{d.month:02d}_{half}", True

# ── Runner ─────────────────────────────────────────────────────

WINDOWS = [
    ("Monthly", window_month),
    ("Weekday", window_weekday),
    ("Day of Month", window_day_of_month),
    ("Turn of Month", window_turn_of_month),
    ("Quarter End", window_quarter_end),
    ("Month × Week", window_month_week),
    ("Month × Weekday", window_month_weekday),
    ("ISO Week", window_week_of_year),
    ("Month Half", window_month_half),
]

def run_scanner():
    """Main scan across all pairs."""
    all_results = {}
    
    for pair in ALL_PAIRS:
        print(f"\n{'='*60}", file=sys.stderr)
        print(f"📥 Loading {pair.upper()}...", file=sys.stderr)
        
        bars = load_pair(pair)
        print(f"   {len(bars)} H1 bars loaded", file=sys.stderr)
        
        days = build_daily_ohlc(bars)
        print(f"   {len(days)} daily bars built", file=sys.stderr)
        
        if len(days) < 200:
            print(f"   ⚠️ Too few days ({len(days)}), skipping", file=sys.stderr)
            continue
        
        pair_results = {}
        
        for win_name, win_fn in WINDOWS:
            results = compute_returns(days, win_fn)
            # Group by label
            by_label = defaultdict(list)
            for label, ret, year, month, day in results:
                by_label[label].append((label, ret, year, month, day))
            
            label_stats = {}
            for label, group in by_label.items():
                stats = analyze_pattern(group)
                if stats:
                    label_stats[label] = stats
            
            if label_stats:
                pair_results[win_name] = label_stats
        
        all_results[pair] = pair_results
    
    return all_results

def print_report(results: dict):
    """Print a formatted report of all findings."""
    
    print("\n" + "#"*70)
    print("# 🔍 FOREX SEASONALITY SCANNER REPORT")
    print("#"*70)
    print(f"\nAnalysis period: 2006-2026 (20 years of H1 data)")
    print(f"Patterns evaluated: Monthly, Weekday, Day-of-Month, Turn-of-Month, Quarter-End, Month×Week, Month×Weekday, ISO Week, Month Half")
    
    # Collect all patterns with their stats across pairs
    all_patterns = []
    
    for pair, pair_results in results.items():
        for win_name, label_stats in pair_results.items():
            for label, stats in label_stats.items():
                all_patterns.append({
                    'pair': pair.upper(),
                    'window': win_name,
                    'label': label,
                    'avg_return': stats['avg_return'],
                    'win_rate': stats['win_rate'],
                    'sharpe': stats['sharpe'],
                    'count': stats['count'],
                    'consistency': stats['consistency'],
                    'years': stats['years'],
                })
    
    # ── Rank by Sharpe (best risk-adjusted) ──
    print("\n\n## 🏆 TOP PATTERNS BY SHARPE RATIO (risk-adjusted)")
    print("   Filter: count ≥ 10 occurrences")
    print("="*60)
    
    top_sharpe = sorted(all_patterns, key=lambda p: p['sharpe'], reverse=True)[:30]
    
    print(f"{'Rank':<5} {'Pair':<8} {'Pattern':<18} {'Label':<14} {'Avg%':>8} {'WR%':>6} {'Sharpe':>8} {'N':>5} {'Cons%':>6}")
    print("-"*80)
    for i, p in enumerate(top_sharpe, 1):
        print(f"{i:<5} {p['pair']:<8} {p['window']:<18} {p['label']:<14} {p['avg_return']:>8.3f} {p['win_rate']:>6.1f} {p['sharpe']:>8.3f} {p['count']:>5} {p['consistency']:>6.0f}")
    
    # ── Rank by Win Rate ──
    print("\n\n## 🎯 TOP PATTERNS BY WIN RATE")
    print("   Filter: count ≥ 10 occurrences")
    print("="*60)
    
    top_wr = sorted(all_patterns, key=lambda p: p['win_rate'], reverse=True)[:30]
    
    print(f"{'Rank':<5} {'Pair':<8} {'Pattern':<18} {'Label':<14} {'Avg%':>8} {'WR%':>6} {'Sharpe':>8} {'N':>5} {'Cons%':>6}")
    print("-"*80)
    for i, p in enumerate(top_wr, 1):
        print(f"{i:<5} {p['pair']:<8} {p['window']:<18} {p['label']:<14} {p['avg_return']:>8.3f} {p['win_rate']:>6.1f} {p['sharpe']:>8.3f} {p['count']:>5} {p['consistency']:>6.0f}")
    
    # ── Rank by Avg Return ──
    print("\n\n## 💰 TOP PATTERNS BY AVG DAILY RETURN")
    print("   Filter: count ≥ 10 occurrences")
    print("="*60)
    
    top_ret = sorted(all_patterns, key=lambda p: p['avg_return'], reverse=True)[:30]
    
    print(f"{'Rank':<5} {'Pair':<8} {'Pattern':<18} {'Label':<14} {'Avg%':>8} {'WR%':>6} {'Sharpe':>8} {'N':>5} {'Cons%':>6}")
    print("-"*80)
    for i, p in enumerate(top_ret, 1):
        print(f"{i:<5} {p['pair']:<8} {p['window']:<18} {p['label']:<14} {p['avg_return']:>8.3f} {p['win_rate']:>6.1f} {p['sharpe']:>8.3f} {p['count']:>5} {p['consistency']:>6.0f}")
    
    # ── Monthly patterns summary ──
    print("\n\n## 📅 MONTHLY PATTERNS SUMMARY")
    print("="*60)
    
    monthly = [p for p in all_patterns if p['window'] == 'Monthly']
    months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
    
    for pair in sorted(set(p['pair'] for p in monthly)):
        pair_monthly = {p['label']: p for p in monthly if p['pair'] == pair}
        if not pair_monthly:
            continue
        print(f"\n{pair}:")
        header = "  " + "".join(f"{m:<10}" for m in months)
        print(header)
        
        # Avg return row
        avg_row = "  "
        for i, m in enumerate(months, 1):
            label = f"Month_{i:02d}"
            if label in pair_monthly:
                avg_row += f"{pair_monthly[label]['avg_return']:>+8.3f}% "
            else:
                avg_row += f"{'N/A':>10} "
        print(f"Avg%: {avg_row}")
        
        # Win rate row
        wr_row = "  "
        for i, m in enumerate(months, 1):
            label = f"Month_{i:02d}"
            if label in pair_monthly:
                wr_row += f"{pair_monthly[label]['win_rate']:>7.1f}%  "
            else:
                wr_row += f"{'N/A':>10} "
        print(f"WR%:  {wr_row}")
        
        # Sharpe row
        sh_row = "  "
        for i, m in enumerate(months, 1):
            label = f"Month_{i:02d}"
            if label in pair_monthly:
                sh_row += f"{pair_monthly[label]['sharpe']:>+8.3f} "
            else:
                sh_row += f"{'N/A':>10} "
        print(f"Sharpe:{sh_row}")
    
    # ── Weekday patterns ──
    print("\n\n## 📆 WEEKDAY PATTERNS SUMMARY")
    print("="*60)
    
    weekday_p = [p for p in all_patterns if p['window'] == 'Weekday']
    dows = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri']
    
    for pair in sorted(set(p['pair'] for p in weekday_p)):
        pw = {p['label']: p for p in weekday_p if p['pair'] == pair}
        if not pw:
            continue
        avg_row = f"{pair}: "
        wr_row = "     "
        sh_row = "     "
        for d in dows:
            if d in pw:
                avg_row += f"{d}:{pw[d]['avg_return']:>+7.3f}% "
                wr_row += f"WR:{pw[d]['win_rate']:>5.1f}% "
                sh_row += f"S:{pw[d]['sharpe']:>+5.2f}  "
        print(avg_row)
        print(wr_row)
        print(sh_row)
        print()
    
    # ── Turn of Month ──
    print("\n\n## 🔄 TURN OF MONTH PATTERNS")
    print("="*60)
    turn_p = [p for p in all_patterns if p['window'] == 'Turn of Month']
    print(f"{'Pair':<8} {'Period':<12} {'Avg%':>8} {'WR%':>6} {'Sharpe':>8} {'N':>5} {'Cons%':>6}")
    print("-"*55)
    for p in sorted(turn_p, key=lambda x: abs(x['avg_return']), reverse=True):
        print(f"{p['pair']:<8} {p['label']:<12} {p['avg_return']:>8.3f} {p['win_rate']:>6.1f} {p['sharpe']:>8.3f} {p['count']:>5} {p['consistency']:>6.0f}")
    
    # ── Quarter End ──
    print("\n\n## 🏁 QUARTER END PATTERNS")
    print("="*60)
    qe_p = [p for p in all_patterns if p['window'] == 'Quarter End']
    print(f"{'Pair':<8} {'Period':<12} {'Avg%':>8} {'WR%':>6} {'Sharpe':>8} {'N':>5} {'Cons%':>6}")
    print("-"*55)
    for p in sorted(qe_p, key=lambda x: abs(x['avg_return']), reverse=True):
        print(f"{p['pair']:<8} {p['label']:<12} {p['avg_return']:>8.3f} {p['win_rate']:>6.1f} {p['sharpe']:>8.3f} {p['count']:>5} {p['consistency']:>6.0f}")
    
    # ── Best selling seasonalities (worst avg return = short opportunities) ──
    print("\n\n## 📉 BEST SHORT OPPORTUNITIES (worst avg daily return)")
    print("   Filter: count ≥ 10")
    print("="*60)
    
    worst = sorted(all_patterns, key=lambda p: p['avg_return'])[:20]
    print(f"{'Rank':<5} {'Pair':<8} {'Pattern':<18} {'Label':<14} {'Avg%':>8} {'WR%':>6} {'Sharpe':>8} {'N':>5}")
    print("-"*65)
    for i, p in enumerate(worst, 1):
        print(f"{i:<5} {p['pair']:<8} {p['window']:<18} {p['label']:<14} {p['avg_return']:>8.3f} {p['win_rate']:>6.1f} {p['sharpe']:>8.3f} {p['count']:>5}")
    
    # ── Consistency leaders ──
    print("\n\n## 🏅 MOST CONSISTENT PATTERNS (highest year-over-year consistency)")
    print("   Filter: count ≥ 15, years ≥ 5")
    print("="*60)
    
    consistent = [p for p in all_patterns if p['count'] >= 15 and p['years'] >= 5]
    top_cons = sorted(consistent, key=lambda p: (p['consistency'] * abs(p['avg_return'])), reverse=True)[:20]
    print(f"{'Rank':<5} {'Pair':<8} {'Pattern':<18} {'Label':<14} {'Avg%':>8} {'WR%':>6} {'Cons%':>6} {'Sharpe':>8} {'N':>5}")
    print("-"*75)
    for i, p in enumerate(top_cons, 1):
        print(f"{i:<5} {p['pair']:<8} {p['window']:<18} {p['label']:<14} {p['avg_return']:>8.3f} {p['win_rate']:>6.1f} {p['consistency']:>6.0f} {p['sharpe']:>8.3f} {p['count']:>5}")
    
    print(f"\n{'='*70}")
    print(f"📊 Total patterns analyzed: {len(all_patterns)}")
    print(f"Total pairs: {len(set(p['pair'] for p in all_patterns))}")
    print(f"{'='*70}")

if __name__ == '__main__':
    results = run_scanner()
    print_report(results)
