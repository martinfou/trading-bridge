#!/usr/bin/env python3
"""Probe 4 : qualite de l'ancre — sur quels jours le trou de settlement est-il DETECTABLE ?
Si une fraction importante des jours lun-jeu n'a AUCUN trou visible (feed rempli), l'ancre y est fausse
(close = h23 au lieu de h20/h21) et toute mesure k=0 est melangee.
"""
import csv, datetime as dt, glob, os, collections

DATA = os.path.expanduser("~/projects/tb-research/data/historical/dukascopy")
UTC = dt.timezone.utc
ohlc = {}
for path in sorted(glob.glob(f"{DATA}/xauusd-h1-bid-[12]*.csv")):
    with open(path, newline="") as f:
        r = csv.reader(f); next(r, None)
        for row in r:
            if len(row) < 5 or not row[0].strip().isdigit(): continue
            v = int(row[0]); v = v*1000 if v < 10**11 else v
            try:
                t = dt.datetime.fromtimestamp(v/1000.0, tz=UTC).replace(minute=0,second=0,microsecond=0)
                ohlc[t] = tuple(float(x) for x in row[1:5])
            except Exception: continue
real = {t: v for t, v in ohlc.items() if v[1] > v[2]}
byday = collections.defaultdict(set)
for t in real: byday[t.date()].add(t.hour)

print("--- jours LUN-JEU par an : trou detecte ? (h21 absent / h22 absent / aucun)")
print("  an   n_jours  h21_absent  h22_absent  aucun_trou   h21%  h22%")
for y in range(2006, 2026):
    ds = [d for d in byday if d.year == y and d.weekday() < 4]
    if not ds: continue
    a21 = sum(1 for d in ds if 21 not in byday[d])
    a22 = sum(1 for d in ds if 22 not in byday[d])
    none = sum(1 for d in ds if 21 in byday[d] and 22 in byday[d])
    print(f"  {y} {len(ds):7d} {a21:11d} {a22:11d} {none:12d} {a21/len(ds)*100:5.0f}% {a22/len(ds)*100:5.0f}%")

print("\n--- quand il n'y a AUCUN trou, combien de barres reelles dans la journee ? (sur 24)")
cnt = collections.Counter(len(byday[d]) for d in byday if d.weekday() < 4 and 21 in byday[d] and 22 in byday[d])
print("  ", dict(sorted(cnt.items(), key=lambda kv: -kv[1])[:10]))
print("\n--- jours AVEC trou : nb de barres reelles")
cnt2 = collections.Counter(len(byday[d]) for d in byday if d.weekday() < 4 and (21 not in byday[d] or 22 not in byday[d]))
print("  ", dict(sorted(cnt2.items(), key=lambda kv: -kv[1])[:10]))

print("\n--- les 20,21,22,23 presentes par mois, pour les jours SANS trou (quelle epoque ?)")
for y in [2006, 2009, 2012, 2015, 2018, 2021, 2024]:
    ds = [d for d in byday if d.year == y and d.weekday() < 4 and 21 in byday[d] and 22 in byday[d]]
    if ds: print(f"  {y}: {len(ds)} jours sans trou, exemple {sorted(ds)[0]} heures={sorted(byday[sorted(ds)[0]])}")
