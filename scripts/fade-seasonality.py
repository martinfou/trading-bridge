#!/usr/bin/env python3
"""63e (mercredi 21 oct 2026, PATTERN SAISONNIER) — LE FADE REFUGE EST-IL SAISONNIER ?

MISSION DU MERCREDI : pattern saisonnier + scan macro. La dimension saisonniere a ete fermee sur les
PAIRES (48e/51e : une saisonnalite de paires est une PROJECTION) et sur les facteurs GBP avril/octobre
(refutes par specificite + ere + OANDA pre-2006). Mais elle n'a JAMAIS ete appliquee a l'EDGE QUI SE
DEPLOIE VENDREDI : le fade de la DERNIERE HEURE du vendredi sur les crosses refuge (59e/60e/62e).

Question falsifiable, et directement decisionnelle (deploiement prevu vendredi 23 oct) :
   Q : l'increment vendredi (fade ven - drift Mon-Jeu) est-il STABLE sur les 12 mois, ou concentre ?
   Si concentre, deployer en fin octobre serait deploier HORS SAISON.

Objet mesure (lois du 57e/59e/62e) :
  fade = -log(close/open)*1e4 sur la DERNIERE BARRE REELLE du jour (high > low, jamais close != prev) ;
  fade > 0 = SELL gagnant. Convention A du 59e (ancre CLOTURE reelle, pas une heure UTC fixe).
  Increment D = fade(vendredi) - fade(Mon-Jeu de la MEME semaine)  -> retire le drift generique de fin
  de journee (loi du 59e : sans ce contre-placebo on survend l'edge d'un facteur 2-3).

Controles OBLIGATOIRES (ceux qui ont tue avril et octobre dans la bibliotheque saisonniere) :
  (a) test PRIMAIRE = ANOVA F sur l'effet mois (on ne choisit PAS le meilleur mois -> pas de snooping) ;
  (b) DEMEANAGE PAR ANNEE (identifiabilite Cho & Matteson : un mois "significatif" qui repete la derive
      de l'ere n'est pas une saison) ;
  (c) PLACEBO de permutation (melange des mois DANS chaque annee, 2000 tirages) sur le max |t| des mois ;
  (d) controle de la jambe Mon-Jeu : la saisonnalite est-elle specifique au vendredi ou generique ?
  (e) ere split + extension OANDA PRE-2006 (2002-2005), le test OOS le moins cher disponible ;
  (f) P0 : rejeu des chiffres du 62e dans le run (loi du 60e).

Donnees : crosses refuge OANDA H1 bid dans ~/projects/tb-research/data/historical/dukascopy.
Aucune ecriture dans le repo Java.
"""
import csv, datetime as dt, glob, math, os, random, statistics as st, collections

HOME = os.path.expanduser("~")
DATA = f"{HOME}/projects/tb-research/data/historical/dukascopy"
UTC = dt.timezone.utc
CROSSES = ["gbpchf", "eurchf", "audchf"]
CONTROL = ["eurusd"]              # majeure, jambe refuge absente -> controle cross-section
MONTHS = ["JAN", "FEV", "MAR", "AVR", "MAI", "JUN", "JUL", "AOU", "SEP", "OCT", "NOV", "DEC"]
W = 132
rappel = []


def P(s=""):
    rappel.append(s)


# ---------------------------------------------------------------- stats (stdlib only)
def tstat(xs):
    n = len(xs)
    if n < 3:
        return 0.0, 0.0
    m = sum(xs) / n
    sd = (sum((x - m) ** 2 for x in xs) / (n - 1)) ** 0.5
    return m, (m / (sd / n ** 0.5) if sd > 0 else 0.0)


def betacf(a, b, x):
    MAXIT, EPS, FPMIN = 300, 3e-16, 1e-300
    qab, qap, qam = a + b, a + 1.0, a - 1.0
    c, d = 1.0, 1.0 - qab * x / qap
    if abs(d) < FPMIN:
        d = FPMIN
    d = 1.0 / d
    h = d
    for m in range(1, MAXIT + 1):
        m2 = 2 * m
        aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1.0 + aa * d
        if abs(d) < FPMIN:
            d = FPMIN
        c = 1.0 + aa / c
        if abs(c) < FPMIN:
            c = FPMIN
        d = 1.0 / d
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1.0 + aa * d
        if abs(d) < FPMIN:
            d = FPMIN
        c = 1.0 + aa / c
        if abs(c) < FPMIN:
            c = FPMIN
        d = 1.0 / d
        de = d * c
        h *= de
        if abs(de - 1.0) < EPS:
            break
    return h


