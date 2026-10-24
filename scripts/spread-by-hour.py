#!/usr/bin/env python3
"""
Spread by ET hour-of-day — the measurement behind the RealCostModel time-aware spec.

Input : data/oanda-ba/<SYM>_H1_BA.csv  (OANDA price=BA, H1, 2021-01 -> now)
Output: reports/<date>-spread-by-hour.txt

Why: RealCostModel.halfSpread(symbol) is a CONSTANT per symbol (measured 2026-10-01 on 500 H1
candles), while the 65e measured a factor 13 between the cheapest and the most expensive hour of
the day. Any leg filling inside [16,19) ET is therefore under-costed 2.5-3.4x.

Method (deliberately dumb, so it can be replayed):
  spread_bp(t) = (ask_c(t) - bid_c(t)) / mid_c(t) * 1e4     (full quoted spread at bar close)
  hour(t)      = hour of the bar's CLOSE in America/New_York  (label = start, so close = start+1h)
  multiplier   = median(spread_bp | hour=h) / median(spread_bp, all weekday bars)
  Mon-Fri only; bars with ask <= bid or bid <= 0 dropped; weekend carry bars excluded.

Controls printed:
  P0  the per-symbol median must reproduce the 2026-10-01 FEE-AUDIT medians within the era drift.
  WF  multiplier stability 2021-2023 vs 2024-2026 (a spec may only ship a STABLE multiplier).
"""
import os, sys, csv, statistics
from datetime import datetime, timezone, timedelta
from zoneinfo import ZoneInfo

NY = ZoneInfo("America/New_York")
DATA = os.path.expanduser("~/projects/tb-research/data/oanda-ba")
OUT = os.path.expanduser("~/projects/tb-research/reports")
TODAY = datetime.now().strftime("%Y-%m-%d")

# RealCostModel.HALF_SPREAD_PRICE -> full spread in price units, and the pip size used there.
MODEL = {
    "EUR_USD": (0.00008, 0.0001, 1.12456),
    "GBP_JPY": (0.0170,  0.01,   208.433),
    "USD_JPY": (0.0080,  0.01,   157.925),
    "AUD_USD": (0.000065, 0.0001, 0.692945),
    "USD_CHF": (0.000075, 0.0001, 0.83099),
    "GBP_USD": (None,    0.0001, 1.31978),   # not in HALF_SPREAD_PRICE — measured here as a candidate
    "XAU_USD": (0.265,   0.01,   4182.07),
}

# Buckets proposed to the cost model (ET hour of bar close). Chosen on the measured profile, not a priori.
BUCKETS = [
    ("[00,07) Asie",            range(0, 7)),
    ("[07,08) pre-Londres",     range(7, 8)),
    ("[08,12) Londres/overlap", range(8, 12)),
    ("[12,14) creux midi",      range(12, 14)),
    ("[14,16) NY apres-midi",   range(14, 16)),
    ("[16,17) derniere barre",  range(16, 17)),
    ("[17,18) apres le halt",   range(17, 18)),
    ("[18,19) post-rollover",   range(18, 19)),
    ("[19,24) soir",            range(19, 24)),
]


