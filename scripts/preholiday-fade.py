#!/usr/bin/env python3
"""61e (lundi 19 oct 2026, IDEE NOUVELLE) — LE FADE EXISTE-T-IL AUSSI LA VEILLE D'UN CONGE DE MARCHE US ?

Idee nouvelle (jamais testee dans le pipeline) : le fade refuge du vendredi (37e -> 60e) est un
risk-off de JAMBES devant un TROU DE LIQUIDITE (le week-end). Si le mecanisme est le trou et pas
le vendredi, il doit se reproduire la veille d'un congé de marche US :
  - conge le lundi  -> session veille = VENDREDI, trou de 4 jours (plus long qu'un week-end)
  - conge en semaine -> session veille = MAR/MER/JEU, trou de 2 jours
  - Good Friday     -> session veille = JEUDI (FX mince le vendredi, NYSE ferme)

Prediction falsifiable (gradient) : si l'effet suit la LONGUEUR du trou, le fade de la derniere
heure doit etre PLUS fort la veille d'un long week-end (trou 4 j) qu'un vendredi ordinaire (3 j),
et plus fort la veille d'un conge en semaine (2 j) qu'un jour ordinaire (1 j).
Si l'effet est specifiquement "vendredi", il doit etre ABSENT la veille des conges en semaine.

Conventions (lois du 57e/59e/60e) :
  - FADE = SELL du cross ; fade > 0 = SELL gagnant. Unite = bp. Signe ecrit dans chaque tableau.
  - k=0 = la DERNIERE barre REELLE (high > low) avant la cloture FX ; k=1..3 = les precedentes.
  - cloture = derniere barre REELLE du jour (pas une heure UTC fixe : loi du 59e).
  - controle apparié par JOUR DE SEMAINE : chaque session PRE est demoyennee par la moyenne des
    sessions ordinaires DU MEME JOUR (loi du 59e : le drift de fin de journee existe tous les jours).
  - specificity : sessions POST-conge et session N-1 (avant la veille) doivent etre plates.
  - IS/OOS 2006-2015 / 2016-2026, eres, mediane, hit, TRIM 5%.
  - cout = demi-spreads MESURES du 55e (A/R) ; pas de carry (hold intra-journalier).
Aucune ecriture dans le repo Java. Donnees : 3 crosses refuge OANDA H1 bid + 3 majeures Dukascopy H1 bid.
"""
import csv
import datetime as dt
import glob
import math
import os
import statistics as st

HOME = os.path.expanduser("~")
DATA = f"{HOME}/projects/tb-research/data/historical/dukascopy"
UTC = dt.timezone.utc

CROSSES = {
    "GBP_CHF": "gbpchf-h1-bid-oanda-2002-05-07-2026-10-09.csv",
    "EUR_CHF": "eurchf-h1-bid-oanda-2002-05-06-2026-10-09.csv",
    "AUD_CHF": "audchf-h1-bid-oanda-2004-05-31-2026-10-09.csv",
}
MAJORS = ["GBP_JPY", "GBP_USD", "USD_CAD"]
DOW = ["LUN", "MAR", "MER", "JEU", "VEN"]
COST_BP = {"GBP_CHF": 2.278, "EUR_CHF": 1.702, "AUD_CHF": 2.584,
           "GBP_JPY": 1.630, "GBP_USD": 2.140, "USD_CAD": 2.140}
ERAS = [(2006, 2010), (2010, 2015), (2015, 2020), (2020, 2027)]


# ------------------------------------------------------------------ chargement
def major_files(sym):
    base = sym.replace("_", "").lower()
    one = f"{DATA}/{base}-h1-bid-2006-2026.csv"
    if os.path.exists(one):
        return [one]
    return sorted(p for p in glob.glob(f"{DATA}/{base}-h1-bid-*.csv") if "oanda" not in p)


def load_ohlc(paths):
    out = {}
    for path in paths:
        with open(path, newline="") as f:
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
                    t = dt.datetime.fromtimestamp(v / 1000.0, tz=UTC).replace(
                        minute=0, second=0, microsecond=0)
                    out[t] = (float(row[1]), float(row[2]), float(row[3]), float(row[4]))
                except (ValueError, OverflowError, OSError):
                    continue
    return out


