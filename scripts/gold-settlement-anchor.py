#!/usr/bin/env python3
"""62e (mardi 20 oct 2026, VARIATION) — LE FADE EXIGE-T-IL UN MARCHE FERME ? Transposition du 61e a l'OR.

VARIATION du 61e (pre-conge FX) transposee a l'OR. Le 61e a etabli sur les crosses refuge que le fade de la
derniere heure suit la FERMETURE DU MARCHE FX, pas le calendrier actions US : a trou EGAL de 4 jours, la
veille de Good Friday (FX OUVERT) vaut +0.05/+0.22/+0.86 bp et la veille d'un lundi ferie (FX FERME)
+4.77/+2.77/+7.85 (contraste t 3.3-3.7). Mecanisme invoque : le TROU DE LIQUIDITE.

L'or est le seul instrument du pipeline qui offre un CONTROLE INTERNE de ce mecanisme :
  (i)  il a une cloture QUOTIDIENNE (settlement 17:00 ET) que le FX n'a pas -> trou de 1 jour TOUS les jours ;
  (ii) son calendrier de fermeture differe du FX : il ferme Good Friday / Noel / Jour de l'An (le FX cote) ;
       le MEME jeudi est donc g=4 pour l'or et "FX ouvert" pour le FX.

Predictions falsifiables :
  P1 (TROU)      : si le mecanisme est le trou, le fade k=0 existe TOUS les jours de semaine sur l'or.
  P2 (ANCRE)     : loi du 59e — l'effet suit le SETTLEMENT (barre finissant a 17:00 ET), pas une heure fixe.
  P3 (FERMETURE) : sur le calendrier PROPRE a l'or, l'effet est une fonction MONOTONE de la longueur du trou.

ATTENTION QUALITE DES DONNEES (mesuree, pas supposee) : le trou de settlement n'est VISIBLE dans le feed qu'a
partir de 2013 (2006-2012 = 24 barres reelles chaque lun-jeu, feed rempli). La BARRE qui se termine a 17:00 ET
existe en revanche sur toute la periode -> l'ancre est batie sur l'heure d'ete reelle (2e dimanche de mars ->
1er dimanche de novembre) et l'accord avec le trou observe est MESURE comme validation independante.

Conventions (lois du 57e/59e/60e/61e) :
  - FADE = SELL ; fade = -log(close/open)*1e4 ; fade > 0 = SELL gagnant. Signe ecrit dans chaque tableau.
  - k=0 = barre qui se termine a 17:00 ET ; k=1..5 = precedentes ; p1 = premiere barre APRES le trou.
  - COUT = 0.53 USD A/R MESURE (RealCostModel.XAU_USD : demi-spread 0.265 par jambe) converti en bp au prix.
  - IS/OOS + eres + mediane + hit + TRIM 5% + HOLD publie (loi du 56e/60e : le hold est EMERGENT du flux).
Aucune ecriture dans le repo Java. Donnees : XAU_USD H1 bid Dukascopy 2006-2025.
"""
import csv, datetime as dt, glob, math, os, statistics as st, collections

HOME = os.path.expanduser("~")
DATA = f"{HOME}/projects/tb-research/data/historical/dukascopy"
UTC = dt.timezone.utc
SPREAD_USD_RT = 0.53
DOW = ["LUN", "MAR", "MER", "JEU", "VEN"]
ERAS = [(2006, 2010), (2010, 2015), (2015, 2020), (2020, 2026)]


def load_xau():
    out = {}
    for path in sorted(glob.glob(f"{DATA}/xauusd-h1-bid-[12]*.csv")):
        with open(path, newline="") as f:
            r = csv.reader(f); next(r, None)
            for row in r:
                if len(row) < 5 or not row[0].strip().isdigit(): continue
                v = int(row[0]); v = v * 1000 if v < 10 ** 11 else v
                try:
                    t = dt.datetime.fromtimestamp(v / 1000.0, tz=UTC).replace(minute=0, second=0, microsecond=0)
                    out[t] = tuple(float(x) for x in row[1:5])
                except (ValueError, OverflowError, OSError): continue
    return out


