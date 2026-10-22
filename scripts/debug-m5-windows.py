#!/usr/bin/env python3
"""Debug: verify the M5 window arithmetic on a few Fridays before trusting aggregates."""
import os, csv, statistics as st
from datetime import datetime, timezone, timedelta
from collections import defaultdict

D = os.path.expanduser("~/projects/tb-research/data/oanda-ba")
FMT = "%Y-%m-%dT%H:%M:%SZ"

def load(inst, gran):
    out = []
    for r in csv.DictReader(open(os.path.join(D, f"{inst}_{gran}_BA.csv"))):
        t = datetime.strptime(r["time"][:19] + "Z", FMT).replace(tzinfo=timezone.utc)
        out.append(dict(t=t, bo=float(r["bid_o"]), bc=float(r["bid_c"]),
                        ao=float(r["ask_o"]), ac=float(r["ask_c"])))
    out.sort(key=lambda x: x["t"])
    return out

def sp(b, a):
    m = (b + a) / 2
    return (a - b) / m * 10000.0

def fridays(bars):
    by = defaultdict(list)
    for b in bars:
        if b["t"].weekday() == 4:
            by[b["t"].isocalendar()[:2]].append(b)
    return {k: sorted(v, key=lambda x: x["t"]) for k, v in by.items()}

m5 = load("GBP_CHF", "M5")
fr = fridays(m5)
ks = sorted(fr)[:4]
for k in ks:
    bs = fr[k]
    ce = bs[-1]["t"] + timedelta(minutes=5)
    ent = next((x for x in bs if x["t"].hour == 13 and x["t"].minute == 0), None)
    print("week", k, "bars", len(bs), "last", bs[-1]["t"], "close_end", ce, "ent", ent["t"] if ent else None)
    if not ent:
        continue
    for off, lab in [(-60, "H-60"), (-30, "H-30"), (-10, "H-10"), (0, "close")]:
        t = ce + timedelta(minutes=off)
        cand = [x for x in bs if x["t"] < t]
        ex = cand[-1]
        fade = (ent["bo"] - ex["bc"]) / ent["bo"] * 10000
        s = sp(ex["bc"], ex["ac"])
        print(f"   {lab:6s} exit {ex['t']}  fade {fade:+7.2f}  spread {s:6.2f}  net {fade-s:+7.2f}")

# average of the same over all Fridays
print("\nMoyennes sur tous les vendredis M5 :")
for off, lab in [(-60, "H-60"), (-30, "H-30"), (-10, "H-10"), (0, "close")]:
    fades, nets, sps = [], [], []
    for k, bs in fr.items():
        if len(bs) < 60:
            continue
        ce = bs[-1]["t"] + timedelta(minutes=5)
        ent = next((x for x in bs if x["t"].hour == 13 and x["t"].minute == 0), None)
        if not ent:
            continue
        cand = [x for x in bs if x["t"] < ce + timedelta(minutes=off)]
        if not cand:
            continue
        ex = cand[-1]
        fade = (ent["bo"] - ex["bc"]) / ent["bo"] * 10000
        s = sp(ex["bc"], ex["ac"])
        fades.append(fade); sps.append(s); nets.append(fade - s)
    print(f"  {lab:6s} n={len(nets):4d} fade {st.mean(fades):+7.3f}  spread {st.mean(sps):6.3f}  net {st.mean(nets):+7.3f}")