# ------------------------------------------------------------------ stats
def tstat(xs):
    n = len(xs)
    if n < 3:
        return 0.0, 0.0
    m = sum(xs) / n
    sd = (sum((x - m) ** 2 for x in xs) / (n - 1)) ** 0.5
    return m, (m / (sd / n ** 0.5) if sd > 0 else 0.0)


def tstat0(xs):
    """t d'une moyenne contre zero (meme chose que tstat, nom explicite)."""
    return tstat(xs)


def trim(xs, frac=0.05):
    if len(xs) < 40:
        return xs
    k = max(1, int(frac * len(xs)))
    return sorted(xs)[k:len(xs) - k]


def welch(a, b):
    """Difference de deux moyennes independantes (Welch) : (diff, t)."""
    if len(a) < 3 or len(b) < 3:
        return 0.0, 0.0
    ma, mb = sum(a) / len(a), sum(b) / len(b)
    va = sum((x - ma) ** 2 for x in a) / (len(a) - 1)
    vb = sum((x - mb) ** 2 for x in b) / (len(b) - 1)
    se = (va / len(a) + vb / len(b)) ** 0.5
    return ma - mb, ((ma - mb) / se if se > 0 else 0.0)


# ------------------------------------------------------------------ calendrier US
def nth_weekday(year, month, weekday, n):
    d = dt.date(year, month, 1)
    c = 0
    while d.month == month:
        if d.weekday() == weekday:
            c += 1
            if c == n:
                return d
        d += dt.timedelta(days=1)
    raise ValueError("nth_weekday")


def last_weekday(year, month, weekday):
    d = dt.date(year, month + 1, 1) - dt.timedelta(days=1) if month < 12 else dt.date(year, 12, 31)
    while d.weekday() != weekday:
        d -= dt.timedelta(days=1)
    return d


def easter(year):
    a = year % 19
    b, c = divmod(year, 100)
    d, e = divmod(b, 4)
    f = (b + 8) // 25
    g = (b - f + 1) // 3
    h = (19 * a + b - d - g + 15) % 30
    i, k = divmod(c, 4)
    l = (32 + 2 * e + 2 * i - h - k) % 7
    m = (a + 11 * h + 22 * l) // 451
    month = (h + l - 7 * m + 114) // 31
    day = ((h + l - 7 * m + 114) % 31) + 1
    return dt.date(year, month, day)


def observed(d):
    if d.weekday() == 5:
        return d - dt.timedelta(days=1)
    if d.weekday() == 6:
        return d + dt.timedelta(days=1)
    return d


def us_closures(y0=2006, y1=2026):
    """Fermetures PLEINES du NYSE. Retourne {date: (nom, type)}.
    type: 'long_weekend' (conge lundi ou vendredi -> week-end de 3 j)
          'midweek'      (conge mar/mer/jeu)
          'good_friday'  (NYSE ferme, FX ouvert mais mince)"""
    out = {}
    for y in range(y0, y1 + 1):
        items = [
            (observed(dt.date(y, 1, 1)), "New Year"),
            (nth_weekday(y, 1, 0, 3), "MLK"),
            (nth_weekday(y, 2, 0, 3), "Presidents"),
            (easter(y) - dt.timedelta(days=2), "GoodFriday"),
            (last_weekday(y, 5, 0), "Memorial"),
            (nth_weekday(y, 9, 0, 1), "Labor"),
            (nth_weekday(y, 11, 3, 4), "Thanksgiving"),
            (observed(dt.date(y, 7, 4)), "July4"),
            (observed(dt.date(y, 12, 25)), "Christmas"),
        ]
        if y >= 2022:
            items.append((observed(dt.date(y, 6, 19)), "Juneteenth"))
        for d, name in items:
            if d.weekday() >= 5:
                continue
            if d.year < y0:
                continue
            if name == "GoodFriday":
                typ = "good_friday"
            elif d.weekday() == 0:
                typ = "long_weekend"      # vendredi -> mardi
            elif d.weekday() == 4:
                typ = "long_weekend"      # jeudi -> lundi
            else:
                typ = "midweek"
            out[d] = (name, typ)
    return out