def tstat(xs):
    n = len(xs)
    if n < 3: return 0.0, 0.0
    m = sum(xs) / n
    sd = (sum((x - m) ** 2 for x in xs) / (n - 1)) ** 0.5
    return m, (m / (sd / n ** 0.5) if sd > 0 else 0.0)


def welch(a, b):
    if len(a) < 3 or len(b) < 3: return 0.0, 0.0
    ma, mb = sum(a) / len(a), sum(b) / len(b)
    va = sum((x - ma) ** 2 for x in a) / (len(a) - 1)
    vb = sum((x - mb) ** 2 for x in b) / (len(b) - 1)
    se = (va / len(a) + vb / len(b)) ** 0.5
    return ma - mb, ((ma - mb) / se if se > 0 else 0.0)


def trim(xs, frac=0.05):
    if len(xs) < 40: return xs
    k = max(1, int(frac * len(xs)))
    return sorted(xs)[k:len(xs) - k]


def fade(o, c):
    return -math.log(c / o) * 1e4 if o > 0 and c > 0 else None


def us_dst_bounds(y):
    """2e dimanche de mars -> 1er dimanche de novembre (regle US depuis 2007)."""
    def nth_sun(m, n):
        d = dt.date(y, m, 1); c = 0
        while True:
            if d.weekday() == 6:
                c += 1
                if c == n: return d
            d += dt.timedelta(days=1)
    return nth_sun(3, 2), nth_sun(11, 1)


def is_edt(d):
    a, b = us_dst_bounds(d.year)
    return a <= d < b


def settle_hour(d):
    """Heure UTC de la barre qui SE TERMINE a 17:00 ET : 20 en EDT, 21 en EST."""
    return 20 if is_edt(d) else 21


def build_sessions(ohlc, real):
    byday = collections.defaultdict(list)
    for t in real:
        if t.weekday() < 5: byday[t.date()].append(t)
    out = []
    for d in sorted(byday):
        ts = sorted(byday[d])
        ah = settle_hour(d)
        if d.weekday() == 4:
            close_t = ts[-1]                      # vendredi : cloture hebdo = derniere barre reelle
        else:
            close_t = dt.datetime.combine(d, dt.time(ah, 0), tzinfo=UTC)
            if close_t not in real:
                close_t = ts[-1]
        c_ = ohlc[close_t][3]
        if c_ <= 0: continue
        hours = {t.hour for t in ts}
        brk = next((h for h in (21, 22, 23) if h not in hours), None) if d.weekday() < 4 else None
        kk = []
        for k in range(6):
            t = close_t - dt.timedelta(hours=k)
            kk.append(fade(ohlc[t][0], ohlc[t][3]) if t in real else None)
        after = [t for t in ts if t > close_t]
        p = [fade(ohlc[after[k]][0], ohlc[after[k]][3]) if len(after) > k else None for k in range(3)]
        hold = (after[0] - close_t).total_seconds() / 3600.0 if after else None
        t13 = dt.datetime.combine(d, dt.time(13, 0), tzinfo=UTC)
        a = fade(ohlc[t13][0], c_) if t13 in real else None
        out.append({"date": d, "brk": brk, "brk_exp": (ah + 1) if d.weekday() < 4 else None,
                    "close_t": close_t, "close": c_, "k": kk, "p": p, "hold": hold, "a": a,
                    "n_real": len(ts)})
    prev = None
    for s in out:
        s["full"] = fade(prev["close"], s["close"]) if prev else None
        prev = s
    for i, s in enumerate(out):
        s["gap"] = (out[i + 1]["date"] - s["date"]).days if i + 1 < len(out) else None
    return out


def nth_weekday(y, m, wd, n):
    d = dt.date(y, m, 1); c = 0
    while d.month == m:
        if d.weekday() == wd:
            c += 1
            if c == n: return d
        d += dt.timedelta(days=1)
    raise ValueError


def last_weekday(y, m, wd):
    d = dt.date(y, m + 1, 1) - dt.timedelta(days=1) if m < 12 else dt.date(y, 12, 31)
    while d.weekday() != wd: d -= dt.timedelta(days=1)
    return d