def betai(a, b, x):
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    lb = math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b) + a * math.log(x) + b * math.log(1 - x)
    bt = math.exp(lb)
    if x < (a + 1.0) / (a + b + 2.0):
        return bt * betacf(a, b, x) / a
    return 1.0 - bt * betacf(b, a, 1.0 - x) / b


def t_p_two(t, df):
    if df <= 0:
        return 1.0
    return betai(df / 2.0, 0.5, df / (df + t * t))


def f_sf(F, d1, d2):
    """P(F > f)"""
    if F <= 0:
        return 1.0
    return betai(d2 / 2.0, d1 / 2.0, d2 / (d2 + d1 * F))


# ---------------------------------------------------------------- data
def load(pair):
    bars = {}
    for path in sorted(glob.glob(f"{DATA}/{pair}-h1-bid-*.csv")):
        with open(path, newline="") as f:
            r = csv.reader(f)
            next(r, None)
            for row in r:
                if len(row) < 5 or not row[0].strip().isdigit():
                    continue
                v = int(row[0])
                v = v * 1000 if v < 10 ** 11 else v
                try:
                    t = dt.datetime.fromtimestamp(v / 1000.0, tz=UTC).replace(minute=0, second=0, microsecond=0)
                    bars[t] = tuple(float(x) for x in row[1:5])
                except (ValueError, OverflowError, OSError):
                    continue
    return bars


def fade(o, c):
    return -math.log(c / o) * 1e4 if o > 0 and c > 0 else None


def last_real_by_day(bars):
    """derniere barre REELLE (high > low) de chaque jour UTC, garde-fou heure >= 17 UTC."""
    byday = collections.defaultdict(list)
    for t, v in bars.items():
        if v[1] > v[2] and t.weekday() < 5:
            byday[t.date()].append(t)
    out = {}
    for d, ts in byday.items():
        t = max(ts)
        if t.hour >= 17:
            out[d] = fade(bars[t][0], bars[t][3])
    return out


def weekly_increments(fd):
    """{vendredi: (fade_vendredi, mean(Mon-Jeu meme semaine), n_mt)}"""
    out = {}
    for d, f in fd.items():
        if d.weekday() != 4:
            continue
        mt = [fd.get(d - dt.timedelta(days=k)) for k in range(1, 5)]
        mt = [x for x in mt if x is not None]
        if len(mt) < 2:
            continue
        out[d] = (f, sum(mt) / len(mt), len(mt))
    return out


def monthly_cells(weekly):
    """{(annee, mois): [D_par_semaine]}  avec D = fade_vendredi - drift Mon-Jeu meme semaine."""
    cells = collections.defaultdict(list)
    for d, (f, mt, _) in weekly.items():
        cells[(d.year, d.month)].append(f - mt)
    return cells


# ---------------------------------------------------------------- tests
def year_demeaned(cellmean):
    """X = V - moyenne de l'annee (identifiabilite : on retire la derive de l'ere)."""
    byyear = collections.defaultdict(list)
    for (y, m), v in cellmean.items():
        byyear[y].append((m, v))
    X = {}
    for y, rows in byyear.items():
        if len(rows) < 6:            # annee incomplete (2026) -> exclue du test
            continue
        mu = sum(v for _, v in rows) / len(rows)
        for m, v in rows:
            X[(y, m)] = v - mu
    return X