# Fermetures exceptionnelles (ouragans, deuils) — EXCLUES du jeu principal, listees pour transparence.
SPECIAL = {dt.date(2012, 10, 29): "Sandy", dt.date(2012, 10, 30): "Sandy",
           dt.date(2018, 12, 5): "Bush", dt.date(2025, 1, 9): "Carter"}


# ------------------------------------------------------------------ structure par session
def build_sessions(ohlc):
    """Une entree par JOUR DE COTATION DE SEMAINE (lun-ven, barres reelles seulement).
    Retourne liste triee de dicts : date, close_t, close, k (fades par barre k=0..3),
    a_fade (13:00 -> close), n_real."""
    byday = {}
    for t, (o, h, l, c) in ohlc.items():
        if h > l:
            byday.setdefault(t.date(), []).append(t)
    out = []
    for d in sorted(byday):
        if d.weekday() >= 5:          # le week-end n'est pas une session de semaine (loi du 56e/57e)
            continue
        ts = sorted(byday[d])
        close_t = ts[-1]
        close = ohlc[close_t][3]
        if close <= 0:
            continue
        kk = []
        for k in range(4):
            t = close_t - dt.timedelta(hours=k)
            if t not in ohlc:
                kk.append(None)
                continue
            o, h, l, c = ohlc[t]
            kk.append(-math.log(c / o) * 1e4 if o > 0 and c > 0 else None)
        # jambe A : open de la barre [13,14) -> close de la derniere barre reelle
        t13 = dt.datetime.combine(d, dt.time(13, 0), tzinfo=UTC)
        a = None
        if t13 in ohlc and ohlc[t13][0] > 0:
            a = -math.log(close / ohlc[t13][0]) * 1e4
        out.append({"date": d, "close_t": close_t, "close": close, "k": kk, "a": a,
                    "n_real": len(ts)})
    # full-day fade = close precedente -> close
    prev = None
    for s in out:
        s["full"] = (-math.log(s["close"] / prev["close"]) * 1e4
                     if prev is not None and prev["close"] > 0 else None)
        prev = s
    return out


def tag_sessions(sessions, closures):
    """Marque PRE / HOL / POST / OTHER + gap (jours calendaires jusqu'a la session de semaine suivante).
    PRE = derniere session de SEMAINE avant le conge ; HOL = session FX du jour du conge (marche
    ouvert mais mince : NYSE ferme) ; POST = premiere session de semaine apres le conge.
    Les trois sont EXCLUES de la baseline ordinaire."""
    dates = [s["date"] for s in sessions]
    pos = {d: i for i, d in enumerate(dates)}
    for s in sessions:
        s["tag"] = "OTHER"
        s["holiday"] = None
        s["gap"] = None
        s["h_type"] = None
    for i, s in enumerate(sessions):
        if i + 1 < len(sessions):
            s["gap"] = (sessions[i + 1]["date"] - s["date"]).days
    for hdate, (name, typ) in sorted(closures.items()):
        before = [d for d in dates if d < hdate]
        if not before:
            continue
        pre = before[-1]
        if (hdate - pre).days > 5:
            continue
        s = sessions[pos[pre]]
        if s["tag"] == "OTHER":
            s["tag"] = "PRE"
            s["holiday"] = name
            s["h_type"] = typ
        if hdate in pos:                     # FX ouvert le jour du conge US
            h = sessions[pos[hdate]]
            if h["tag"] == "OTHER":
                h["tag"] = "HOL"
                h["holiday"] = name
                h["h_type"] = typ
        after = [d for d in dates if d > hdate]
        if after and (after[0] - hdate).days <= 5:
            post = after[0]
            s2 = sessions[pos[post]]
            if s2["tag"] == "OTHER":
                s2["tag"] = "POST"
                s2["holiday"] = name
                s2["h_type"] = typ
            if s["tag"] == "PRE":
                # le TROU qui compte est PRE -> premiere session APRES le conge (la session FX du
                # jour du conge est mince, elle ne referme pas le trou de liquidite)
                s["gap"] = (post - pre).days
    return sessions


