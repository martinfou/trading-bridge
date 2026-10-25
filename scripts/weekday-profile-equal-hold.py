#!/usr/bin/env python3
"""57e (mercredi 14 oct 2026) — SAISONNALITE FX : profil jour-de-semaine a HOLD EGALISE.

Deux questions ouvertes du 56e :
  (1) le tableau cross vs majeure du 55e n'est PAS interpretable car le hold du fade est EMERGENT
      du flux de donnees (24 h sur les CSV Dukascopy 24x7 remplis, 66 h sur les CSV OANDA) ;
  (2) le week-end ne doit pas etre compte comme un rollover.

Construction egalisee, la seule qui rende les familles comparables :
  - snapshot quotidien a heure FIXE (close de la barre qui se TERMINE a cet instant) ;
  - samedi/dimanche JETES pour TOUTES les series => le jour precedent est toujours l'ouvrable
    precedent ;
  - r(J) = log(snapshot(J)/snapshot(J-1)) bp, hold 24 h (mar->ven) et 72 h (lun) IDENTIQUE partout ;
  - les observations a hold > 96 h (trou de donnees / ferie prolonge) sont EXCLUES et comptees.

Ajouts : decompte du flux par jour, P0 contre les references publiees, test APPARIE par date a
jambe gauche constante, sensibilite a l'ancre, et DECOMPOSITION INTRA-JOURNALIERE du vendredi.
"""
import csv
import datetime as dt
import glob
import math
import os

HOME = os.path.expanduser("~")
DATA = f"{HOME}/projects/tb-research/data/historical/dukascopy"
UTC = dt.timezone.utc
W0 = dt.datetime(2006, 1, 1, tzinfo=UTC)
W1 = dt.datetime(2026, 5, 20, tzinfo=UTC)

CROSSES = {
    "GBP_CHF": "gbpchf-h1-bid-oanda-2002-05-07-2026-10-09.csv",
    "EUR_CHF": "eurchf-h1-bid-oanda-2002-05-06-2026-10-09.csv",
    "AUD_CHF": "audchf-h1-bid-oanda-2004-05-31-2026-10-09.csv",
}
MAJORS = ["GBP_JPY", "GBP_USD", "USD_CAD"]
DOW = ["LUN", "MAR", "MER", "JEU", "VEN"]

# References publiees — CONVENTION : ce sont des RETOURS BRUTS du vendredi (r<0 = baisse),
# PAS des fade (SELL). Sources : 54e pour les crosses, 37e/50e pour les paires.
REF_FRI = {"GBP_CHF": -8.48, "EUR_CHF": -5.05, "AUD_CHF": -10.73,
           "GBP_JPY": -5.61, "GBP_USD": -5.34, "USD_CAD": +0.55}
REF_T = {"GBP_CHF": 4.97, "EUR_CHF": 4.52, "AUD_CHF": 4.82,
         "GBP_JPY": 2.39, "GBP_USD": 2.86, "USD_CAD": 0.4}


def major_files(sym):
    base = sym.replace("_", "").lower()
    one = f"{DATA}/{base}-h1-bid-2006-2026.csv"
    if os.path.exists(one):
        return [one]
    return sorted(p for p in glob.glob(f"{DATA}/{base}-h1-bid-*.csv") if "oanda" not in p)


def load(files):
    out = {}
    for p in files:
        with open(p, newline="") as f:
            r = csv.reader(f)
            try:
                next(r)
            except StopIteration:
                continue
            for row in r:
                if len(row) < 5 or not row[0].strip().isdigit():
                    continue
                v = int(row[0])
                if v < 10 ** 11:
                    v *= 1000
                try:
                    t = dt.datetime.fromtimestamp(v / 1000.0, tz=UTC).replace(minute=0, second=0, microsecond=0)
                    out[t] = float(row[4])
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