def easter(y):
    a = y % 19; b, c = divmod(y, 100); d, e = divmod(b, 4); f = (b + 8) // 25; g = (b - f + 1) // 3
    h = (19 * a + b - d - g + 15) % 30; i, k = divmod(c, 4); l = (32 + 2 * e + 2 * i - h - k) % 7
    m = (a + 11 * h + 22 * l) // 451; mo = (h + l - 7 * m + 114) // 31; da = ((h + l - 7 * m + 114) % 31) + 1
    return dt.date(y, mo, da)


def observed(d):
    if d.weekday() == 5: return d - dt.timedelta(days=1)
    if d.weekday() == 6: return d + dt.timedelta(days=1)
    return d


def us_holidays(y0=2006, y1=2025):
    out = {}
    for y in range(y0, y1 + 1):
        items = [(observed(dt.date(y, 1, 1)), "NewYear"), (nth_weekday(y, 1, 0, 3), "MLK"),
                 (nth_weekday(y, 2, 0, 3), "Presidents"), (easter(y) - dt.timedelta(days=2), "GoodFriday"),
                 (last_weekday(y, 5, 0), "Memorial"), (observed(dt.date(y, 7, 4)), "July4"),
                 (nth_weekday(y, 9, 0, 1), "Labor"), (nth_weekday(y, 11, 3, 4), "Thanksgiving"),
                 (observed(dt.date(y, 12, 25)), "Christmas")]
        if y >= 2022: items.append((observed(dt.date(y, 6, 19)), "Juneteenth"))
        for d, n in items:
            if d.weekday() < 5 and d.year >= y0: out[d] = n
    return out


