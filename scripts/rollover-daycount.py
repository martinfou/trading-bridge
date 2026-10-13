#!/usr/bin/env python3
"""Rollover day-count audit — 13 oct 2026 (56e, mardi = VARIATION).

Question : la convention de rollover du moteur (SwapCalculator.countRollovers / yearlySwap)
facture-t-elle le bon nombre de jours de swap pour un hold vendredi -> lundi ?

Le moteur compte CHAQUE jour calendaire traverse a 17:00 ET (+3 pour le mercredi).
Le marche interbancaire FX (spot T+2) ne facture PAS samedi/dimanche : le week-end est
facture le MERCREDI (roll du mercredi = value date vendredi -> lundi = 3 jours), et un
hold vendredi -> lundi coute donc exactement 1 jour.

Ce script reconstruit les instants d'entree/sortie EXACTS du fade du vendredi
(GoldWeekdayEffectStrategy : entree = 1re barre du jour UTC cible, sortie = 1re barre
d'un jour UTC different) et compare, evenement par evenement :
  - jours factures par le MOTEUR (regle countRollovers)
  - jours factures par le MARCHE (lun-ven seulement, mercredi = 3)
Sortie : distribution des ratios + ratio agrege pondere par le carry reel de l'annee.
"""
import csv
import datetime as dt
import os
import sys
from collections import Counter

HOME = os.path.expanduser("~")
DATA = f"{HOME}/projects/tb-research/data/historical"
NY = "America/New_York"

try:
    from zoneinfo import ZoneInfo
except ImportError:
    print("zoneinfo manquant")
    sys.exit(1)
TZ_NY = ZoneInfo(NY)
UTC = dt.timezone.utc

ROLLOVER_HOUR = 17  # 17:00 ET


# ---------------------------------------------------------------- data
# Les 3 crosses refuge : CSV OANDA (sessions realistes, dimanche soir seulement).
CROSS_CSV = {
    "GBP_CHF": "dukascopy/gbpchf-h1-bid-oanda-2002-05-07-2026-10-09.csv",
    "EUR_CHF": "dukascopy/eurchf-h1-bid-oanda-2002-05-06-2026-10-09.csv",
    "AUD_CHF": "dukascopy/audchf-h1-bid-oanda-2004-05-31-2026-10-09.csv",
}
# Incumbents + controle : CSV Dukascopy (24x7, barres de week-end synthetiques).
INCUMBENTS = ["GBP_JPY", "GBP_USD", "USD_CAD"]


def parse_ts(raw):
    """Accepte epoch ms (CSV OANDA/Dukascopy du repo), ISO8601, et 'YYYY.MM.DD HH:MM'."""
    raw = raw.strip().replace("\ufeff", "")
    if not raw:
        return None
    if raw.isdigit():
        v = int(raw)
        if v > 10**11:      # millisecondes
            return dt.datetime.fromtimestamp(v / 1000.0, tz=UTC)
        return dt.datetime.fromtimestamp(v, tz=UTC)   # secondes
    if raw[0].isdigit() and "-" in raw[:10]:
        s = raw.replace("Z", "+00:00")
        try:
            d = dt.datetime.fromisoformat(s)
        except ValueError:
            return None
    else:
        for fmt in ("%Y.%m.%d %H:%M:%S", "%Y.%m.%d %H:%M", "%Y.%m.%d,%H:%M"):
            try:
                d = dt.datetime.strptime(raw, fmt).replace(tzinfo=UTC)
                break
            except ValueError:
                d = None
        if d is None:
            return None
    if d.tzinfo is None:
        d = d.replace(tzinfo=UTC)
    return d.astimezone(UTC)


def files_for(sym):
    if sym in CROSS_CSV:
        return [os.path.join(DATA, CROSS_CSV[sym])]
    # incumbents : le moteur charge par millesime (loadYearSpec) ou agrege
    base = f"dukascopy/{sym.replace('_','').lower()}-h1-bid-"
    p = os.path.join(DATA, base + "2006-2026.csv")
    if os.path.exists(p):
        return [p]
    import glob as _g
    return sorted(x for x in _g.glob(os.path.join(DATA, base + "*.csv")) if "oanda" not in x)


def load(paths):
    if isinstance(paths, str):
        paths = [paths]
    out = set()
    for path in paths:
        with open(path, newline="") as f:
            r = csv.reader(f)
            head = next(r)
            ti = 0
            for i, h in enumerate(head):
                if "time" in h.lower() or "date" in h.lower():
                    ti = i
                    break
            for row in r:
                if not row:
                    continue
                t = parse_ts(row[ti])
                if t:
                    out.add(t)
    return sorted(out)


