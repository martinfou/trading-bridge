#!/usr/bin/env python3
"""Diagnostic P0 : pourquoi ce harnais ne reproduit pas EXACTEMENT la table du 59e ?
Attribue l'ecart entre trois definitions de la sortie du vendredi :
  S1 = 59e : derniere barre du jour (max hour, y compris barres PLATES), close de cette barre
  S2 = derniere barre REELLE (high > low) du vendredi, close
  S3 = idem S2 mais en exigeant une reouverture reelle dans les 5 jours (le set de ce harnais)
"""
import csv, datetime as dt, glob, math, os

HOME = os.path.expanduser("~")
DATA = f"{HOME}/projects/tb-research/data/historical/dukascopy"
UTC = dt.timezone.utc
FILES = {
    "GBP_CHF": "gbpchf-h1-bid-oanda-2002-05-07-2026-10-09.csv",
    "EUR_CHF": "eurchf-h1-bid-oanda-2002-05-06-2026-10-09.csv",
    "AUD_CHF": "audchf-h1-bid-oanda-2004-05-31-2026-10-09.csv",
}
COST = {"GBP_CHF": 2.278, "EUR_CHF": 1.702, "AUD_CHF": 2.584}


def load(path):
    out = {}
    with open(path, newline="") as f:
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
                out[t] = (float(row[1]), float(row[2]), float(row[3]), float(row[4]))
            except (ValueError, OverflowError, OSError):
                continue
    return out


def tstat(xs):
    n = len(xs)
    if n < 3:
        return 0.0, 0.0
    m = sum(xs) / n
    sd = (sum((x - m) ** 2 for x in xs) / (n - 1)) ** 0.5
    return m, (m / (sd / n ** 0.5) if sd > 0 else 0.0)


for sym, fname in FILES.items():
    o = load(f"{DATA}/{fname}")
    byday = {}
    for t in o:
        byday.setdefault(t.date(), []).append(t)
    s1, s2, s3, h_dist = [], [], [], {}
    n_flat_last = 0
    for d, bars in sorted(byday.items()):
        if d.weekday() != 4:
            continue
        bars.sort()
        t13 = dt.datetime.combine(d, dt.time(12, 0), tzinfo=UTC)
        if t13 not in o:
            continue
        p13 = o[t13][3]
        H_all = bars[-1]
        h_dist[H_all.hour] = h_dist.get(H_all.hour, 0) + 1
        real = [t for t in bars if o[t][1] > o[t][2]]
        if not real:
            continue
        H_real = real[-1]
        if o[H_all][1] == o[H_all][2]:
            n_flat_last += 1
        for lst, th in ((s1, H_all), (s2, H_real)):
            c = o[th][3]
            if p13 > 0 and c > 0:
                lst.append(-math.log(c / p13) * 1e4)
        # S3 : exiger une reouverture reelle dans les 5 jours
        ok = any(o[t][1] > o[t][2] and t > H_real for t in sorted(o) if H_real < t <= H_real + dt.timedelta(days=5))
        if ok:
            c = o[H_real][3]
            if p13 > 0 and c > 0:
                s3.append(-math.log(c / p13) * 1e4)
    print(f"\n{sym}  cout {COST[sym]:.3f}")
    for lab, xs in (("S1 59e (derniere barre, plate incluse)", s1),
                    ("S2 derniere barre REELLE", s2),
                    ("S3 = S2 + reouverture exigee", s3)):
        m, t = tstat(xs)
        print(f"  {lab:38s} n={len(xs):5d}  brut {m:+7.2f} (t {t:+5.2f})  net {m-COST[sym]:+7.2f}")
    print(f"  vendredis dont la DERNIERE barre est PLATE : {n_flat_last}")
    print("  distribution de l'heure de la derniere barre (toutes, plates incluses) : " +
          " ".join(f"h{h}:{n}" for h, n in sorted(h_dist.items()) if n > 5))
