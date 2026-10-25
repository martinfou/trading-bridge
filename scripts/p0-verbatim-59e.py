#!/usr/bin/env python3
"""Replique VERBATIM le bloc 13 du harnais du 59e et attribue l'ecart de sample (n=1057 vs 1130)."""
import csv, datetime as dt, math, os, statistics as st

HOME = os.path.expanduser("~")
DATA = f"{HOME}/projects/tb-research/data/historical/dukascopy"
UTC = dt.timezone.utc
FILES = {
    "GBP_CHF": "gbpchf-h1-bid-oanda-2002-05-07-2026-10-09.csv",
    "EUR_CHF": "eurchf-h1-bid-oanda-2002-05-06-2026-10-09.csv",
    "AUD_CHF": "audchf-h1-bid-oanda-2004-05-31-2026-10-09.csv",
}
COST = {"GBP_CHF": 2.278, "EUR_CHF": 1.702, "AUD_CHF": 2.584}


def load(p):
    out = {}
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
                t = dt.datetime.fromtimestamp(v / 1000.0, tz=UTC).replace(
                    minute=0, second=0, microsecond=0)
                out[t] = float(row[4])
            except (ValueError, OverflowError, OSError):
                continue
    return out


def price_at(px, day, hour):
    if hour == 0:
        t = dt.datetime.combine(day - dt.timedelta(days=1), dt.time(23, 0), tzinfo=UTC)
    else:
        t = dt.datetime.combine(day, dt.time(hour - 1, 0), tzinfo=UTC)
    return px.get(t)


def tstat(xs):
    n = len(xs)
    m = sum(xs) / n
    sd = (sum((x - m) ** 2 for x in xs) / (n - 1)) ** 0.5
    return m, m / (sd / n ** 0.5)


for sym, fname in FILES.items():
    px = load(f"{DATA}/{fname}")
    byday = {}
    for t in px:
        byday.setdefault(t.date(), []).append(t.hour)
    lastH = {d: max(hs) for d, hs in byday.items() if d.weekday() == 4}
    xs, miss13, missH = [], 0, 0
    for d, H in lastH.items():
        a, b = price_at(px, d, 13), price_at(px, d, H + 1)
        if a is None:
            miss13 += 1
        if b is None:
            missH += 1
        if a and b and a > 0:
            xs.append(-math.log(b / a) * 1e4 - COST[sym])
    m, t = tstat(xs)
    print(f"{sym}: n={len(xs)}  net {m:+.2f} (t {t:+.2f})  | vendredis={len(lastH)} "
          f"prix 13:00 manquant={miss13}  prix sortie manquant={missH}")
    # meme chose mais en exigant une barre REELLE pour la sortie
    print(f"   heures de derniere barre : " +
          " ".join(f"h{h}:{sum(1 for v in lastH.values() if v == h)}"
                   for h in sorted(set(lastH.values())) if sum(1 for v in lastH.values() if v == h) > 5))
