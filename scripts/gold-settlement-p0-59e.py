#!/usr/bin/env python3
"""62e P0 — VERIFICATION INDEPENDANTE DU CHIFFRE DU 59e (loi du 60e : rejouer chaque table publiee).

Le 59e publie, sur les crosses refuge OANDA H1 bid : k=0 vendredi = -3.35 / -1.93 / -6.16 bp
(GBP_CHF / EUR_CHF / AUD_CHF), et Mon-Jeu +1.28 / +0.88 / +1.94, avec k=0 = "derniere barre REELLE
avant la cloture" (h20 UTC en ete, h21 en hiver).

Le 62e mesure la barre [16,17) ET (= h20/h21 UTC) : +4.88 sur GBP_CHF le vendredi (2016+).
Les deux ne peuvent pas etre vrais. On rejoue les DEUX conventions, sur le FULL et sur 2016+.
"""
import csv, datetime as dt, glob, math, os

DATA = os.path.expanduser("~/projects/tb-research/data/historical/dukascopy")
UTC = dt.timezone.utc
CROSSES = {"GBP_CHF": "gbpchf-h1-bid-oanda-2002-05-07-2026-10-09.csv",
           "EUR_CHF": "eurchf-h1-bid-oanda-2002-05-06-2026-10-09.csv",
           "AUD_CHF": "audchf-h1-bid-oanda-2004-05-31-2026-10-09.csv"}


def load(fname):
    out = {}
    with open(f"{DATA}/{fname}", newline="") as f:
        r = csv.reader(f); next(r, None)
        for row in r:
            if len(row) < 5 or not row[0].strip().isdigit(): continue
            v = int(row[0]); v = v * 1000 if v < 10 ** 11 else v
            try:
                t = dt.datetime.fromtimestamp(v / 1000.0, tz=UTC).replace(minute=0, second=0, microsecond=0)
                out[t] = tuple(float(x) for x in row[1:5])
            except (ValueError, OverflowError, OSError): continue
    return out


def dst(y):
    def sun(m, n):
        d = dt.date(y, m, 1); c = 0
        while True:
            if d.weekday() == 6:
                c += 1
                if c == n: return d
            d += dt.timedelta(days=1)
    return sun(3, 2), sun(11, 1)


def settle_hour(d):
    a, b = dst(d.year)
    return 20 if a <= d < b else 21


def tstat(xs):
    n = len(xs)
    if n < 3: return 0.0, 0.0
    m = sum(xs) / n
    sd = (sum((x - m) ** 2 for x in xs) / (n - 1)) ** 0.5
    return m, (m / (sd / n ** 0.5) if sd > 0 else 0.0)


def fade(o, c):
    return -math.log(c / o) * 1e4 if o > 0 and c > 0 else None


print("=" * 122)
print("62e P0 — REJEU DU CHIFFRE DU 59e SUR SES PROPRES FICHIERS (crosses refuge OANDA H1 bid)")
print("Convention A = 'derniere barre REELLE du jour' (celle du 59e) | Convention B = barre [16,17) ET exacte")
print("FADE = SELL ; fade > 0 = SELL gagnant (le prix baisse dans la barre)")
print("=" * 122)
for sym, fname in CROSSES.items():
    ohlc = load(fname)
    real = {t: v for t, v in ohlc.items() if v[1] > v[2]}
    byday = {}
    for t in real: byday.setdefault(t.date(), []).append(t)
    A_mj, A_vn, B_mj, B_vn = [], [], [], []
    for d, ts in byday.items():
        if d.weekday() >= 5: continue
        ts = sorted(ts)
        # A : derniere barre reelle du jour
        a = fade(ohlc[ts[-1]][0], ohlc[ts[-1]][3])
        # B : barre [16,17) ET
        tb = dt.datetime.combine(d, dt.time(settle_hour(d), 0), tzinfo=UTC)
        b = fade(ohlc[tb][0], ohlc[tb][3]) if tb in real else None
        (A_vn if d.weekday() == 4 else A_mj).append((d, a))
        (B_vn if d.weekday() == 4 else B_mj).append((d, b))
    print(f"\n  {sym}")
    for lab, rows in (("A derniere barre reelle", (A_mj, A_vn)), ("B barre [16,17) ET      ", (B_mj, B_vn))):
        for per, lo, hi in (("FULL   ", dt.date(1990, 1, 1), dt.date(2030, 1, 1)),
                            ("2016+  ", dt.date(2016, 1, 1), dt.date(2030, 1, 1)),
                            ("avant16", dt.date(1990, 1, 1), dt.date(2016, 1, 1))):
            mj = [v for d, v in rows[0] if v is not None and lo <= d < hi]
            vn = [v for d, v in rows[1] if v is not None and lo <= d < hi]
            m1, t1 = tstat(mj); m2, t2 = tstat(vn)
            print(f"    {lab} {per}  lun-jeu {m1:+7.2f}(t{t1:+5.1f}) n={len(mj):5d}"
                  f"   |  vendredi {m2:+7.2f}(t{t2:+5.1f}) n={len(vn):4d}"
                  f"   |  ven - lun-jeu {m2 - m1:+6.2f}")
