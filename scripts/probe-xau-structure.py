#!/usr/bin/env python3
"""Probe structure de la serie XAU_USD H1 (Dukascopy bid) avant toute mesure.
Questions: quelles heures cotent? quelle est la DERNIERE barre reelle de chaque jour?
Le marche de l'or a-t-il une cloture QUOTIDIENNE (trou intra-journalier) ?
"""
import csv, datetime as dt, glob, os, collections

DATA = os.path.expanduser("~/projects/tb-research/data/historical/dukascopy")
UTC = dt.timezone.utc
files = sorted(glob.glob(f"{DATA}/xauusd-h1-bid-[12]*.csv"))

ohlc = {}
for path in files:
    with open(path, newline="") as f:
        r = csv.reader(f); next(r, None)
        for row in r:
            if len(row) < 5 or not row[0].strip().isdigit(): continue
            v = int(row[0])
            if v < 10**11: v *= 1000
            try:
                t = dt.datetime.fromtimestamp(v/1000.0, tz=UTC).replace(minute=0, second=0, microsecond=0)
                ohlc[t] = tuple(float(x) for x in row[1:5])
            except Exception: continue

ks = sorted(ohlc)
print(f"fichiers={len(files)} bars={len(ohlc)} {ks[0]} -> {ks[-1]}")

# 1. barres par heure UTC
byh = collections.Counter(t.hour for t in ohlc)
print("\n--- barres par heure UTC (2006-2025)")
for h in range(24):
    print(f"  h{h:02d} {byh.get(h,0):8d}")

# 2. barres par jour de semaine
bywd = collections.Counter(t.weekday() for t in ohlc)
DOW = ["LUN","MAR","MER","JEU","VEN","SAM","DIM"]
print("\n--- barres par jour de semaine: " + "  ".join(f"{DOW[i]}={bywd.get(i,0)}" for i in range(7)))

# 3. derniere barre REELLE par jour : distribution d'heure, par mois (ete/hiver)
real = {t: v for t, v in ohlc.items() if v[1] > v[2]}
byday = collections.defaultdict(list)
for t in real: byday[t.date()].append(t)
last_h = collections.Counter()
for d, ts in byday.items():
    if d.weekday() < 5:
        last_h[max(ts).hour] += 1
print("\n--- heure UTC de la DERNIERE barre reelle lun-ven:", dict(sorted(last_h.items(), key=lambda kv: -kv[1])[:8]))

# ete vs hiver
summer, winter = collections.Counter(), collections.Counter()
for d, ts in byday.items():
    if d.weekday() >= 5: continue
    h = max(ts).hour
    (summer if 4 <= d.month <= 10 else winter)[h] += 1
print("  ete (avr-oct):", dict(sorted(summer.items(), key=lambda kv: -kv[1])[:6]))
print("  hiver (nov-mar):", dict(sorted(winter.items(), key=lambda kv: -kv[1])[:6]))

# 4. premiere barre reelle du jour
first_h = collections.Counter()
for d, ts in byday.items():
    if d.weekday() < 5: first_h[min(ts).hour] += 1
print("\n--- heure UTC de la PREMIERE barre reelle lun-ven:", dict(sorted(first_h.items(), key=lambda kv: -kv[1])[:8]))

# 5. trou intra-journalier: heures manquantes consecutives entre derniere et premiere
print("\n--- exemple de structure journaliere (3 jours au hasard)")
sample = [d for d in sorted(byday) if d.weekday() < 5][1000:1003]
for d in sample:
    ts = sorted(byday[d]); print(f"  {d} {DOW[d.weekday()]} n={len(ts):2d} {ts[0].strftime('%H:%M')} -> {ts[-1].strftime('%H:%M')}")

# 6. etendue des jours par an
years = collections.Counter(d.year for d in byday)
print("\n--- jours de cotation par an:", dict(sorted(years.items())))