# ---------------------------------------------------------------- conventions
def engine_days(entry, exit_):
    """Replique SwapCalculator.countRollovers : chaque jour calendaire a 17:00 ET, mercredi = 3."""
    e = entry.astimezone(TZ_NY)
    x = exit_.astimezone(TZ_NY)
    cur = e.replace(hour=ROLLOVER_HOUR, minute=0, second=0, microsecond=0)
    if cur < e:
        cur += dt.timedelta(days=1)
    total = 0
    while cur < x:
        total += 3 if cur.weekday() == 2 else 1  # WEDNESDAY = 2
        cur += dt.timedelta(days=1)
    return total


def market_days(entry, exit_):
    """Convention spot FX T+2 : rolls lun-ven seulement ; le mercredi porte le week-end (3 jours)."""
    e = entry.astimezone(TZ_NY)
    x = exit_.astimezone(TZ_NY)
    cur = e.replace(hour=ROLLOVER_HOUR, minute=0, second=0, microsecond=0)
    if cur < e:
        cur += dt.timedelta(days=1)
    total = 0
    while cur < x:
        if cur.weekday() < 5:  # MON..FRI
            total += 3 if cur.weekday() == 2 else 1
        cur += dt.timedelta(days=1)
    return total


# ---------------------------------------------------------------- carry table (47e / 55e)
DIFF = {
    "GBP_USD": [-0.304, +0.734, +2.546, +0.658, +0.388, +0.571, +0.545, +0.346, +0.419, +0.347, -0.145, -0.794, -1.466, -1.400, -0.239, -0.024, -0.227, -0.227, -0.049, -0.047, -0.025],
    "AUD_USD": [+0.835, +1.399, +4.008, +2.878, +4.369, +4.544, +3.446, +2.611, +2.553, +2.029, +1.342, +0.587, -0.240, -0.863, -0.277, -0.086, -0.591, -1.198, -0.660, -0.347, +0.533],
    "USD_CHF": [+3.742, +2.925, +1.124, +0.462, +0.162, +0.246, +0.344, +0.214, +0.123, +0.951, +1.413, +2.000, +2.995, +2.981, +1.231, +0.850, +2.405, +3.656, +3.809, +4.084, +3.777],
    "USD_JPY": [+4.829, +4.522, +2.118, -0.023, -0.072, -0.029, -0.045, -0.070, -0.081, +0.057, +0.571, +1.093, +2.116, +2.176, +0.569, +0.180, +2.257, +5.174, +4.865, +3.358, +2.449],
    "USD_CAD": [+1.144, +1.126, +0.628, +0.224, -0.250, -0.608, -0.659, -0.799, -0.785, -0.301, +0.158, +0.464, +0.815, +0.552, +0.082, +0.001, +0.050, +0.437, +0.684, +1.592, +1.488],
    "EUR_USD": [-2.074, -0.991, +1.669, +0.672, +0.499, +1.087, +0.291, +0.054, +0.086, -0.246, -0.908, -1.482, -2.510, -2.564, -0.959, -0.663, -1.886, -1.747, -1.480, -1.993, -1.707],
}


def diff_series(sym):
    base, quote = sym.split("_")
    if quote == "CHF":
        a, b = DIFF[base + "_USD"], DIFF["USD_CHF"]
        return [x + y for x, y in zip(a, b)]
    if quote == "JPY":
        a, b = DIFF[base + "_USD"], DIFF["USD_JPY"]
        return [x + y for x, y in zip(a, b)]
    return DIFF[sym]


def diff_year(sym, year):
    d = diff_series(sym)
    i = year - 2006
    if 0 <= i < len(d):
        return d[i]
    return d[-1]


# ---------------------------------------------------------------- main
W0 = dt.datetime(2006, 1, 1, tzinfo=UTC)
W1 = dt.datetime(2026, 5, 20, tzinfo=UTC)


