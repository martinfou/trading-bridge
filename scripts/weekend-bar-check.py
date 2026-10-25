#!/usr/bin/env python3
"""Pourquoi les deux familles ne font pas le meme trade : OHLC des barres de week-end.

Les CSV Dukascopy des majeurs (GBP_JPY/USD_CAD/GBP_USD) portent 24 barres par samedi et par
dimanche (24x7 synthetique), les CSV OANDA des crosses n'ont que le dimanche soir. Verifier :
  - part des barres sam/dim PLATES (high == low) = barres de carry, prix non tradeable ;
  - ecart entre le close de la derniere barre reelle du vendredi et l'open/close de la
    barre synthetique du samedi 00:00 UTC (= le prix de sortie que le moteur va utiliser).
"""
import csv
import datetime as dt
import os
import sys

HOME = os.path.expanduser("~")
DATA = f"{HOME}/projects/tb-research/data/historical"
UTC = dt.timezone.utc

FILES = {
    "GBP_CHF": "dukascopy/gbpchf-h1-bid-oanda-2002-05-07-2026-10-09.csv",
    "GBP_JPY": "dukascopy/gbpjpy-h1-bid-2006-01-01-2006-12-31.csv",
}


def rows(path, limit_year=None):
    out = []
    with open(path, newline="") as f:
        r = csv.reader(f)
        next(r)
        for row in r:
            if not row:
                continue
            t = dt.datetime.fromtimestamp(int(row[0]) / 1000.0, tz=UTC)
            if limit_year and t.year != limit_year:
                continue
            o, h, l, c = (float(x) for x in row[1:5])
            out.append((t, o, h, l, c))
    out.sort()
    return out


def analyse(sym, path, year):
    p = os.path.join(DATA, path)
    if not os.path.exists(p):
        print(f"[{sym}] absent {p}")
        return
    d = rows(p, year)
    we = [x for x in d if x[0].weekday() >= 5]
    flat = [x for x in we if x[2] == x[3]]
    print(f"[{sym}] {year} : {len(d)} barres, {len(we)} sam/dim, "
          f"{len(flat)}/{len(we)} PLATES (high==low)")
    # barres du samedi 00:00 UTC vs derniere barre reelle du vendredi (heure UTC)
    fri = [x for x in d if x[0].weekday() == 4]
    sat0 = [x for x in d if x[0].weekday() == 5 and x[0].hour == 0]
    ecarts = []
    for s in sat0[:60]:
        cand = [f for f in fri if f[0] < s[0]]
        if not cand:
            continue
        f = cand[-1]
        ecarts.append((s[0].date(), f[0].hour, abs(s[4] - f[4]) / f[4] * 1e4))
    if ecarts:
        m = sum(e[2] for e in ecarts) / len(ecarts)
        print(f"        |close(sam 00:00) - close(derniere barre vendredi)| : moyenne {m:.3f} bp "
              f"sur {len(ecarts)} semaines (max {max(e[2] for e in ecarts):.2f} bp)")
        print(f"        exemple : vendredi derniere barre {ecarts[0][1]}h UTC -> samedi 00:00, "
              f"ecart {ecarts[0][2]:.3f} bp")


if __name__ == "__main__":
    for sym, path in FILES.items():
        analyse(sym, path, 2011)
