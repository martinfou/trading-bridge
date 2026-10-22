#!/usr/bin/env python3
"""P0: les deux sources (H1 et M5, memes annees) doivent donner le meme fade et le meme spread de sortie."""
import os, csv, statistics as st
from datetime import datetime, timezone, timedelta
from collections import defaultdict

D = os.path.expanduser("~/projects/tb-research/data/oanda-ba")
FMT = "%Y-%m-%dT%H:%M:%SZ"

def load(inst, gran):
    out = {}
    for r in csv.DictReader(open(os.path.join(D, f"{inst}_{gran}_BA.csv"))):
        t = datetime.strptime(r["time"][:19] + "Z", FMT).replace(tzinfo=timezone.utc)
        out[t] = (float(r["bid_o"]), float(r["bid_c"]), float(r["ask_o"]), float(r["ask_c"]))
    return dict(sorted(out.items()))

def sp(b, a):
    m = (b + a) / 2
    return (a - b) / m * 10000.0

inst = "GBP_CHF"
h1 = load(inst, "H1")
m5 = load(inst, "M5")

def fridays(d):
    by = defaultdict(list)
    for t in d:
        if t.weekday() == 4:
            by[t.isocalendar()[:2]].append(t)
    return {k: sorted(v) for k, v in by.items()}

f1, f5 = fridays(h1), fridays(m5)
common = sorted(set(f1) & set(f5))
print(f"vendredis communs H1/M5 : {len(common)}")
print(f"  {'annee':6s} {'n':>4s} {'fade H1':>9s} {'fade M5':>9s} {'s_exit H1':>10s} {'s_exit M5':>10s}")
byyear = defaultdict(lambda: [[], [], [], []])
mismatch = 0
for k in common:
    t1, t5 = f1[k], f5[k]
    e1 = next((t for t in t1 if t.hour == 13), None)
    e5 = next((t for t in t5 if t.hour == 13 and t.minute == 0), None)
    if not e1 or not e5:
        continue
    x1, x5 = t1[-1], t5[-1]
    fade1 = (h1[e1][0] - h1[x1][1]) / h1[e1][0] * 10000
    fade5 = (m5[e5][0] - m5[x5][1]) / m5[e5][0] * 10000
    s1, s5 = sp(h1[x1][1], h1[x1][3]), sp(m5[x5][1], m5[x5][3])
    if abs(fade1 - fade5) > 5:
        mismatch += 1
    y = e1.year
    for i, v in enumerate((fade1, fade5, s1, s5)):
        byyear[y][i].append(v)
for y in sorted(byyear):
    a, b, c, d = byyear[y]
    print(f"  {y:6d} {len(a):4d} {st.mean(a):+9.2f} {st.mean(b):+9.2f} {st.mean(c):10.2f} {st.mean(d):10.2f}")
print(f"\n  ecart |fadeH1 - fadeM5| > 5 bp sur {mismatch}/{len(common)} vendredis")
allf1 = [x for y in byyear for x in byyear[y][0]]
allf5 = [x for y in byyear for x in byyear[y][1]]
print(f"  FULL H1 fade {st.mean(allf1):+.2f} bp | M5 fade {st.mean(allf5):+.2f} bp")
# et sur la periode complete H1 (2005-2026) pour situer l'ere M5
allh1 = []
for k, ts in f1.items():
    e = next((t for t in ts if t.hour == 13), None)
    if not e:
        continue
    allh1.append((e.year, (h1[e][0] - h1[ts[-1]][1]) / h1[e][0] * 10000))
for a, z in [(2005, 2011), (2012, 2017), (2018, 2021), (2022, 2026)]:
    v = [f for y, f in allh1 if a <= y <= z]
    print(f"  H1 fade {a}-{z} : {st.mean(v):+.2f} bp (n={len(v)})")
