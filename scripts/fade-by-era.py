#!/usr/bin/env python3
"""
64e — LE FADE PAR ERE, par paire : le plein-echantillon est-il porte par des annees mortes ?
Mesure : fade BID 13:00 UTC -> close de la derniere barre reelle ; spread plein de sortie mesure (ASK).
net = fade - spread_sortie.
"""
import os, csv, statistics as st
from datetime import datetime, timezone
from collections import defaultdict

D = os.path.expanduser("~/projects/tb-research/data/oanda-ba")
FMT = "%Y-%m-%dT%H:%M:%SZ"

def load(inst):
    out = {}
    for r in csv.DictReader(open(os.path.join(D, f"{inst}_H1_BA.csv"))):
        t = datetime.strptime(r["time"][:19] + "Z", FMT).replace(tzinfo=timezone.utc)
        out[t] = (float(r["bid_o"]), float(r["bid_c"]), float(r["ask_o"]), float(r["ask_c"]))
    return dict(sorted(out.items()))

def sp(b, a):
    m = (b + a) / 2
    return (a - b) / m * 10000.0

def tstat(xs):
    if len(xs) < 3:
        return 0.0
    m, sd = st.mean(xs), st.stdev(xs)
    return m / (sd / len(xs) ** 0.5) if sd else 0.0

ERAS = [(2005, 2009), (2010, 2013), (2014, 2017), (2018, 2021), (2022, 2026)]
for inst in ["GBP_CHF", "EUR_CHF", "AUD_CHF"]:
    d = load(inst)
    fri = defaultdict(list)
    for t in d:
        if t.weekday() == 4:
            fri[t.isocalendar()[:2]].append(t)
    rec = []
    for k, ts in fri.items():
        ts.sort()
        e = next((t for t in ts if t.hour == 13), None)
        if not e or len(ts) < 5:
            continue
        x = ts[-1]
        fade = (d[e][0] - d[x][1]) / d[e][0] * 10000
        s = sp(d[x][1], d[x][3])
        # jambe k=0 seule : open -> close de la derniere barre
        last = (d[x][0] - d[x][1]) / d[x][0] * 10000
        rec.append((e.year, fade, s, last))
    print("=" * 92)
    print(f"{inst} — n={len(rec)} vendredis")
    print(f"  {'ERE':10s} {'n':>4s} {'fade 13h':>10s} {'t':>6s} {'dont k=0':>9s} {'t':>6s} {'spread sortie':>13s} {'net':>8s}")
    for a, z in ERAS:
        sub = [r for r in rec if a <= r[0] <= z]
        if len(sub) < 10:
            continue
        f = [r[1] for r in sub]
        k0 = [r[3] for r in sub]
        s = [r[2] for r in sub]
        net = [r[1] - r[2] for r in sub]
        print(f"  {a}-{z} {len(sub):6d} {st.mean(f):+10.2f} {tstat(f):+6.2f} {st.mean(k0):+9.2f} {tstat(k0):+6.2f} "
              f"{st.mean(s):13.2f} {st.mean(net):+8.2f}")
    f = [r[1] for r in rec]; s = [r[2] for r in rec]; net = [r[1] - r[2] for r in rec]
    print(f"  {'FULL':10s} {len(rec):6d} {st.mean(f):+10.2f} {tstat(f):+6.2f} "
          f"{st.mean([r[3] for r in rec]):+9.2f} {tstat([r[3] for r in rec]):+6.2f} {st.mean(s):13.2f} {st.mean(net):+8.2f}")
    # net de la jambe k=0 seule, spread de sortie mesure (ce que D65 achete vraiment)
    k0net = [r[3] - r[2] for r in rec]
    for a, z in ERAS:
        sub = [(r[3] - r[2]) for r in rec if a <= r[0] <= z]
        if len(sub) >= 10:
            print(f"      net de la SEULE jambe k=0 (derniere heure) {a}-{z} : {st.mean(sub):+7.2f} bp  t {tstat(sub):+5.2f}  n={len(sub)}")