def anova_months(X):
    bym = collections.defaultdict(list)
    for (_, m), v in X.items():
        bym[m].append(v)
    months = [m for m in range(1, 13) if len(bym[m]) >= 5]
    N = sum(len(bym[m]) for m in months)
    k = len(months)
    means = {m: sum(bym[m]) / len(bym[m]) for m in months}
    ssb = sum(len(bym[m]) * means[m] ** 2 for m in months)
    ssw = sum(sum((x - means[m]) ** 2 for x in bym[m]) for m in months)
    d1, d2 = k - 1, N - k
    F = (ssb / d1) / (ssw / d2) if ssw > 0 else 0.0
    return F, d1, d2, f_sf(F, d1, d2), means, bym, N


def month_perm_max(absval):
    """placebo : melange des mois DANS chaque annee -> distribution du max |t| sur 12 mois."""
    byyear = collections.defaultdict(list)
    for (y, m), v in absval.items():
        byyear[y].append((m, v))
    rng = random.Random(20261021)
    rnd = []
    for _ in range(2000):
        shuf = {}
        for y, rows in byyear.items():
            vals = [v for _, v in rows]
            rng.shuffle(vals)
            for (m, _), v in zip(rows, vals):
                shuf[(y, m)] = v
        bym = collections.defaultdict(list)
        for (_, m), v in shuf.items():
            bym[m].append(v)
        best = 0.0
        for m, xs in bym.items():
            if len(xs) >= 5:
                _, t = tstat(xs)
                best = max(best, abs(t))
        rnd.append(best)
    rnd.sort()
    return rnd[int(0.95 * len(rnd))], rnd[int(0.99 * len(rnd))], sum(rnd) / len(rnd)


