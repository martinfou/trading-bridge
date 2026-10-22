#!/usr/bin/env python3
"""64e (annexe) — chemin du MID dans la derniere heure (M5), par tranche de 5 min.
Si le mid ne bouge pas, le 'fade' mesure sur le bid est le spread qui s'ouvre."""
import os, csv, statistics as st
from datetime import datetime, timezone, timedelta
from collections import defaultdict

D = os.path.expanduser("~/projects/tb-research/data/oanda-ba")
FMT = "%Y-%m-%dT%H:%M:%SZ"

def load(inst):
    out = {}
    for r in csv.DictReader(open(os.path.join(D, f"{inst}_M5_BA.csv"))):
        t = datetime.strptime(r["time"][:19] + "Z", FMT).replace(tzinfo=timezone.utc)
        bo, bc, ao, ac = (float(r[k]) for k in ("bid_o", "bid_c", "ask_o", "ask_c"))
        out[t] = (bo, bc, ao, ac)
    return dict(sorted(out.items()))

def mid(bo, ao):
    return (bo + ao) / 2.0

def tstat(xs):
    if len(xs) < 3:
        return 0.0
    m, sd = st.mean(xs), st.stdev(xs)
    return m / (sd / len(xs) ** 0.5) if sd else 0.0

for inst in ["GBP_CHF", "AUD_CHF"]:
    if not os.path.exists(os.path.join(D, f"{inst}_M5_BA.csv")):
        continue
    d = load(inst)
    fri = defaultdict(list)
    for t in d:
        if t.weekday() == 4:
            fri[t.isocalendar()[:2]].append(t)
    print("=" * 96)
    print(f"{inst} M5 — {len(fri)} vendredis, {min(d)} -> {max(d)}")
    # reference : mid au debut de la derniere heure (H-60) et close
    buckets = [(-60, -45), (-45, -30), (-30, -20), (-20, -10), (-10, -5), (-5, 0)]
    res = defaultdict(list)
    tot_bid, tot_mid = [], []
    for k, ts in fri.items():
        ts.sort()
        if len(ts) < 60:
            continue
        x = ts[-1]
        ce = x + timedelta(minutes=5)
        starts = [t for t in ts if ce - timedelta(minutes=60) <= t < ce]
        if len(starts) < 10:
            continue
        p0 = mid(d[starts[0]][0], d[starts[0]][2])   # mid a H-60
        b0 = d[starts[0]][0]                          # bid a H-60
        for lo, hi in buckets:
            sel = [t for t in starts if ce + timedelta(minutes=lo) <= t < ce + timedelta(minutes=hi)]
            if not sel:
                continue
            tt = sel[-1]
            res[(lo, hi)].append((mid(d[tt][1], d[tt][3]) - p0) / p0 * 10000)
        pc = mid(d[x][1], d[x][3])
        tot_mid.append((pc - p0) / p0 * 10000)
        tot_bid.append((d[x][1] - b0) / b0 * 10000)
    print(f"  du debut de la derniere heure (H-60) jusqu'a la fin : MID {st.mean(tot_mid):+7.2f} bp (t {tstat(tot_mid):+5.2f}, n={len(tot_mid)})"
          f" | BID {st.mean(tot_bid):+7.2f} bp (t {tstat(tot_bid):+5.2f})")
    print("  chemin CUMULE du mid depuis H-60 (bp, negatif = baisse) :")
    for lo, hi in buckets:
        v = res.get((lo, hi))
        if v:
            print(f"    [{lo:+3d},{hi:+3d}) min : {st.mean(v):+7.2f} bp  t {tstat(v):+5.2f}  n={len(v)}")
    print()