def analyse(sym, paths):
    missing = [p for p in paths if not os.path.exists(p)]
    if missing or not paths:
        print(f"[{sym}] FICHIER ABSENT {missing or '-'}")
        return None
    ts = load(paths)
    ts = [t for t in ts if W0 <= t < W1]
    if not ts:
        print(f"[{sym}] fenetre vide")
        return None
    dow = Counter(t.weekday() for t in ts)
    print(f"[data] {sym}: {len(ts)} barres {ts[0]} -> {ts[-1]}")
    print(f"       lun..dim = " + " ".join(str(dow.get(i, 0)) for i in range(7)))

    # entree = 1re barre du vendredi UTC ; sortie = 1re barre d'un jour UTC != vendredi
    events = []
    i = 1
    while i < len(ts):
        cur, prev = ts[i], ts[i - 1]
        if cur.astimezone(UTC).date() != prev.astimezone(UTC).date():
            if prev.astimezone(UTC).weekday() == 4:  # FRIDAY
                # 1re barre du vendredi = derniere transition vers vendredi
                # (on remonte : la barre de debut de vendredi est celle qui suit le jeudi)
                j = i - 1
                while j > 0 and ts[j - 1].astimezone(UTC).date() == prev.astimezone(UTC).date():
                    j -= 1
                entry = ts[j]
                h_hold = cur - entry
                events.append((entry, cur, h_hold))
        i += 1

    if not events:
        print(f"[{sym}] aucun vendredi")
        return None

    ratio = Counter()
    eng_tot = real_tot = 0
    eng_w = real_w = 0.0
    exceptions = []
    for entry, exit_, h in events:
        e_days, m_days = engine_days(entry, exit_), market_days(entry, exit_)
        eng_tot += e_days
        real_tot += m_days
        d = abs(diff_year(sym, entry.astimezone(TZ_NY).year))
        eng_w += e_days * d
        real_w += m_days * d
        ratio[f"{e_days}->{m_days}"] += 1
        if (e_days, m_days) != (3, 1):
            exceptions.append((entry, exit_, e_days, m_days))

    agg = real_w / eng_w if eng_w else float("nan")
    print(f"       evenements={len(events)}  jours moteur={eng_tot}  jours marche={real_tot}")
    print(f"       distribution moteur->marche : {dict(ratio)}")
    print(f"       ratio agrege pondere carry  : {agg:.5f}")
    if exceptions:
        for entry, exit_, e_days, m_days in exceptions[:6]:
            print(f"       ⚠ {entry} -> {exit_} ({e_days}->{m_days}) {'' if len(exceptions) <= 6 else ''}")
    # hold moyen
    hours = [h.total_seconds() / 3600.0 for _, _, h in events]
    print(f"       hold moyen {sum(hours)/len(hours):.1f} h  (min {min(hours):.0f} / max {max(hours):.0f})")
    return agg


def sanity():
    print("=== CONTROLE ARITHMETIQUE DES DEUX CONVENTIONS ===")
    cases = [
        ("vendredi 16:00 ET -> lundi 04:00 ET", dt.datetime(2024, 5, 3, 20, 0, tzinfo=UTC), dt.datetime(2024, 5, 6, 8, 0, tzinfo=UTC)),
        ("vendredi 00:00 UTC -> dimanche 21:00 UTC", dt.datetime(2024, 5, 3, 0, 0, tzinfo=UTC), dt.datetime(2024, 5, 5, 21, 0, tzinfo=UTC)),
        ("semaine complete lun -> lun", dt.datetime(2024, 5, 6, 0, 0, tzinfo=UTC), dt.datetime(2024, 5, 13, 0, 0, tzinfo=UTC)),
        ("lundi -> mardi (1 jour)", dt.datetime(2024, 5, 6, 12, 0, tzinfo=UTC), dt.datetime(2024, 5, 7, 12, 0, tzinfo=UTC)),
        ("mercredi -> jeudi (triple)", dt.datetime(2024, 5, 8, 12, 0, tzinfo=UTC), dt.datetime(2024, 5, 9, 12, 0, tzinfo=UTC)),
    ]
    for label, a, b in cases:
        print(f"  {label:42s} moteur={engine_days(a,b):2d}  marche={market_days(a,b):2d}")


if __name__ == "__main__":
    sanity()
    print()
    print("=== FADE DU VENDREDI : JOURS DE SWAP FACTURES (2006-01 -> 2026-05) ===")
    out = {}
    for sym in list(CROSS_CSV) + INCUMBENTS:
        agg = analyse(sym, files_for(sym))
        if agg is not None:
            out[sym] = agg
    print()
    print("=== RATIOS (carry corrige = carry moteur x ratio) ===")
    for s, a in out.items():
        print(f"  {s:9s} {a:.5f}   (1/3 = 0.33333, 1/2 = 0.5)")