def load(sym):
    path = os.path.join(DATA, f"{sym}_H1_BA.csv")
    if not os.path.exists(path):
        return None
    rows = []
    with open(path) as f:
        r = csv.DictReader(f)
        for d in r:
            try:
                t = datetime.strptime(d["time"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
                bo, bc = float(d["bid_o"]), float(d["bid_c"])
                ac = float(d["ask_c"])
            except (KeyError, ValueError):
                continue
            if bo <= 0 or bc <= 0 or ac <= 0 or ac <= bc:
                continue
            # La cle est l'heure de DEBUT de la barre en ET : c'est le label OANDA, et c'est ce qui
            # rend l'intervalle lisible sans ambiguite. La barre [h, h+1) ET est celle dont le close
            # est a h+1 (le piege du 64e : confondre label et instant de cloture decale d'une heure).
            ny_start = t.astimezone(NY)
            rows.append((ny_start, (ac - bc) / ((ac + bc) / 2) * 1e4, t))
    return rows


def med(v):
    return statistics.median(v) if v else float("nan")


def main():
    out = []
    P = out.append
    P("=" * 110)
    P(f"SPREAD PAR HEURE ET — base du modele de cout sensible a l'heure  ({TODAY})")
    P("OANDA H1 price=BA. spread plein au close de barre, en bp du mid. Mon-Ven seulement.")
    P("Cle = heure ET de DEBUT de barre (label OANDA) ; la barre [h,h+1) ferme a h+1.")
    P("=" * 110)

    profiles = {}
    for sym in MODEL:
        rows = load(sym)
        if not rows:
            P(f"\n--- {sym}: DONNEES ABSENTES")
            continue
        rows = [r for r in rows if r[0].weekday() < 5]
        if not rows:
            continue
        prof = {h: [] for h in range(24)}
        prof_is = {h: [] for h in range(24)}
        prof_oos = {h: [] for h in range(24)}
        for ny, s, cutc in rows:
            prof[ny.hour].append(s)
            (prof_is if cutc.year <= 2023 else prof_oos)[ny.hour].append(s)
        allv = [s for h in range(24) for s in prof[h]]
        base = med(allv)
        profiles[sym] = (base, prof, allv, len(allv))

        P(f"\n--- {sym}   n={len(allv)}   MEDIANE plein spread = {base:.2f} bp "
          f"(demi = {base/2:.2f} bp/legue)   {rows[0][2].date()} -> {rows[-1][2].date()}")
        half, pip, mid = MODEL[sym]
        if half is not None:
            model_full = 2 * half / mid * 1e4      # plein spread RELATIF en bp du mid (pas en pips)
            P(f"    RealCostModel constant : demi={half} -> plein {model_full:.2f} bp a mid {mid} "
              f"; mesure/constante = {base/model_full:.2f}x")
        # la barre de cloture du vendredi : une population a part (le 64e/65e y a mesure 12-18 bp)
        fri_last = [s for ny, s, _ in rows if ny.weekday() == 4 and ny.hour == 16]
        other16 = [s for ny, s, _ in rows if ny.hour == 16 and ny.weekday() != 4]
        if fri_last and other16:
            P(f"    barre [16,17) ET : vendredi {med(fri_last):6.2f} bp (n={len(fri_last)}) vs "
              f"autres jours {med(other16):6.2f} bp (n={len(other16)}) -> x{med(fri_last)/med(other16):.2f}")
        P("    debut ET :" + "".join(f"{h:6d}" for h in range(24)))
        P("    bp   :" + "".join(f"{med(prof[h]):6.2f}" for h in range(24)))
        P("    x med:" + "".join(f"{(med(prof[h])/base if base else 0):6.2f}" for h in range(24)))
        P("    n    :" + "".join(f"{len(prof[h]):6d}" for h in range(24)))
        # WF : le multiplicateur est-il stable ?
        P("    x IS(21-23) / x OOS(24-26) :")
        P("      IS :" + "".join(f"{(med(prof_is[h])/med([s for x in range(24) for s in prof_is[x]])):6.2f}" for h in range(24)))
        P("      OOS:" + "".join(f"{(med(prof_oos[h])/med([s for x in range(24) for s in prof_oos[x]])):6.2f}" for h in range(24)))

    P("\n" + "=" * 110)
    P("TABLE DE DECISION — multiplicateur par tranche (mediane de la tranche / mediane du symbole)")
    P("=" * 110)
    hdr = f"{'TRANCHE':<28}" + "".join(f"{s:>12}" for s in profiles)
    P(hdr)
    for name, hours in BUCKETS:
        line = f"{name:<28}"
        for sym, (base, prof, allv, n) in profiles.items():
            vals = [s for h in hours for s in prof[h]]
            line += f"{(med(vals)/base if base and vals else float('nan')):>12.2f}"
        P(line)
    P("\nLecture : 1.00 = la constante actuelle est juste pour cette tranche ; 3.00 = toute jambe")
    P("qui entre ou sort dans cette tranche est SOUS-COSTEE d'un facteur 3.")

    # Effet sur les strategies deployees : quelles heures utilisent-elles ?
    P("\n" + "=" * 110)
    P("EFFET DIRECT SUR LES 5 STRATEGIES DEPLOYEES (symbole + heure de fill)")
    P("=" * 110)
    P("  Symboles deployes : EUR_USD (ltrsi3), USD_JPY (monthweekphase, compmomentum),")
    P("  USD_CHF (vwpreversion), GBP_JPY (consecbar). Les 4 sont mesures ci-dessus.")

    txt = "\n".join(out)
    print(txt)
    path = os.path.join(OUT, f"{TODAY}-spread-by-hour.txt")
    with open(path, "w") as f:
        f.write(txt + "\n")
    print(f"\n[ecrit] {path}")


if __name__ == "__main__":
    main()
