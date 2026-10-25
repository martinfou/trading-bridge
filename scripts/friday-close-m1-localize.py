#!/usr/bin/env python3
"""Localisation M1 de l'effet de derniere heure avant la cloture du vendredi (annexe du 59e).

But : savoir si le mouvement de la derniere heure se produit (a) etale sur l'heure, (b) dans les
dernieres minutes. Determine si une sortie "quelques minutes avant la cloture" capture l'edge.
Donnee : USD_CHF M1 bid (Dukascopy) — seule serie M1 avec un CHF disponible. Une annee => faible
puissance, a lire comme LOCALISATION, pas comme mesure de strategie.
"""
import csv
import datetime as dt
import glob
import math
import os

HOME = os.path.expanduser("~")
D = f"{HOME}/projects/tb-research/data/historical/dukascopy"
UTC = dt.timezone.utc


def load():
    px = {}
    for p in sorted(glob.glob(f"{D}/usdchf-m1-bid-*.csv")):
        with open(p, newline="") as f:
            r = csv.reader(f)
            next(r)
            for row in r:
                if len(row) < 5 or not row[0].strip().isdigit():
                    continue
                v = int(row[0])
                if v < 10 ** 11:
                    v *= 1000
                try:
                    t = dt.datetime.fromtimestamp(v / 1000.0, tz=UTC)
                except (ValueError, OverflowError, OSError):
                    continue
                px[t] = (float(row[1]), float(row[2]), float(row[3]), float(row[4]))
    return px


px = load()
ts = sorted(px)
print(f"USD_CHF M1 : {len(ts)} barres, {ts[0]} -> {ts[-1]}")

# derniere barre REELLE (high>low) de chaque vendredi
last = {}
for t in ts:
    if t.weekday() == 4:
        o, h, l, c = px[t]
        if h > l and (t.date() not in last or t > last[t.date()][0]):
            last[t.date()] = (t, c)
print(f"vendredis avec une derniere barre reelle : {len(last)}")

print("\nCumul (bp) sur les N dernieres MINUTES avant la cloture du vendredi (USD_CHF, brut) :")
for n in (5, 10, 15, 30, 60):
    xs = []
    for d, (t_close, c_close) in last.items():
        t0 = t_close - dt.timedelta(minutes=n - 1)
        if t0 in px:
            xs.append(math.log(c_close / px[t0][3]) * 1e4)
    if len(xs) < 20:
        print(f"  {n:3d} min : n={len(xs)} insuffisant")
        continue
    m = sum(xs) / len(xs)
    sd = (sum((x - m) ** 2 for x in xs) / (len(xs) - 1)) ** 0.5
    print(f"  {n:3d} min : {m:+6.2f} bp (t {m/(sd/len(xs)**0.5):+5.2f}, n={len(xs)})")
P = px
print("\nControle : % du mouvement de la derniere heure deja realise a H-n :")
for n in (5, 10, 15, 30):
    xs = []
    for d, (t_close, c_close) in last.items():
        t0 = t_close - dt.timedelta(hours=1)
        tm = t_close - dt.timedelta(minutes=n)
        if t0 in px and tm in px:
            tot = math.log(c_close / px[t0][3]) * 1e4
            part = math.log(px[tm][3] / px[t0][3]) * 1e4
            if abs(tot) > 1e-9:
                xs.append(100.0 * part / tot)
    m = sum(xs) / len(xs)
    print(f"  a H-{n:02d} min : {m:6.1f} % du mouvement de l'heure (n={len(xs)})")
