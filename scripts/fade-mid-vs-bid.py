#!/usr/bin/env python3
"""
64e — LE CONTROLE DECISIF : le fade de la derniere heure est-il un MOUVEMENT DE PRIX (mid)
ou l'APPARITION DU SPREAD (bid) ?

Une baisse de prix genuine deplace le mid ET le bid. Un elargissement de spread
symetrique ne deplace QUE le bid (le mid reste). On mesure les deux, meme fenetre,
memes vendredis, et on publie les trois nombres : bid, mid, spread a la sortie.
"""
import os, csv, statistics as st
from datetime import datetime, timezone
from collections import defaultdict

D = os.path.expanduser("~/projects/tb-research/data/oanda-ba")
FMT = "%Y-%m-%dT%H:%M:%SZ"

def load(inst, gran="H1"):
    out = {}
    for r in csv.DictReader(open(os.path.join(D, f"{inst}_{gran}_BA.csv"))):
        t = datetime.strptime(r["time"][:19] + "Z", FMT).replace(tzinfo=timezone.utc)
        bo, bc, ao, ac = (float(r[k]) for k in ("bid_o", "bid_c", "ask_o", "ask_c"))
        out[t] = (bo, bc, ao, ac)
    return dict(sorted(out.items()))

def sp(b, a):
    m = (b + a) / 2
    return (a - b) / m * 10000.0

def tstat(xs):
    if len(xs) < 3:
        return 0.0
    m, sd = st.mean(xs), st.stdev(xs)
    return m / (sd / len(xs) ** 0.5) if sd else 0.0

def mid(bo, ao):
    return (bo + ao) / 2.0

ERAS = [(2005, 2009), (2010, 2013), (2014, 2017), (2018, 2021), (2022, 2026)]
for inst in ["GBP_CHF", "EUR_CHF", "AUD_CHF"]:
    d = load(inst)
    fri = defaultdict(list)
    for t in d:
        if t.weekday() == 4:
            fri[t.isocalendar()[:2]].append(t)
    rows = []
    for k, ts in fri.items():
        ts.sort()
        if len(ts) < 5:
            continue
        e = next((t for t in ts if t.hour == 13), None)
        if not e:
            continue
        x = ts[-1]                      # derniere barre reelle = derniere heure avant la cloture
        prev = ts[-2]                   # barre precedente
        # 1) leg de la derniere heure (k=0) : open de la derniere barre -> close
        b0 = (d[x][0] - d[x][1]) / d[x][0] * 10000
        m0 = (mid(d[x][0], d[x][2]) - mid(d[x][1], d[x][3])) / mid(d[x][0], d[x][2]) * 10000
        # 2) jambe 13:00 -> close
        bA = (d[e][0] - d[x][1]) / d[e][0] * 10000
        mA = (mid(d[e][0], d[e][2]) - mid(d[x][1], d[x][3])) / mid(d[e][0], d[e][2]) * 10000
        # 3) jambe 13:00 -> open de la derniere barre (avant la derniere heure)
        bP = (d[e][0] - d[x][0]) / d[e][0] * 10000
        mP = (mid(d[e][0], d[e][2]) - mid(d[x][0], d[x][2])) / mid(d[e][0], d[e][2]) * 10000
        rows.append((e.year, b0, m0, bA, mA, bP, mP, sp(d[x][1], d[x][3]), sp(d[x][0], d[x][2])))
    print("=" * 108)
    print(f"{inst} — n={len(rows)}. Convention FADE (SELL) : positif = le prix baisse.")
    print("  Une baisse genuine deplace le MID ; un elargissement de spread ne deplace que le BID.")
    print(f"  {'ERE':10s} {'n':>4s} | {'k=0 bid':>8s} {'k=0 mid':>8s} | {'13h->close bid':>14s} {'mid':>7s} | "
          f"{'13h->H-1 bid':>12s} {'mid':>7s} | {'spread in':>9s} {'spread out':>10s}")
    for a, z in ERAS + [(2005, 2026)]:
        sub = [r for r in rows if a <= r[0] <= z]
        if len(sub) < 10:
            continue
        lbl = f"{a}-{z}" if (a, z) != (2005, 2026) else "FULL"
        c = lambda i: st.mean(r[i] for r in sub)
        print(f"  {lbl:10s} {len(sub):4d} | {c(1):+8.2f} {c(2):+8.2f} | {c(3):+14.2f} {c(4):+7.2f} | "
              f"{c(5):+12.2f} {c(6):+7.2f} | {c(8):9.2f} {c(7):10.2f}")
    # t du mid pour la derniere heure, par ere
    print("      t du k=0 : ", end="")
    for a, z in ERAS:
        sub = [r[2] for r in rows if a <= r[0] <= z]
        if len(sub) >= 10:
            print(f"{a}-{z}: bid t{st.mean([r[1] for r in rows if a<=r[0]<=z])/ (st.stdev([r[1] for r in rows if a<=r[0]<=z]) or 1) * len(sub)**0.5:+5.1f} "
                  f"mid t{tstat(sub):+5.2f}  ", end="")
    print()