# ------------------------------------------------------------------ tests appariés
def matched(sessions, key):
    """Retourne (valeurs PRE, valeurs POST, valeurs OTHER, dict baseline par jour de semaine).
    key = 'k0' / 'a' / 'full'."""
    pre, post, other = [], [], []
    base = {wd: [] for wd in range(7)}
    for s in sessions:
        v = s["k"][0] if key == "k0" else s[key]
        if v is None:
            continue
        if s["tag"] == "PRE" and s["date"].weekday() < 5:
            pre.append((s, v))
        elif s["tag"] == "POST" and s["date"].weekday() < 5:
            post.append((s, v))
        elif s["tag"] == "OTHER":
            other.append((s, v))
            base[s["date"].weekday()].append(v)
    return pre, post, other, base


def residual(pre, base):
    """Chaque observation PRE est demoyennee par la moyenne des sessions ordinaires du MEME jour."""
    out = []
    for s, v in pre:
        wd = s["date"].weekday()
        if len(base[wd]) < 30:
            continue
        out.append(v - sum(base[wd]) / len(base[wd]))
    return out


def line_pre(label, pre, base, extra=""):
    r = residual(pre, base)
    m, t = tstat(r)
    raw_m, raw_t = tstat([v for _, v in pre])
    bm = sum(len(base[wd]) for wd in range(5))
    bmean = sum(sum(base[wd]) for wd in range(5)) / bm if bm else 0.0
    return (f"  {label:10s}{len(pre):5d}{raw_m:+8.2f}{raw_t:+7.2f}{bmean:+9.2f}"
            f"{m:+9.2f}{t:+7.2f}{extra}")