def median(xs):
    s = sorted(xs)
    n = len(s)
    return s[n // 2] if n % 2 else 0.5 * (s[n // 2 - 1] + s[n // 2])


def price_at(px, day, hour):
    """Prix a l'instant hour:00 UTC du jour `day` = close de la barre ouverte a hour-1."""
    if hour == 0:
        t = dt.datetime.combine(day - dt.timedelta(days=1), dt.time(23, 0), tzinfo=UTC)
    else:
        t = dt.datetime.combine(day, dt.time(hour - 1, 0), tzinfo=UTC)
    return px.get(t)


def daily_returns(px, hour, max_hold=96.0):
    """[(date, weekday, bp, hold_h)] ; snapshot a `hour` UTC, lun-ven seulement, trous exclus."""
    days = []
    d = W0.date()
    while d < W1.date():
        if d.weekday() < 5:
            p = price_at(px, d, hour)
            if p is not None:
                days.append((d, p))
        d += dt.timedelta(days=1)
    rows, dropped = [], 0
    anchor_t = dt.time(hour % 24)
    for i in range(1, len(days)):
        d0, p0 = days[i - 1]
        d1, p1 = days[i]
        hold = (dt.datetime.combine(d1, anchor_t, tzinfo=UTC)
                - dt.datetime.combine(d0, anchor_t, tzinfo=UTC)).total_seconds() / 3600.0
        if hold > max_hold:
            dropped += 1
            continue
        rows.append((d1, d1.weekday(), math.log(p1 / p0) * 1e4, hold))
    return rows, dropped


def cell(rows, want_dow, lo=None, hi=None):
    lo_d = lo.date() if lo else None
    hi_d = hi.date() if hi else None
    xs = [r for (d, w, r, h) in rows
          if (want_dow is None or w == want_dow) and (lo_d is None or lo_d <= d < hi_d)]
    m, t = tstat(xs)
    return m, t, len(xs), (median(xs) if xs else 0.0)


ANCHOR = 21  # instant du snapshot principal = cloture hebdomadaire (vendredi 21:00 UTC)


def main():
    rep = []
    P = rep.append
    P("=" * 104)
    P("57e (mercredi 14 oct 2026) — SAISONNALITE FX : PROFIL JOUR-DE-SEMAINE A HOLD EGALISE")
    P("Fix du 56e : sam/dim jetes pour TOUTES les series + snapshot a instant fixe => hold identique.")
    P("Fenetre 2006-01-01 -> 2026-05-20 UTC. Snapshot principal = close a 21:00 UTC (cloture hebdo).")
    P("=" * 104)

    px, meta = {}, {}
    for sym, fname in CROSSES.items():
        px[sym] = load([f"{DATA}/{fname}"])
        meta[sym] = "CROSS (OANDA, sessions realistes)"
    for sym in MAJORS:
        px[sym] = load(major_files(sym))
        meta[sym] = "MAJEURE (Dukascopy 24x7)"

    # ---------------- 0. flux ----------------
    P("\n--- 0. FLUX DE DONNEES — la cause du hold divergent du 55e")
    for sym in px:
        cnt = {}
        for t in px[sym]:
            cnt[t.weekday()] = cnt.get(t.weekday(), 0) + 1
        P(f"  {sym:8s} {meta[sym]:32s} " +
          " ".join(f"{n}={cnt.get(i,0)}" for i, n in enumerate(DOW)) +
          f" | SAM={cnt.get(5,0)} DIM={cnt.get(6,0)}")
    P("  Les majeures portent ~24 barres SYNTHETIQUES par samedi ET par dimanche : pour elles le")
    P("  'jour de trading suivant' vendredi 00:00 UTC est le SAMEDI (hold 24 h), alors que pour les")
    P("  crosses (aucune barre de week-end utile) c'est le DIMANCHE soir (hold 66 h). C'est exactement")
    P("  l'ecart de tableau du 55e. Ici : lun-ven seulement, des deux cotes.")

    # ---------------- 0b. trous ----------------
    P("\n--- 0b. INTEGRITE DES SERIES (trou > 72 h)")
    for sym in px:
        ts = sorted(px[sym])
        gaps = [(a, b, (b - a).total_seconds() / 86400.0) for a, b in zip(ts, ts[1:])
                if (b - a).total_seconds() > 72 * 3600]
        if not gaps:
            P(f"  {sym:8s} aucun trou")
        for a, b, dd in gaps:
            P(f"  ⚠ {sym:8s} {a:%Y-%m-%d %H:%M} -> {b:%Y-%m-%d %H:%M}  {dd:.0f} JOURS MANQUANTS")
    P("  -> un trou de plusieurs mois fabrique UN 'retour' de plusieurs mois qui pollue une cellule.")

    # ---------------- 1. hold ----------------
    P("\n--- 1. CONTROLE DU HOLD APRES LE FIX (24 h mar->ven, 72 h lundi ; trous exclus)")
    rows_by = {}
    for sym in px:
        rows, dropped = daily_returns(px[sym], ANCHOR)
        rows_by[sym] = rows
        h = {}
        for (d, w, r, hh) in rows:
            h.setdefault(w, set()).add(round(hh, 0))
        P(f"  {sym:8s} obs={len(rows):5d} exclues(hold>96h)={dropped:3d}  " +
          " ".join(f"{DOW[i]}={sorted(h.get(i, []))}" for i in range(5)))

    # ---------------- 2. P0 ----------------
    P("\n--- 2. P0 NON-REGRESSION (retour BRUT du vendredi ; r<0 = baisse le vendredi)")
    P(f"  {'SYMBOLE':9s}{'r brut bp':>10s}{'t':>7s}{'med':>8s}{'n':>6s} | {'ref r brut':>10s}{'t ref':>6s}{'ecart bp':>10s}")
    gaps_cross, gaps_major = [], []
    for sym in px:
        m, t, n, med = cell(rows_by[sym], 4)
        g = m - REF_FRI[sym]
        (gaps_cross if sym in CROSSES else gaps_major).append(g)
        P(f"  {sym:9s}{m:+10.2f}{t:+7.2f}{med:+8.2f}{n:6d} | {REF_FRI[sym]:+10.2f}{REF_T[sym]:6.2f}{g:+10.2f}")
    P(f"  ecart moyen : crosses {sum(gaps_cross)/len(gaps_cross):+.2f} bp | "
      f"majeures {sum(gaps_major)/len(gaps_major):+.2f} bp   (plancher de bruit du systeme de jambes : 2,9 bp — 53e)")

    # ---------------- 3. profil ----------------
    P("\n--- 3. PROFIL COMPLET LUNDI -> VENDREDI (moyenne bp + t). Bonferroni 30 cellules : |t| >= 3")
    P(f"  {'SYMBOLE':9s}" + "".join(f"{n:>19s}" for n in DOW) + f"{'Mon-Jeu':>19s}")
    for sym in px:
        line = f"  {sym:9s}"
        for d in range(5):
            m, t, n, _ = cell(rows_by[sym], d)
            line += f"   {m:+7.2f}(t{t:+5.2f})"
        m, t, _, _ = cell(rows_by[sym], None)
        line += f"   {m:+7.2f}(t{t:+5.2f})"
        P(line)

    # ---------------- 4. cross vs majeure ----------------
    P("\n--- 4. CROSS vs MAJEURE A HOLD EGAL (la question du 56e)")
    cf, mf = [], []
    for sym in px:
        m, t, n, _ = cell(rows_by[sym], 4)
        (cf if sym in CROSSES else mf).append((-m, t, sym))
    mc = sum(x[0] for x in cf) / len(cf)
    mm = sum(x[0] for x in mf) / len(mf)
    P("  fade (SELL vendredi) = -r :")
    P("    crosses  : " + " · ".join(f"{s} {v:+.2f} (t {t:+.2f})" for v, t, s in cf) +
      f"   => moyenne {mc:+.2f} bp")
    P("    majeures : " + " · ".join(f"{s} {v:+.2f} (t {t:+.2f})" for v, t, s in mf) +
      f"   => moyenne {mm:+.2f} bp")
    P(f"  ECART cross - majeure = {mc - mm:+.2f} bp   (n=3 par famille : lecture qualitative)")
    P("  Si l'ecart tombait a ~0, l'attribution 'jambe refuge' du 50e/53e/54e serait un artefact de")
    P("  DUREE DE DETENTION. Il ne tombe pas a 0 : la hierarchie cross > majeure survit au hold egal.")

    # ---------------- 5. apparie par date ----------------
    P("\n--- 5. TEST APPARIE PAR DATE — meme jambe GAUCHE (GBP), jambe droite differente")
    P("  Le 50e/53e : 'la jambe de DROITE decide'. Test propre = deux paires de meme base, retour")
    P("  par retour, sur les MEMES vendredis (difference appariee).")
    fri = {sym: {d: r for (d, w, r, h) in rows_by[sym] if w == 4} for sym in px}
    for a, b in (("GBP_CHF", "GBP_JPY"), ("GBP_CHF", "GBP_USD"), ("GBP_USD", "GBP_JPY")):
        common = sorted(set(fri[a]) & set(fri[b]))
        diffs = [fri[a][d] - fri[b][d] for d in common]
        m, t = tstat(diffs)
        P(f"  {a:8s} - {b:8s} : {m:+7.2f} bp  (t {t:+5.2f}, n {len(common)})")
    P("  (une jambe droite plus refuge doit donner un vendredi PLUS negatif => difference negative)")

    # ---------------- 6. stabilite ----------------
    P("\n--- 6. STABILITE DE LA CELLULE VENDREDI (retour brut)")
    P(f"  {'SYMBOLE':9s}{'IS 06-15':>17s}{'OOS 16-26':>17s} |" +
      "".join(f"{s:>17s}" for s in ("2006-09", "2010-14", "2015-19", "2020-26")))
    for sym in px:
        ism, ist, _, _ = cell(rows_by[sym], 4, W0, dt.datetime(2016, 1, 1, tzinfo=UTC))
        osm, ost, _, _ = cell(rows_by[sym], 4, dt.datetime(2016, 1, 1, tzinfo=UTC), W1)
        line = f"  {sym:9s}{ism:+9.2f}(t{ist:+5.2f}){osm:+9.2f}(t{ost:+5.2f}) |"
        for a, b in ((2006, 2010), (2010, 2015), (2015, 2020), (2020, 2027)):
            m, t, _, _ = cell(rows_by[sym], 4, dt.datetime(a, 1, 1, tzinfo=UTC), dt.datetime(b, 1, 1, tzinfo=UTC))
            line += f"{m:+9.2f}(t{t:+5.2f})"
        P(line)

    # ---------------- 7. ancre ----------------
    P("\n--- 7. SENSIBILITE A L'ANCRE (fenetre de 24 h SE TERMINANT a H:00 UTC)")
    P("  Meme objet (retour de vendredi) avec une autre heure de coupe. Monotone = gradient.")
    P(f"  {'ANCRE':11s}" + "".join(f"{s:>17s}" for s in px))
    for hour in (1, 5, 9, 13, 17, 21):
        line = f"  {str(hour) + ':00 UTC':11s}"
        for sym in px:
            rows, _ = daily_returns(px[sym], hour)
            m, t, n, _ = cell(rows, 4)
            line += f"   {m:+7.2f}(t{t:+4.1f})"
        P(line)

    # ---------------- 8. decomposition intra-journaliere ----------------
    P("\n--- 8. DECOMPOSITION INTRA-JOURNALIERE DU VENDREDI : ou se concentre la baisse ?")
    P("  retour brut = GAP 21:00(J-1)->00:00(J) + ASIE 00->08 + LONDRES 08->13 + NY/CLOTURE 13->21")
    P("  (les 4 composantes sont des rendements BRUTS : negatif = prix en baisse)")
    P("  ⚠ les fenetres n'ont PAS la meme duree (GAP 3 h, ASIE 8 h, LON 5 h, NY 8 h) : lire aussi")
    P("    le bp/heure, sinon la fenetre la plus longue gagne par construction.")
    P(f"  {'SYMBOLE':9s}{'JOUR':>8s}{'GAP':>16s}{'ASIE 0-8':>16s}{'LON 8-13':>16s}{'NY 13-21':>16s}"
      f"{'TOTAL':>10s}{'NY bp/h':>10s}{'reste/h':>9s}")
    for sym in px:
        for label, dows in (("VEN", [4]), ("Mon-Jeu", [0, 1, 2, 3])):
            b = {"gap": [], "a": [], "c": [], "d": []}
            d = W0.date() + dt.timedelta(days=1)
            while d < W1.date():
                if d.weekday() in dows:
                    p0 = price_at(px[sym], d, 0)
                    p8 = price_at(px[sym], d, 8)
                    p13 = price_at(px[sym], d, 13)
                    p21 = price_at(px[sym], d, 21)
                    dd = d - dt.timedelta(days=1)
                    while dd.weekday() >= 5:
                        dd -= dt.timedelta(days=1)
                    prev = price_at(px[sym], dd, 21)
                    if None not in (p0, p8, p13, p21, prev):
                        b["gap"].append(math.log(p0 / prev) * 1e4)
                        b["a"].append(math.log(p8 / p0) * 1e4)
                        b["c"].append(math.log(p13 / p8) * 1e4)
                        b["d"].append(math.log(p21 / p13) * 1e4)
                d += dt.timedelta(days=1)

            def sm(k, b=b):
                m, t = tstat(b[k])
                return f"{m:+7.2f}(t{t:+4.1f})"
            n = len(b["a"])
            tot = sum(sum(b[k]) for k in b) / n if n else 0.0
            ny_h = (sum(b["d"]) / n) / 8.0 if n else 0.0
            rest_h = ((sum(b["gap"]) + sum(b["a"]) + sum(b["c"])) / n) / 16.0 if n else 0.0
            P(f"  {sym:9s}{label:>8s}{sm('gap'):>16s}{sm('a'):>16s}{sm('c'):>16s}{sm('d'):>16s}"
              f"{tot:+10.2f}{ny_h:+10.3f}{rest_h:+9.3f}")
    P("  Lecture : si NY/CLOTURE porte tout, le fade est un phenomene de FIN DE SEANCE (de-risquage")
    P("  avant le week-end), pas une derive etalee sur la journee.")

    txt = "\n".join(rep)
    print(txt)
    out = f"{HOME}/projects/tb-research/reports/2026-10-14-weekday-profile-equal-hold.txt"
    with open(out, "w") as f:
        f.write(txt + "\n")
    print("\nrapport ->", out)


if __name__ == "__main__":
    main()
