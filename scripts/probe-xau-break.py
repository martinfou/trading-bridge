#!/usr/bin/env python3
"""Probe 3 : le break DE SETTLEMENT quotidien de l'or (17:00 ET) est-il visible dans le feed ?
On regarde, par mois, quelles heures h20..h23 sont REELLES pour MAR-JEU, et la presence d'un trou.
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

print("--- presence (nb de barres REELLES) par heure h19..h23 et par mois, jours MAR-JEU")
print("  mois  n_jours " + "".join(f"  h{h:02d}" for h in range(19,24)))
for m in range(1,13):
    days = sorted({t.date() for t in real if t.month == m and t.weekday() in (1,2,3)})
    cnt = {h: 0 for h in range(19,24)}
    for d in days:
        for h in range(19,24):
            if dt.datetime(d.year,d.month,d.day,h,tzinfo=UTC) in real: cnt[h]+=1
    if not days: continue
    print(f"  {m:4d} {len(days):7d} " + "".join(f"{cnt[h]/len(days)*100:5.0f}%" for h in range(19,24)))

print("\n--- pareil pour h20..h23 cote VENDREDI (la cloture HEBDO)")
print("  mois  n_jours " + "".join(f"  h{h:02d}" for h in range(19,24)))
for m in range(1,13):
    days = sorted({t.date() for t in real if t.month == m and t.weekday() == 4})
    if not days: continue
    cnt = {h: 0 for h in range(19,24)}
    for d in days:
        for h in range(19,24):
            if dt.datetime(d.year,d.month,d.day,h,tzinfo=UTC) in real: cnt[h]+=1
    print(f"  {m:4d} {len(days):7d} " + "".join(f"{cnt[h]/len(days)*100:5.0f}%" for h in range(19,24)))

# un jour concret, ete et hiver
print("\n--- 3 jours concrets (structure complete des barres REELLES)")
for d in [dt.date(2024,6,12), dt.date(2024,12,11), dt.date(2024,6,14)]:
    hs = sorted(t.hour for t in real if t.date()==d)
    print(f"  {d} wd={d.weekday()} heures reelles: {hs}")
