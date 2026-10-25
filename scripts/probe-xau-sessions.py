#!/usr/bin/env python3
"""Probe 2 : XAU_USD H1 — structure intra-journaliere REELLE et comportement les jours de conge US.
Question decisive : le feed XAU (Dukascopy spot bid) cote-t-il les jours de conge US ?
A-t-il une cloture QUOTIDIENNE (break 21-22 UTC) ou est-il 24h Mon-Jeu ?
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

real = {t: v for t, v in ohlc.items() if v[1] > v[2]}
DOW = ["LUN","MAR","MER","JEU","VEN","SAM","DIM"]

# 1. barres REELLES par (jour de semaine, heure)
print("--- barres REELLES par heure, par jour de semaine (2006-2025)")
tab = collections.defaultdict(collections.Counter)
for t in real: tab[t.weekday()][t.hour] += 1
print("      " + "".join(f"h{h:02d}  " for h in range(24)))
for wd in range(7):
    print(f"  {DOW[wd]} " + "".join(f"{tab[wd].get(h,0):4d} " for h in range(24)))

# 2. nombre de barres reelles par jour de semaine (par an)
print("\n--- jours avec >=1 barre REELLE, par jour de semaine et par an")
cnt = collections.Counter((d.year, d.weekday()) for d in {t.date() for t in real})
for y in range(2006, 2026):
    print(f"  {y}: " + " ".join(f"{DOW[wd]}={cnt.get((y,wd),0):3d}" for wd in range(7)))

# 3. jours feries US : le feed cote-t-il ?
def nth_weekday(y, m, wd, n):
    d = dt.date(y, m, 1); c = 0
    while d.month == m:
        if d.weekday() == wd:
            c += 1
            if c == n: return d
        d += dt.timedelta(days=1)
def last_weekday(y, m, wd):
    d = dt.date(y, m+1, 1) - dt.timedelta(days=1) if m < 12 else dt.date(y, 12, 31)
    while d.weekday() != wd: d -= dt.timedelta(days=1)
    return d
def easter(y):
    a=y%19; b,c=divmod(y,100); d,e=divmod(b,4); f=(b+8)//25; g=(b-f+1)//3
    h=(19*a+b-d-g+15)%30; i,k=divmod(c,4); l=(32+2*e+2*i-h-k)%7; m=(a+11*h+22*l)//451
    mo=(h+l-7*m+114)//31; da=((h+l-7*m+114)%31)+1
    return dt.date(y,mo,da)
def observed(d):
    if d.weekday()==5: return d-dt.timedelta(days=1)
    if d.weekday()==6: return d+dt.timedelta(days=1)
    return d
hol = {}
for y in range(2006, 2026):
    items = [(observed(dt.date(y,1,1)),"NewYear"),(nth_weekday(y,1,0,3),"MLK"),
             (nth_weekday(y,2,0,3),"Presidents"),(easter(y)-dt.timedelta(days=2),"GoodFriday"),
             (last_weekday(y,5,0),"Memorial"),(observed(dt.date(y,7,4)),"July4"),
             (nth_weekday(y,9,0,1),"Labor"),(nth_weekday(y,11,3,4),"Thanksgiving"),
             (observed(dt.date(y,12,25)),"Christmas")]
    if y>=2022: items.append((observed(dt.date(y,6,19)),"Juneteenth"))
    for d,n in items:
        if d.weekday()<5: hol[d]=n

print(f"\n--- {len(hol)} conges US : barres REELLES ce jour-la (feed XAU)")
stat = collections.defaultdict(list)
for d,n in sorted(hol.items()):
    if d < dt.date(2006,1,3): continue
    ts = sorted(t for t in real if t.date()==d)
    stat[n].append(len(ts))
for n, vs in sorted(stat.items(), key=lambda kv:-len(kv[1])):
    zero = sum(1 for v in vs if v==0)
    print(f"  {n:13s} n={len(vs):3d}  barres reelles moy={sum(vs)/len(vs):5.1f}  jours a ZERO={zero:3d}")

# 4. comparaison FX (EUR_USD Dukascopy) les memes jours pour calibrer
fx = sorted(glob.glob(f"{DATA}/eurusd-h1-bid-[12]*.csv"))
fo = {}
for path in fx:
    with open(path, newline="") as f:
        r = csv.reader(f); next(r, None)
        for row in r:
            if len(row) < 5 or not row[0].strip().isdigit(): continue
            v=int(row[0]); v = v*1000 if v < 10**11 else v
            try:
                t = dt.datetime.fromtimestamp(v/1000.0, tz=UTC).replace(minute=0,second=0,microsecond=0)
                fo[t] = tuple(float(x) for x in row[1:5])
            except Exception: continue
fr = {t:v for t,v in fo.items() if v[1]>v[2]}
print("\n--- controle FX (EUR_USD, meme feed, memes jours)")
statf = collections.defaultdict(list)
for d,n in sorted(hol.items()):
    if d < dt.date(2006,1,3): continue
    statf[n].append(len([t for t in fr if t.date()==d]))
for n, vs in sorted(statf.items(), key=lambda kv:-len(kv[1])):
    print(f"  {n:13s} n={len(vs):3d}  barres reelles moy={sum(vs)/len(vs):5.1f}  jours a ZERO={sum(1 for v in vs if v==0):3d}")