def main():
    rep = []; P = rep.append
    W = 138
    P("=" * W)
    P("62e (mardi 20 oct 2026, VARIATION) — LE FADE EXIGE-T-IL UN MARCHE FERME ? Transposition du 61e a l'OR")
    P("FADE = SELL ; fade = -log(close/open)*1e4 ; fade > 0 = SELL gagnant.")
    P("k=0 = barre qui se termine a 17:00 ET (settlement). p1 = premiere barre APRES le trou.")
    P("P1 = le fade existe tous les jours (trou quotidien) | P2 = ancre settlement, pas heure fixe")
    P("P3 = fonction monotone de la longueur du trou du calendrier de l'OR")
    P("=" * W)

    ohlc = load_xau()
    real = {t: v for t, v in ohlc.items() if v[1] > v[2]}
    S = build_sessions(ohlc, real)
    ks = sorted(ohlc)
    P("\n--- 0. FLUX, STRUCTURE ET QUALITE DE L'ANCRE")
    P(f"  XAU_USD H1 bid  bars={len(ohlc):7d}  reelles={len(real):7d}  {ks[0].date()} -> {ks[-1].date()}")
    P(f"  sessions de l'or = {len(S)}  (jours lun-ven avec au moins une barre reelle)")
    ok = bad = novis = 0
    byyear = collections.defaultdict(lambda: [0, 0, 0])
    for s in S:
        if s["date"].weekday() >= 4: continue
        y = s["date"].year
        if s["brk"] is None: novis += 1; byyear[y][2] += 1
        elif s["brk"] == s["brk_exp"]: ok += 1; byyear[y][0] += 1
        else: bad += 1; byyear[y][1] += 1
    P(f"  VALIDATION DE L'ANCRE (lun-jeu) : heure absente == 17:00 ET + 1 h -> {ok} OK, {bad} DECALES,"
      f" {novis} SANS trou visible")
    P("  par an (OK / decale / sans trou) :")
    line = "   "
    for y in range(2006, 2026):
        a, b, c = byyear[y]
        line += f" {y}:{a}/{b}/{c}"
        if y % 5 == 4: P(line); line = "   "
    if line.strip(): P(line)
    P("  ATTENTION 2006-2012 : feed REMPLI 24 h, aucun trou -> le marche de l'or n'y a pas de cloture observable.")
    P("  La BARRE finissant a 17:00 ET existe sur toute la periode ; mais un FULL 2006-2025 melange 11 ans sans")
    P("  structure et 13 ans avec. Les tableaux donnent FULL et 2016+ separement.")
    vis = [s for s in S if s["date"].weekday() < 4 and s["brk"] is not None]
    P(f"  Sous-echantillon 'trou visible' : {len(vis)} sessions lun-jeu (2013+)")

    allbars = [fade(ohlc[t][0], ohlc[t][3]) for t in real if t.weekday() < 5]
    bm, bt = tstat(allbars)
    P(f"\n  DRIFT HORAIRE INCONDITIONNEL (toutes barres reelles lun-ven) : {bm:+.3f} bp  t {bt:+.1f}  n={len(allbars)}")
    P("  -> tout k=0 se lit CONTRE ce chiffre (loi du 59e : soustraire le drift generique).")

    P("\n--- 1. LOCALISATION — profil settlement-relatif par jour de semaine (localiser AVANT de conditionner)")
    P("    k=0 se termine a 17:00 ET ; k=5 = 5 h avant ; p1 = premiere barre apres le trou.")
    for sub, lab in [(S, "FULL 2006-2025"), ([s for s in S if s["date"].year >= 2016], "2016-2025 (trou systematique)")]:
        P(f"\n  [{lab}]")
        P(f"  {'JOUR':6s}{'n':>5s}" + "".join(f"{'k=' + str(k):>15s}" for k in range(5, -1, -1)) + f"{'p1 reouv':>15s}")
        for wd in range(5):
            row = [s for s in sub if s["date"].weekday() == wd]
            if len(row) < 30: continue
            line = f"  {DOW[wd]:6s}{len(row):5d}"
            for k in range(5, -1, -1):
                xs = [s["k"][k] for s in row if s["k"][k] is not None]
                m, t = tstat(xs)
                line += f"{m:+8.2f}({t:+5.1f})"
            xs = [s["p"][0] for s in row if s["p"][0] is not None]
            line += f"{tstat(xs)[0]:+8.2f}({tstat(xs)[1]:+5.1f})" if xs else f"{'n/a':>15s}"
            P(line)

    P("\n--- 2. P1 (TROU) — k=0 par jour de semaine : le fade existe-t-il TOUS les jours ?")
    P(f"  {'JOUR':6s}{'n':>5s}{'k=0 FULL':>10s}{'t':>7s}{'k=0 2016+':>11s}{'t':>7s}{'vs drift':>10s}"
      f"{'k=1 FULL':>10s}{'t':>7s}{'p1 reouv':>10s}{'t':>7s}")
    for wd in range(5):
        row = [s for s in S if s["date"].weekday() == wd]
        r16 = [s for s in row if s["date"].year >= 2016]
        xs = [s["k"][0] for s in row if s["k"][0] is not None]
        x16 = [s["k"][0] for s in r16 if s["k"][0] is not None]
        x1 = [s["k"][1] for s in row if s["k"][1] is not None]
        xp = [s["p"][0] for s in row if s["p"][0] is not None]
        m, t = tstat(xs); m16, t16 = tstat(x16)
        P(f"  {DOW[wd]:6s}{len(xs):5d}{m:+10.2f}{t:+7.2f}{m16:+11.2f}{t16:+7.2f}{m - bm:+10.2f}"
          f"{tstat(x1)[0]:+10.2f}{tstat(x1)[1]:+7.2f}{(tstat(xp)[0] if xp else 0):+10.2f}"
          f"{(tstat(xp)[1] if xp else 0):+7.2f}")

    P("\n--- 3. P2 (ANCRE) — settlement (17:00 ET : h20 EDT / h21 EST) vs heure UTC fixe")
    P(f"  {'JOUR':6s}{'n EDT':>7s}{'k=0 EDT':>10s}{'t':>7s}{'n EST':>7s}{'k=0 EST':>10s}{'t':>7s}"
      f"   ||{'barre [20,21)UTC':>18s}{'t':>7s}{'barre [21,22)UTC':>18s}{'t':>7s}")
    for wd in range(5):
        row = [s for s in S if s["date"].weekday() == wd]
        edt = [s for s in row if is_edt(s["date"])]
        est = [s for s in row if not is_edt(s["date"])]
        def hbar(rows, hh):
            """barre UTC [hh, hh+1) quel que soit le pays."""
            xs = []
            for s in rows:
                t = dt.datetime.combine(s["date"], dt.time(hh, 0), tzinfo=UTC)
                if t in real: xs.append(fade(real[t][0], real[t][3]))
            return tstat(xs)
        a = tstat([s["k"][0] for s in edt if s["k"][0] is not None])
        b = tstat([s["k"][0] for s in est if s["k"][0] is not None])
        f20 = hbar(row, 20); f21 = hbar(row, 21)
        P(f"  {DOW[wd]:6s}{len(edt):7d}{a[0]:+10.2f}{a[1]:+7.2f}{len(est):7d}{b[0]:+10.2f}{b[1]:+7.2f}"
          f"   ||{f20[0]:+18.2f}{f20[1]:+7.2f}{f21[0]:+18.2f}{f21[1]:+7.2f}")
    P("  Loi du 59e : sous la BONNE ancre les deux saisons tiennent ; sous une heure UTC fixe l'effet scinde.")
    P("  (en EDT l'ancre EST la barre [20,21) UTC ; en EST c'est [21,22). Une barre fixe melange les deux.)")

    P("\n--- 3b. CONTROLE CROSS-INSTRUMENT — la MEME barre [16,17) ET sur l'or et sur 4 paires FX")
    P("  Si l'effet du settlement est un effet d'HORLOGE commun a tous les actifs, le FX doit montrer le MEME")
    P("  signe a la meme heure. Le 59e a mesure sur le FX une derniere heure NEGATIVE tous les jours.")
    fx = {}
    for sym in ["EUR_USD", "GBP_JPY", "USD_CHF", "USD_CAD"]:
        base = sym.replace("_", "").lower()
        d2 = {}
        for path in sorted(glob.glob(f"{DATA}/{base}-h1-bid-[12]*.csv")):
            with open(path, newline="") as f:
                r = csv.reader(f); next(r, None)
                for row in r:
                    if len(row) < 5 or not row[0].strip().isdigit(): continue
                    v = int(row[0]); v = v * 1000 if v < 10 ** 11 else v
                    try:
                        t = dt.datetime.fromtimestamp(v / 1000.0, tz=UTC).replace(minute=0, second=0, microsecond=0)
                        d2[t] = tuple(float(x) for x in row[1:5])
                    except (ValueError, OverflowError, OSError): continue
        fx[sym] = d2
    P(f"  {'INSTRUMENT':12s}{'n lun-jeu':>10s}{'k=0 lun-jeu':>13s}{'t':>7s}{'n ven':>7s}{'k=0 vendredi':>14s}{'t':>7s}")
    for sym in ["XAU_USD"] + list(fx):
        src = real if sym == "XAU_USD" else {t: v for t, v in fx[sym].items() if v[1] > v[2]}
        mj, vn = [], []
        for s in S:
            t = dt.datetime.combine(s["date"], dt.time(settle_hour(s["date"]), 0), tzinfo=UTC)
            if t not in src: continue
            v0 = fade(src[t][0], src[t][3])
            if v0 is None: continue
            (vn if s["date"].weekday() == 4 else mj).append(v0)
        P(f"  {sym:12s}{len(mj):10d}{tstat(mj)[0]:+13.2f}{tstat(mj)[1]:+7.2f}"
          f"{len(vn):7d}{tstat(vn)[0]:+14.2f}{tstat(vn)[1]:+7.2f}")
    P("  Lecture : un effet PROPRE a l'or (auction de settlement) donne un signe que le FX ne partage pas.")

    P("\n--- 3c. LA MEME CHOSE, RESTREINTE A 2016+ (periode ou l'or a un vrai trou), et sur la barre SUIVANTE")
    P("  [16,17) ET = la derniere heure avant le settlement ; [17,18) ET = la premiere heure APRES (le 'reopen').")
    P("  Si le FX montre la MEME chose sur [17,18) ET, le rebond n'est pas un effet de l'or : c'est le rollover.")
    P(f"  {'INSTRUMENT':12s}{'[16,17) lun-jeu':>17s}{'t':>7s}{'[17,18) lun-jeu':>17s}{'t':>7s}"
      f"{'[16,17) ven':>13s}{'t':>7s}{'[17,18) ven':>13s}{'t':>7s}")
    for sym in ["XAU_USD"] + list(fx):
        src = real if sym == "XAU_USD" else {t: v for t, v in fx[sym].items() if v[1] > v[2]}
        acc = {("mj", 0): [], ("mj", 1): [], ("vn", 0): [], ("vn", 1): []}
        for s in S:
            if s["date"].year < 2016: continue
            a0 = dt.datetime.combine(s["date"], dt.time(settle_hour(s["date"]), 0), tzinfo=UTC)
            a1 = a0 + dt.timedelta(hours=1)
            kk = "vn" if s["date"].weekday() == 4 else "mj"
            for j, tt in ((0, a0), (1, a1)):
                if tt in src:
                    v0 = fade(src[tt][0], src[tt][3])
                    if v0 is not None: acc[(kk, j)].append(v0)
        P(f"  {sym:12s}{tstat(acc[('mj',0)])[0]:+17.2f}{tstat(acc[('mj',0)])[1]:+7.2f}"
          f"{tstat(acc[('mj',1)])[0]:+17.2f}{tstat(acc[('mj',1)])[1]:+7.2f}"
          f"{tstat(acc[('vn',0)])[0]:+13.2f}{tstat(acc[('vn',0)])[1]:+7.2f}"
          f"{tstat(acc[('vn',1)])[0]:+13.2f}{tstat(acc[('vn',1)])[1]:+7.2f}")

    P("\n--- 4. P3 (FERMETURE) — k=0 en fonction de la LONGUEUR DU TROU du calendrier de l'OR")
    P(f"  {'g':>3s}{'n':>6s}{'k=0 bp':>10s}{'t':>7s}{'k=1 bp':>9s}{'jour(s)':>22s}{'  exemples':>30s}")
    byg = collections.defaultdict(list)
    for s in S:
        if s["gap"] and s["k"][0] is not None: byg[s["gap"]].append(s)
    for g in sorted(byg):
        rows = byg[g]
        xs = [s["k"][0] for s in rows]
        x1 = [s["k"][1] for s in rows if s["k"][1] is not None]
        dows = sorted({DOW[s["date"].weekday()] for s in rows})
        ex = ", ".join(str(s["date"]) for s in rows[:2])
        P(f"  {g:3d}{len(rows):6d}{tstat(xs)[0]:+10.2f}{tstat(xs)[1]:+7.2f}{tstat(x1)[0]:+9.2f}"
          f"{'/'.join(dows)[:20]:>22s}{ex:>30s}")

    P("\n--- 5. TEST DECISIF — le meme jeudi est 'FX ouvert' et 'or ferme le lendemain' (Good Friday)")
    hol = us_holidays()
    pos = {s["date"]: i for i, s in enumerate(S)}
    def pre_of(hd):
        before = [s for s in S if s["date"] < hd and (hd - s["date"]).days <= 6]
        return before[-1] if before else None
    GF = sorted(d for d, n in hol.items() if n == "GoodFriday")
    MONH = sorted(d for d, n in hol.items() if d.weekday() == 0)
    gf_pre = [x for x in (pre_of(d) for d in GF) if x]
    mon_pre = [x for x in (pre_of(d) for d in MONH) if x]
    P("  Rappel 61e (FX, k=0) : veille Good Friday (FX OUVERT) +0.05/+0.22/+0.86 bp ; veille lundi ferie")
    P("  (FX FERME) +4.77/+2.77/+7.85 ; contraste t 3.3-3.7.")
    P(f"  {len(gf_pre)} jeudis veille de Good Friday | {len(mon_pre)} vendredis veille d'un lundi ferie")
    cats = [("ordinaire lun-jeu (g=1)", [s for s in S if s["date"].weekday() < 4 and s["gap"] == 1]),
            ("ordinaire vendredi (g=3)", [s for s in S if s["date"].weekday() == 4 and s["gap"] == 3]),
            ("JEUDI veille Good Friday", gf_pre),
            ("VENDREDI veille lundi ferie", mon_pre)]
    P(f"  {'CATEGORIE':30s}{'n':>5s}{'k=0 bp':>10s}{'t':>7s}{'mediane':>9s}{'hit>0':>7s}")
    for label, rows in cats:
        xs = [s["k"][0] for s in rows if s["k"][0] is not None]
        if not xs: continue
        P(f"  {label:30s}{len(xs):5d}{tstat(xs)[0]:+10.2f}{tstat(xs)[1]:+7.2f}"
          f"{st.median(xs):+9.2f}{100.0*sum(1 for v in xs if v>0)/len(xs):6.0f}%")
    dd = {label: [s["k"][0] for s in rows if s["k"][0] is not None] for label, rows in cats}
    for lab, A, B in [("GoodFriday-PRE - ordin. lun-jeu ", dd["JEUDI veille Good Friday"], dd["ordinaire lun-jeu (g=1)"]),
                      ("lundi-ferie-PRE - ordin. vendredi", dd["VENDREDI veille lundi ferie"], dd["ordinaire vendredi (g=3)"]),
                      ("lundi-ferie-PRE - GoodFriday-PRE ", dd["VENDREDI veille lundi ferie"], dd["JEUDI veille Good Friday"])]:
        d0, t0 = welch(A, B)
        P(f"  -> {lab} : {d0:+6.2f} bp  t {t0:+5.2f}")

    P("\n--- 6. JAMBES (loi du 60e) et HOLD REEL (loi du 56e)")
    P("  A = 13:00 UTC -> settlement | B = settlement -> 1re barre apres le trou | C = 13:00 -> reouverture")
    P(f"  {'CATEGORIE':30s}{'n':>5s}{'A bp':>9s}{'B bp':>9s}{'C bp':>9s}{'jour plein':>11s}{'hold B (h)':>11s}")
    for label, rows in cats:
        A_ = [s["a"] for s in rows if s["a"] is not None]
        B_ = [s["p"][0] for s in rows if s["p"][0] is not None]
        C_ = [s["a"] + s["p"][0] for s in rows if s["a"] is not None and s["p"][0] is not None]
        F_ = [s["full"] for s in rows if s["full"] is not None]
        H_ = [s["hold"] for s in rows if s["hold"]]
        P(f"  {label:30s}{len(A_):5d}{tstat(A_)[0]:+9.2f}{(tstat(B_)[0] if B_ else 0):+9.2f}"
          f"{(tstat(C_)[0] if C_ else 0):+9.2f}{(tstat(F_)[0] if F_ else 0):+11.2f}"
          f"{(sum(H_)/len(H_) if H_ else 0):11.1f}")
    allcost = [SPREAD_USD_RT / s["close"] * 1e4 for s in S if s["close"] > 0]
    P(f"  cout A/R de l'or (0.53 USD mesure) : mediane {st.median(allcost):.2f} bp, min {min(allcost):.2f},"
      f" max {max(allcost):.2f} (prix 600 -> 4300 USD)")

    P("\n--- 7. STABILITE — IS/OOS et eres (loi du 61e : le late-bloomer est le mode de mort par defaut)")
    P(f"  {'SERIE':34s}{'n':>5s}{'FULL':>8s}{'IS 06-15':>10s}{'OOS 16-25':>11s}{'TRIM5':>8s}"
      f"{'   eres 06-10/10-15/15-20/20-26':>32s}")
    series = [("k=0 lun-jeu (fade pre-settlement)", [s for s in S if s["date"].weekday() < 4], 0),
              ("k=0 vendredi (bid pre-cloture)", [s for s in S if s["date"].weekday() == 4], 0),
              ("k=1 lun-ven (baseline matinee)", [s for s in S], 1),
              ("A = 13:00 -> settlement lun-jeu", [s for s in S if s["date"].weekday() < 4], "a"),
              ("B = settlement -> reouverture", [s for s in S if s["date"].weekday() < 4], "p"),
              ("A vendredi = 13:00 -> cloture", [s for s in S if s["date"].weekday() == 4], "a")]
    for lab, rows, key in series:
        if key == "a": rs = [(s["date"], s["a"]) for s in rows if s["a"] is not None]
        elif key == "p": rs = [(s["date"], s["p"][0]) for s in rows if s["p"][0] is not None]
        else: rs = [(s["date"], s["k"][key]) for s in rows if s["k"][key] is not None]
        full = [v for _, v in rs]
        isx = [v for d, v in rs if d < dt.date(2016, 1, 1)]
        oosx = [v for d, v in rs if d >= dt.date(2016, 1, 1)]
        eras = []
        for a, b in ERAS:
            ex = [v for d, v in rs if dt.date(a, 1, 1) <= d < dt.date(b, 1, 1)]
            eras.append(f"{tstat(ex)[0]:+6.2f}" if len(ex) >= 10 else "  n/a ")
        P(f"  {lab:34s}{len(full):5d}{tstat(full)[0]:+8.2f}{tstat(isx)[0]:+10.2f}{tstat(oosx)[0]:+11.2f}"
          f"{tstat(trim(full))[0]:+8.2f}{'  ' + ' / '.join(eras):>32s}")

    P("\n--- 7b. STABILITE SUR LA SEULE PERIODE OU LA STRUCTURE EXISTE (2016+) — IS 2016-2020 / OOS 2021-2025")
    P("  Le split 2006/2016 ci-dessus n'est PAS un vrai IS/OOS pour l'effet de settlement : 2006-2012 est un feed")
    P("  rempli 24 h ou la barre de 17:00 ET n'est pas une cloture. On ne mesure pas un phenomene la ou il n'existe pas.")
    P(f"  {'SERIE':34s}{'n 2016+':>8s}{'FULL':>8s}{'IS 16-20':>10s}{'OOS 21-25':>11s}{'t OOS':>7s}{'TRIM5':>8s}")
    for lab, rows, key in series:
        rows = [s for s in rows if s["date"].year >= 2016]
        if key == "a": rs = [(s["date"], s["a"]) for s in rows if s["a"] is not None]
        elif key == "p": rs = [(s["date"], s["p"][0]) for s in rows if s["p"][0] is not None]
        else: rs = [(s["date"], s["k"][key]) for s in rows if s["k"][key] is not None]
        full = [v for _, v in rs]
        isx = [v for d, v in rs if d < dt.date(2021, 1, 1)]
        oosx = [v for d, v in rs if d >= dt.date(2021, 1, 1)]
        P(f"  {lab:34s}{len(full):8d}{tstat(full)[0]:+8.2f}{tstat(isx)[0]:+10.2f}{tstat(oosx)[0]:+11.2f}"
          f"{tstat(oosx)[1]:+7.2f}{tstat(trim(full))[0]:+8.2f}")

    P("\n--- 8. COMPOSITION PAR CONGE et SPECIFICITE (POST / N-1 plats = pas un effet de calendrier generique)")
    names = collections.defaultdict(list)
    for hd, n in hol.items():
        x = pre_of(hd)
        if x and x["k"][0] is not None and (hd - x["date"]).days <= 6:
            names[n].append((x, x["k"][0]))
    for n, vs in sorted(names.items(), key=lambda kv: -len(kv[1])):
        vv = [v for _, v in vs]
        P(f"  {n:13s} n={len(vv):3d}  brut {tstat(vv)[0]:+7.2f} bp  t {tstat(vv)[1]:+5.2f}"
          f"  jours={sorted({DOW[s0['date'].weekday()] for s0, _ in vs})}")
    P(f"  {'CATEGORIE':30s}{'PRE k=0':>10s}{'POST n':>8s}{'POST k=0':>10s}{'N-1 k=0':>10s}")
    for label, rows in cats:
        pre = [s["k"][0] for s in rows if s["k"][0] is not None]
        post, prev = [], []
        for s in rows:
            i = pos.get(s["date"])
            if i is None: continue
            if i + 1 < len(S) and S[i + 1]["k"][0] is not None: post.append(S[i + 1]["k"][0])
            if i > 0 and S[i - 1]["k"][0] is not None: prev.append(S[i - 1]["k"][0])
        P(f"  {label:30s}{tstat(pre)[0]:+10.2f}{len(post):8d}{tstat(post)[0]:+10.2f}{tstat(prev)[0]:+10.2f}")

    txt = "\n".join(rep)
    print(txt)
    out = f"{HOME}/projects/tb-research/reports/2026-10-20-gold-settlement-anchor.txt"
    with open(out, "w") as f: f.write(txt + "\n")
    print("\nrapport ->", out)


if __name__ == "__main__":
    main()