def main():
    P("=" * W)
    P("63e (mercredi 21 oct 2026, PATTERN SAISONNIER) — LE FADE REFUGE EST-IL SAISONNIER ?")
    P("Objet : fade de la DERNIERE BARRE REELLE du vendredi - drift Mon-Jeu de la MEME semaine (crosses refuge)")
    P("fade = -log(close/open)*1e4 ; fade > 0 = SELL gagnant. Ancre CLOTURE reelle (loi du 59e).")
    P("=" * W)
    P()

    data = {p: load(p) for p in CROSSES + CONTROL}
    fd = {p: last_real_by_day(b) for p, b in data.items()}
    for p in CROSSES + CONTROL:
        b = data[p]
        dts = sorted(b)
        P(f"  {p.upper():8s} barres={len(b):7d}  {dts[0].date()} -> {dts[-1].date()}  "
          f"jours_reels={len(fd[p])}")
    P()

    wk = {p: weekly_increments(fd[p]) for p in CROSSES + CONTROL}

    # ------------------------------------------------ P0 : rejeu du 62e
    P("=" * W)
    P("SECTION 1 — P0 : REJEU DES CHIFFRES DU 62e (convention A = derniere barre reelle du jour)")
    P("-" * W)
    P(f"{'SERIE':10s} {'n ven':>7s} {'fade VEN':>18s} {'n mj':>7s} {'fade LUN-JEU':>18s} {'VEN-MJ':>8s}")
    for p in CROSSES:
        ven = [f for f, _, _ in wk[p].values()]
        mj = [fd[p][d] for d in fd[p] if d.weekday() < 4]
        mv, tv = tstat(ven)
        mm, tm = tstat(mj)
        P(f"{p.upper():10s} {len(ven):7d} {mv:+7.2f}(t{tv:+5.1f}) {len(mj):7d} {mm:+7.2f}(t{tm:+5.1f}) {mv-mm:+8.2f}")
    P("  (62e attendu : GBP_CHF ven +3.38 t+11.7 n1131 / EUR_CHF +1.93 t+10.4 / AUD_CHF +5.98 t+13.3 ;")
    P("   lun-jeu -0.99 t-8.6 / -0.67 t-9.9 / -1.29 t-7.7 — n et t doivent coller si le loader est identique)")
    P()

    # ------------------------------------------------ saisonnalite
    P("=" * W)
    P("SECTION 2 — PROFIL SAISONNIER DE L'INCREMENT (D = fade ven - drift Mon-Jeu meme semaine)")
    P("  X = D(mois) - moyenne de l'ANNEE (demeanage : retire la derive de l'ere). ANOVA F = test PRIMAIRE.")
    P("-" * W)
    pooled = collections.defaultdict(list)   # (y,m) -> [D moyennes par cross]
    for p in CROSSES:
        for (y, m), ds in monthly_cells(wk[p]).items():
            pooled[(y, m)].append(sum(ds) / len(ds))
    cellmean = {k: sum(v) / len(v) for k, v in pooled.items()}
    X = year_demeaned(cellmean)
    F, d1, d2, pval, means, bym, N = anova_months(X)

    P(f"  Cellules annee-mois : {len(cellmean)}  | retenues apres demeanage : {N} (12 mois x annees completes)")
    P(f"  ANOVA mois : F({d1},{d2}) = {F:.2f}   p = {pval:.4f}   "
      f"-> {'STRUCTURE SAISONNIERE DETECTEE' if pval < 0.05 else 'AUCUNE STRUCTURE SAISONNIERE'}")
    P()
    P(f"  {'MOIS':4s} {'n':>4s} {'effet X (bp)':>13s} {'t':>7s} {'D brut (bp)':>12s} {'mois>0':>7s}")
    rows = []
    for m in range(1, 13):
        xs = bym[m]
        if len(xs) < 5:
            continue
        mx, tx = tstat(xs)
        raw = [v for (y, mm), v in cellmean.items() if mm == m]
        mr = sum(raw) / len(raw)
        pos = sum(1 for v in raw if v > 0)
        rows.append((m, len(xs), mx, tx, mr, pos, len(raw)))
        P(f"  {MONTHS[m-1]:4s} {len(xs):4d} {mx:+13.2f} {tx:+7.2f} {mr:+12.2f} {pos:3d}/{len(raw)}")
    thr = 0.05 / 12
    P(f"  Seuil Bonferroni 0.05/12 = {thr:.5f} (|t| critique ~2.87 sur ~20 obs) -> "
      f"{sum(1 for r in rows if t_p_two(r[3], r[1]-1) < thr)} mois le franchissent")
    best = max(rows, key=lambda r: abs(r[3]))
    oct_r = next((r for r in rows if r[0] == 10), None)
    P(f"  Mois le plus extreme : {MONTHS[best[0]-1]} (t {best[3]:+.2f})  |  OCTOBRE : "
      f"{('t %+.2f, D %+.2f bp' % (oct_r[3], oct_r[4])) if oct_r else 'n/a'}")
    P()

    # placebo de permutation sur max|t|
    absx = {k: v for k, v in X.items()}
    q95, q99, mu = month_perm_max(absx)
    P(f"  PLACEBO permutation (2000 tirages, mois melanges DANS chaque annee) sur max|t| :")
    P(f"    quantile 95% = {q95:.2f} | 99% = {q99:.2f} | moyenne = {mu:.2f} | observe = {abs(best[3]):.2f}")
    P(f"    -> {'le pic observe DEPASSE le placebo' if abs(best[3]) > q99 else 'le pic observe est DANS le bruit de selection'}")
    P()

    # ------------------------------------------------ controle jambe Mon-Jeu
    P("=" * W)
    P("SECTION 3 — CONTROLE DE SPECIFICITE : la MEME analyse sur le drift Mon-Jeu (niveau, pas increment)")
    P("  Si le drift generique porte la meme saisonnalite, la saison n'est pas celle du vendredi.")
    P("-" * W)
    mt_cells = collections.defaultdict(list)
    for p in CROSSES:
        bym_p = collections.defaultdict(list)
        for d, f in fd[p].items():
            if d.weekday() < 4:
                bym_p[(d.year, d.month)].append(f)
        for k, v in bym_p.items():
            mt_cells[k].append(sum(v) / len(v))
    mt_cellmean = {k: sum(v) / len(v) for k, v in mt_cells.items()}
    Xmt = year_demeaned(mt_cellmean)
    F2, d1b, d2b, p2, means2, bym2, N2 = anova_months(Xmt)
    P(f"  ANOVA sur le drift Mon-Jeu : F({d1b},{d2b}) = {F2:.2f}  p = {p2:.4f}  (n={N2})")
    P(f"  effet par mois (bp) : " + "  ".join(f"{MONTHS[m-1]}={means2[m]:+.2f}" for m in sorted(means2)))
    P()

    # ------------------------------------------------ eres + pre-2006
    P("=" * W)
    P("SECTION 4 — STABILITE : eres et extension OANDA PRE-2006 (le test OOS le moins cher)")
    P("-" * W)
    P(f"  {'PERIODE':16s} {'n ven':>6s} {'mean D':>9s} {'t':>7s} {'mois>0':>8s} {'OCT mean':>9s} {'n OCT':>6s}")
    periods = [("PRE-2006", 2002, 2005), ("2006-2015", 2006, 2015), ("2016-2026", 2016, 2026), ("FULL", 2002, 2026)]
    for name, y0, y1 in periods:
        ds, oct_d = [], []
        for p in CROSSES:
            if name == "PRE-2006" and p == "audchf":
                y0p = 2004
            else:
                y0p = y0
            for d, (f, mt, _) in wk[p].items():
                if y0p <= d.year <= y1:
                    ds.append(f - mt)
                    if d.month == 10:
                        oct_d.append(f - mt)
        m_, t_ = tstat(ds)
        mo = sum(1 for (y, mm), v in cellmean.items() if mm != 0 and y0 <= y <= y1 and v > 0)
        tot = sum(1 for (y, mm) in cellmean if y0 <= y <= y1)
        mo_ = tstat(oct_d)[0] if oct_d else float("nan")
        P(f"  {name:16s} {len(ds):6d} {m_:+9.2f} {t_:+7.2f} {f'{mo}/{tot}':>8s} {mo_:+9.2f} {len(oct_d):6d}")
    P()

    # ------------------------------------------------ par cross + controle majeur
    P("=" * W)
    P("SECTION 5 — PAR CROSS + CONTROLE CROSS-SECTION (majeure sans jambe refuge)")
    P("-" * W)
    P(f"  {'SERIE':10s} {'n':>6s} {'mean D':>9s} {'t':>7s} {'OCT':>9s} {'nOCT':>5s} {'NOV-FEV':>9s} {'MAI-AOU':>9s}")
    for p in CROSSES + CONTROL:
        ds = [f - mt for f, mt, _ in wk[p].values()]
        oct_d = [f - mt for d, (f, mt, _) in wk[p].items() if d.month == 10]
        win = [f - mt for d, (f, mt, _) in wk[p].items() if d.month in (11, 12, 1, 2)]
        sum_ = [f - mt for d, (f, mt, _) in wk[p].items() if d.month in (5, 6, 7, 8)]
        m_, t_ = tstat(ds)
        P(f"  {p.upper():10s} {len(ds):6d} {m_:+9.2f} {t_:+7.2f} "
          f"{(tstat(oct_d)[0] if oct_d else float('nan')):+9.2f} {len(oct_d):5d} "
          f"{(tstat(win)[0] if win else float('nan')):+9.2f} {(tstat(sum_)[0] if sum_ else float('nan')):+9.2f}")
    P()

    # ------------------------------------------------ octobre, semaine par semaine
    P("=" * W)
    P("SECTION 6 — DECISIONNEL : les vendredis d'OCTOBRE, semaine par semaine (pool 3 crosses)")
    P("-" * W)
    P(f"  {'SEMAINE':14s} {'n':>4s} {'mean D':>9s}   |  detail de la semaine de deploiement")
    for wkno in range(1, 6):
        ds = []
        for p in CROSSES:
            for d, (f, mt, _) in wk[p].items():
                if d.month == 10 and (d.day - 1) // 7 + 1 == wkno:
                    ds.append(f - mt)
        if ds:
            m_, t_ = tstat(ds)
            P(f"  S{wkno} du mois   {len(ds):4d} {m_:+9.2f} (t{t_:+5.2f})")
    P("  Lecture : la semaine de deploiement (4e vendredi d'octobre) se compare a ces cellules.")
    P()

    out = "\n".join(rappel)
    print(out)
    path = f"{HOME}/projects/tb-research/reports/2026-10-21-fade-seasonality.txt"
    with open(path, "w") as f:
        f.write(out + "\n")
    print(f"\n[rapport ecrit : {path}]")


if __name__ == "__main__":
    main()