# ------------------------------------------------------------------ main
def main():
    rep = []
    P = rep.append
    P("=" * 128)
    P("61e (lundi 19 oct 2026, IDEE NOUVELLE) — LE FADE REFUGE EXISTE-T-IL LA VEILLE D'UN CONGE US ?")
    P("FADE = SELL du cross ; fade > 0 = SELL gagnant. k=0 = derniere barre REELLE avant la cloture FX.")
    P("Chaque session PRE est comparee aux sessions ordinaires DU MEME JOUR DE SEMAINE (controle apparie).")
    P("=" * 128)

    ohlc = {}
    for sym, fname in CROSSES.items():
        ohlc[sym] = load_ohlc([f"{DATA}/{fname}"])
    for sym in MAJORS:
        ohlc[sym] = load_ohlc(major_files(sym))

    P("\n--- 0. FLUX")
    for sym in ohlc:
        ks = sorted(ohlc[sym])
        kind = "CROSS (OANDA)" if sym in CROSSES else "MAJEURE (Dukascopy)"
        P(f"  {sym:8s} {kind:18s} bars={len(ohlc[sym]):7d}  {ks[0].date()} -> {ks[-1].date()}")

    clos = us_closures()
    P(f"\n--- 1. CALENDRIER : {len(clos)} fermetures pleines du NYSE (2006-2026)")
    typ_cnt = {}
    for d, (name, typ) in clos.items():
        typ_cnt[typ] = typ_cnt.get(typ, 0) + 1
    P("  par type : " + "  ".join(f"{k}={v}" for k, v in sorted(typ_cnt.items())))
    P("  fermetures exceptionnelles EXCLUES (transparence) : " +
      ", ".join(f"{d} {n}" for d, n in sorted(SPECIAL.items())))

    # ---------------- 2. CENSUS DES SESSIONS PRE (donnees, pas calendrier)
    P("\n--- 2. CENSUS DES SESSIONS PRE-CONGE (identifiees DANS les donnees)")
    P(f"  {'SYMBOLE':9s}{'PRE':>5s}{'POST':>6s}{'OTHER':>7s} | repartition des PRE par jour de semaine"
      f" | {'gap moyen':>10s}")
    sessions = {}
    for sym in ohlc:
        s = tag_sessions(build_sessions(ohlc[sym]), clos)
        sessions[sym] = s
        pre = [x for x in s if x["tag"] == "PRE"]
        post = [x for x in s if x["tag"] == "POST"]
        other = [x for x in s if x["tag"] == "OTHER"]
        cnt = [sum(1 for x in pre if x["date"].weekday() == wd) for wd in range(5)]
        gaps = [x["gap"] for x in pre if x["gap"]]
        P(f"  {sym:9s}{len(pre):5d}{len(post):6d}{len(other):7d} | " +
          " ".join(f"{DOW[i]}:{cnt[i]:2d}" for i in range(5)) +
          f" | {sum(gaps)/len(gaps):10.2f}")
    P("  Lecture : les PRE de VENDREDI viennent des conges du LUNDI (trou de 4 j) ; les PRE de")
    P("  MAR/MER/JEU viennent des conges en semaine (trou de 2 j). Les POST sont le controle de specificite.")

    # ---------------- 3. LE TEST PRINCIPAL : k=0 (derniere heure) PRE vs meme jour de semaine
    P("\n--- 3. TEST PRINCIPAL — fade de la DERNIERE HEURE (k=0) sur les sessions PRE-CONGE")
    P("  resid. = PRE demoyennee par la moyenne des sessions ordinaires du MEME jour de semaine.")
    P(f"  {'SYMBOLE':9s}{'n':>5s}{'PRE bp':>8s}{'t':>7s}{'base bp':>9s}{'resid bp':>9s}{'t app':>7s}")
    for sym in ohlc:
        pre, post, other, base = matched(sessions[sym], "k0")
        P(line_pre(sym, pre, base))
    P("  (rappel 59e, toutes sessions confondues : le fade de la derniere heure vaut +3.35 / +1.93 /")
    P("   +6.16 bp sur GBP_CHF / EUR_CHF / AUD_CHF le VENDREDI, et +1.28 / +0.88 / +1.94 en Mon-Jeu)")
    P("  ⚠️ les 750 sessions de la derniere heure sont lisibles en niveau ; ici on ne lit que l'ECART.")

    # ---------------- 4. SEPARATION PAR JOUR DE SEMAINE (le point decisif)
    P("\n--- 4. SEPARATION PAR JOUR DE SEMAINE — le fade pre-conge est-il un effet VENDREDI ou un effet TROU ?")
    P(f"  {'SYMBOLE':9s}{'JOUR':>5s}{'n PRE':>7s}{'PRE bp':>8s}{'base bp':>9s}{'resid':>8s}{'t':>7s}"
      f"{'gap':>6s}{'type':>14s}")
    for sym in ohlc:
        pre, post, other, base = matched(sessions[sym], "k0")
        for wd in range(5):
            xs = [(s, v) for s, v in pre if s["date"].weekday() == wd]
            if len(xs) < 5:
                continue
            b = base[wd]
            bm = sum(b) / len(b) if b else 0.0
            pm, pt = tstat([v for _, v in xs])
            r = [v - bm for _, v in xs]
            rm, rt = tstat(r)
            gaps = [s["gap"] for s, _ in xs if s["gap"]]
            types = sorted({s["h_type"] for s, _ in xs})
            P(f"  {sym:9s}{DOW[wd]:>5s}{len(xs):7d}{pm:+8.2f}{bm:+9.2f}{rm:+8.2f}{rt:+7.2f}"
              f"{(sum(gaps)/len(gaps) if gaps else 0):6.1f}{','.join(types)[:14]:>14s}")
        P("")

    # ---------------- 5. JAMBE DEPLOYABLE A (13:00 -> cloture) et pleine journee
    P("\n--- 5. JAMBE DEPLOYABLE (A = 13:00 UTC -> cloture reelle) ET JOURNEE PLEINE (close -> close)")
    P(f"  {'SYMBOLE':9s}{'A PRE':>8s}{'A base':>8s}{'A resid':>9s}{'t':>7s}"
      f"{'net cout':>9s} | {'FULL PRE':>9s}{'FULL base':>10s}{'FULL resid':>11s}{'t':>7s}")
    for sym in ohlc:
        pre, post, other, base = matched(sessions[sym], "a")
        r = residual(pre, base)
        m, t = tstat(r)
        bm = sum(sum(base[wd]) for wd in range(5)) / max(1, sum(len(base[wd]) for wd in range(5)))
        prem = tstat([v for _, v in pre])[0]
        pre2, _, _, base2 = matched(sessions[sym], "full")
        r2 = residual(pre2, base2)
        m2, t2 = tstat(r2)
        bm2 = sum(sum(base2[wd]) for wd in range(5)) / max(1, sum(len(base2[wd]) for wd in range(5)))
        prem2 = tstat([v for _, v in pre2])[0]
        P(f"  {sym:9s}{prem:+8.2f}{bm:+8.2f}{m:+9.2f}{t:+7.2f}{prem-COST_BP[sym]:+9.2f} | "
          f"{prem2:+9.2f}{bm2:+10.2f}{m2:+11.2f}{t2:+7.2f}")
    P("  net cout = A PRE - cout A/R mesure (55e). Aucun carry : hold intra-journalier.")
    P("  ⚠️ donnee BID seule : le demi-spread A LA CLOTURE n'est pas mesurable ici (question ouverte du 59e).")

    # ---------------- 6. SPECIFICITE : POST et N-1
    P("\n--- 6. SPECIFICITE — la session APRES le conge et la session AVANT la veille doivent etre plates")
    P("  Si POST (ou N-1) porte le meme effet, ce n'est pas un effet 'veille de conge'.")
    P(f"  {'SYMBOLE':9s}{'POST n':>7s}{'POST bp':>9s}{'POST resid':>11s}{'t':>7s}"
      f"{'N-1 bp':>9s}{'N-1 resid':>11s}{'t':>7s}")
    for sym in ohlc:
        pre, post, other, base = matched(sessions[sym], "k0")
        rp = residual(post, base)
        mp, tp = tstat(rp)
        # N-1 : session precedant immediatement une session PRE
        dates = [s["date"] for s in sessions[sym]]
        pos = {d: i for i, d in enumerate(dates)}
        prevs = []
        for s, _v in pre:
            i = pos[s["date"]]
            if i > 0:
                x = sessions[sym][i - 1]
                if x["tag"] == "OTHER" and x["k"][0] is not None:
                    prevs.append((x, x["k"][0]))
        rn = residual(prevs, base)
        mn, tn = tstat(rn)
        P(f"  {sym:9s}{len(post):7d}{tstat([v for _, v in post])[0]:+9.2f}{mp:+11.2f}{tp:+7.2f}"
          f"{tstat([v for _, v in prevs])[0]:+9.2f}{mn:+11.2f}{tn:+7.2f}")

    # ---------------- 7. GRADIENT ORDONNE : 5 CATEGORIES QUI SEPARENT « FX FERME » DE « US FERME »
    P("\n--- 7. GRADIENT ORDONNE PAR CATEGORIE — le fade suit-il le TROU FX ou la fermeture US ?")
    P("  1 = lun-jeu ordinaire (gap 1 j) | 2 = vendredi ordinaire (gap 3 j, week-end FX)")
    P("  3 = veille d'un conge EN SEMAINE (gap 2 j, FX ouvert le jour du conge)")
    P("  4 = JEUDI veille d'un conge du VENDREDI (gap 4 j, FX ouvert mais mince le vendredi)")
    P("  5 = VENDREDI veille d'un conge du LUNDI (gap 4 j, week-end FX + lundi US ferme)")
    P(f"  {'SYMBOLE':9s}{'1 ord lun-jeu':>16s}{'2 ord ven':>15s}{'3 pre-conge sem':>17s}"
      f"{'4 jeu->ven ferie':>19s}{'5 ven->lun ferie':>19s}")
    for sym in ohlc:
        pre, post, other, base = matched(sessions[sym], "k0")
        cats = {1: [], 2: [], 3: [], 4: [], 5: []}
        for s, v in other:
            if s["gap"] == 1 and s["date"].weekday() < 4:
                cats[1].append(v)
            elif s["date"].weekday() == 4 and s["gap"] == 3:
                cats[2].append(v)
        for s, v in pre:
            wd = s["date"].weekday()
            if s["gap"] == 2:
                cats[3].append(v)
            elif s["gap"] == 4 and wd == 3:
                cats[4].append(v)
            elif s["gap"] == 4 and wd == 4:
                cats[5].append(v)
        line = f"  {sym:9s}"
        nline = f"  {'':9s}"
        for c in (1, 2, 3, 4, 5):
            m, t = tstat(cats[c])
            line += f"{m:+10.2f}(t{t:+6.1f})"
            nline += f"{'n=' + str(len(cats[c])):>19s}"
        P(line)
        P(nline)
        if cats[5] and cats[2]:
            d, tw = welch(cats[5], cats[2])
            d2, tw2 = welch(cats[5], cats[3]) if cats[3] else (0.0, 0.0)
            P(f"  {'':9s}  -> 5 - 2 (vendredi ordinaire, meme jour de semaine) : {d:+.2f} bp  t {tw:+.2f}"
              f"   |   5 - 3 (meme gap 4 j, autre fermeture) : {d2:+.2f} bp  t {tw2:+.2f}")
    P("  Lecture : si SEUL 5 se detache, c'est le WEEK-END FX (marche ferme) qui porte l'effet.")
    P("  Si 4 ET 5 se detachent, c'est la fermeture des ACTIONS US. Si 3 se detache aussi, c'est le")
    P("  calendrier US en general. Un ordre non monotone = pas un gradient.")

    # ---------------- 8. LOCALISATION k=0..3 sur les PRE
    P("\n--- 8. LOCALISATION — profil k=0..3 sur les sessions PRE (loi du 59e : localiser AVANT de conditionner)")
    P(f"  {'SYMBOLE':9s}" + "".join(f"{'k=' + str(k):>17s}" for k in range(4)) + "   (PRE puis residuel)")
    for sym in ohlc:
        pre, post, other, base = matched(sessions[sym], "k0")
        l1 = f"  {sym:9s}"
        l2 = f"  {'':9s}"
        for k in range(4):
            xs = [(s, s["k"][k]) for s, _ in pre if s["k"][k] is not None]
            m, t = tstat([v for _, v in xs])
            b = []
            for s, v in other:
                if s["k"][k] is not None and len(base[s["date"].weekday()]) >= 30:
                    b.append(v)
            # residuel simple : soustraire la moyenne globale des ordinaires du meme jour
            r = []
            for s, v in xs:
                wd = s["date"].weekday()
                bl = [x["k"][k] for x in sessions[sym]
                      if x["tag"] == "OTHER" and x["date"].weekday() == wd and x["k"][k] is not None]
                if len(bl) >= 30:
                    r.append(v - sum(bl) / len(bl))
            m2, t2 = tstat(r)
            l1 += f"{m:+9.2f}(t{t:+4.1f})"
            l2 += f"{m2:+9.2f}(t{t2:+4.1f})"
        P(l1)
        P(l2)

    # ---------------- 9. STABILITE : IS/OOS, eres, mediane, hit, TRIM
    P("\n--- 9. STABILITE DU RESIDU (k=0) — IS 2006-2015 / OOS 2016-2026, eres, mediane, hit, TRIM 5%")
    P(f"  {'SYMBOLE':9s}{'FULL':>8s}{'IS':>8s}{'OOS':>8s}{'mediane':>9s}{'hit>0':>7s}{'TRIM5':>8s}{'n':>5s}"
      f" | eres (4)")
    for sym in ohlc:
        pre, post, other, base = matched(sessions[sym], "k0")
        rs = [(s["date"], v - sum(base[s["date"].weekday()]) / len(base[s["date"].weekday()]))
              for s, v in pre if len(base[s["date"].weekday()]) >= 30]
        full = [v for _, v in rs]
        isx = [v for d, v in rs if d < dt.date(2016, 1, 1)]
        oosx = [v for d, v in rs if d >= dt.date(2016, 1, 1)]
        eras = []
        for a, b in ERAS:
            ex = [v for d, v in rs if dt.date(a, 1, 1) <= d < dt.date(b, 1, 1)]
            eras.append(f"{tstat(ex)[0]:+7.2f}" if len(ex) >= 12 else "   n/a ")
        med = st.median(full) if full else 0.0
        hit = 100.0 * sum(1 for v in full if v > 0) / len(full) if full else 0.0
        P(f"  {sym:9s}{tstat(full)[0]:+8.2f}{tstat(isx)[0]:+8.2f}{tstat(oosx)[0]:+8.2f}"
          f"{med:+9.2f}{hit:6.0f}%{tstat(trim(full))[0]:+8.2f}{len(full):5d} |" + "".join(eras))

    # ---------------- 9b. STABILITE DE L'INCREMENT 5 - 2 (le seul chiffre qui compte)
    P("\n--- 9b. STABILITE DE L'INCREMENT « vendredi avant lundi ferie » - « vendredi ordinaire »")
    P("  C'est le SEUL chiffre qui repond a 'un jour de fermeture US en plus change-t-il le fade ?'")
    P(f"  {'SYMBOLE':9s}{'FULL':>16s}{'IS 06-15':>16s}{'OOS 16-26':>16s} | eres (diff bp / t)")
    for sym in ohlc:
        pre, post, other, base = matched(sessions[sym], "k0")
        c5 = [(s["date"], v) for s, v in pre if s["gap"] == 4 and s["date"].weekday() == 4]
        c2 = [(s["date"], v) for s, v in other if s["date"].weekday() == 4 and s["gap"] == 3]
        def wsel(rows, lo, hi):
            a = [v for d, v in rows[0] if lo <= d < hi]
            b = [v for d, v in rows[1] if lo <= d < hi]
            return welch(a, b)
        d0, t0 = wsel((c5, c2), dt.date(1990, 1, 1), dt.date(2030, 1, 1))
        di, ti = wsel((c5, c2), dt.date(1990, 1, 1), dt.date(2016, 1, 1))
        do, to = wsel((c5, c2), dt.date(2016, 1, 1), dt.date(2030, 1, 1))
        eras = []
        for a, b in ERAS:
            de, te = wsel((c5, c2), dt.date(a, 1, 1), dt.date(b, 1, 1))
            eras.append(f"{de:+6.2f}/{te:+5.2f}" if len([1 for d, _ in c5 if dt.date(a, 1, 1) <= d < dt.date(b, 1, 1)]) >= 6 else "  n/a    ")
        P(f"  {sym:9s}{d0:+9.2f}(t{t0:+4.1f}){di:+9.2f}(t{ti:+4.1f}){do:+9.2f}(t{to:+4.1f}) | " + " ".join(eras))
    P("  Lecture : un increment qui change de signe entre les eres est un artefact d'ere, pas un edge.")


    P("\n--- 10. DE QUOI EST FAIT L'ECHANTILLON (par nom de conge, crosses GBP_CHF)")
    pre, post, other, base = matched(sessions["GBP_CHF"], "k0")
    names = {}
    for s, v in pre:
        names.setdefault(s["holiday"], []).append(v)
    for n, vs in sorted(names.items(), key=lambda kv: -len(kv[1])):
        m, t = tstat(vs) if len(vs) >= 3 else (sum(vs) / len(vs), 0.0)
        P(f"  {n:14s} n={len(vs):3d}  brut {m:+7.2f} bp  t {t:+5.2f}  jours={sorted({DOW[s0['date'].weekday()] for s0, _ in pre if s0['holiday'] == n})}")

    # ---------------- 11. VERDICT
    P("\n--- 11. LECTURE")
    P("  Tableau 3 : le fade de la derniere heure est-il different la veille d'un conge ?")
    P("  Tableau 4 : l'effet vit-il le VENDREDI (donc long week-end) ou tous les jours de semaine ?")
    P("  Tableau 6 : POST et N-1 plats = specificite ; sinon effet de calendrier generique.")
    P("  Tableau 7 : l'ORDRE des buckets dit si c'est la LONGUEUR du trou qui porte l'effet.")
    P("  Tableau 9 : un residu positif FULL mais negatif en IS ou en OOS = late-bloomer, pas un edge.")

    txt = "\n".join(rep)
    print(txt)
    out = f"{HOME}/projects/tb-research/reports/2026-10-19-preholiday-fade.txt"
    with open(out, "w") as f:
        f.write(txt + "\n")
    print("\nrapport ->", out)


if __name__ == "__main__":
    main()
