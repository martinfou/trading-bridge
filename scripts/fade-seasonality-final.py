#!/usr/bin/env python3
"""63e — LE FADE REFUGE EST-IL SAISONNIER ? (version consolidee, ancre CLOTURE)

Ce run remplace fade-seasonality.py / -annexe.py dont l'ancre etait la "derniere barre reelle du jour UTC".
CONTROLE DE DONNEES QUI A CHANGE L'OBJET : les CSV sont une grille 24x7 REMPLIE (barres presentes chaque
samedi et chaque dimanche), donc "derniere barre reelle du jour" tombe a 23:00 UTC sur ~80% des jours
- c'est-a-dire APRES la cloture du vendredi (21:00 UTC en EDT, 22:00 en EST). L'ancre correcte et
tradeable est la barre qui SE TERMINE a 17:00 ET : elle demarre a 20:00 UTC en ete et 21:00 UTC en hiver.

Objet : fade = -log(close/open)*1e4 a l'ancre cloture ; fade > 0 = SELL gagnant.
Increment D = fade(vendredi) - moyenne fade(lun-jeu de la MEME semaine)  -> retire le drift generique.
Test PRIMAIRE = ANOVA F sur l'effet mois, apres demeanage par ANNEE (identifiabilite : un mois qui repete
la derive de l'ere n'est pas une saison). Controles : placebo de permutation, jambe Mon-Jeu, eres,
OANDA pre-2006, controle cross-section (EUR_USD), et MIROIR drift<->increment.
"""
import csv, datetime as dt, glob, math, os, random, statistics as st, collections

HOME = os.path.expanduser("~")
DATA = f"{HOME}/projects/tb-research/data/historical/dukascopy"
UTC = dt.timezone.utc
CROSSES = ["gbpchf", "eurchf", "audchf"]
CONTROL = ["eurusd"]
MONTHS = ["JAN", "FEV", "MAR", "AVR", "MAI", "JUN", "JUL", "AOU", "SEP", "OCT", "NOV", "DEC"]
W = 134
R = []


def P(s=""):
    R.append(s)


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
    d = FPMIN if abs(d) < FPMIN else d
    d = 1.0 / d
    h = d
    for m in range(1, MAXIT + 1):
        m2 = 2 * m
        aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1.0 + aa * d
        d = FPMIN if abs(d) < FPMIN else d
        c = 1.0 + aa / c
        c = FPMIN if abs(c) < FPMIN else c
        d = 1.0 / d
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1.0 + aa * d
        d = FPMIN if abs(d) < FPMIN else d
        c = 1.0 + aa / c
        c = FPMIN if abs(c) < FPMIN else c
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


def f_sf(F, d1, d2):
    return 1.0 if F <= 0 else betai(d2 / 2.0, d1 / 2.0, d2 / (d2 + d1 * F))


def t_p(t, df):
    return betai(df / 2.0, 0.5, df / (df + t * t)) if df > 0 else 1.0


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
                    bars.setdefault(t, tuple(float(x) for x in row[1:5]))
                except (ValueError, OverflowError, OSError):
                    continue
    return bars


def fade(o, c):
    return -math.log(c / o) * 1e4 if o > 0 and c > 0 else None


def is_edt(d):
    def nth(m, n):
        x = dt.date(d.year, m, 1)
        c = 0
        while True:
            if x.weekday() == 6:
                c += 1
                if c == n:
                    return x
            x += dt.timedelta(days=1)
    a, b = nth(3, 2), nth(11, 1)
    return a <= d < b


def close_start(d):
    """timestamp de la barre qui se termine a 17:00 ET : 20:00 UTC en EDT, 21:00 en EST."""
    return dt.datetime.combine(d, dt.time(20 if is_edt(d) else 21, 0), tzinfo=UTC)


def series(pair):
    b = load(pair)
    out = {}
    for t, v in b.items():
        if t.weekday() > 4 or v[1] <= v[2]:
            continue
        d = t.date()
        if t == close_start(d):
            f = fade(v[0], v[3])
            if f is not None:
                out[d] = f
    return b, out


def year_demean(cellmean, minrows=6):
    byyear = collections.defaultdict(list)
    for (y, m), v in cellmean.items():
        byyear[y].append((m, v))
    X = {}
    for y, rows in byyear.items():
        if len(rows) < minrows:
            continue
        mu = sum(v for _, v in rows) / len(rows)
        for m, v in rows:
            X[(y, m)] = v - mu
    return X


def anova(X):
    bym = collections.defaultdict(list)
    for (_, m), v in X.items():
        bym[m].append(v)
    ks = [m for m in range(1, 13) if len(bym[m]) >= 5]
    N, k = sum(len(bym[m]) for m in ks), len(ks)
    means = {m: sum(bym[m]) / len(bym[m]) for m in ks}
    ssb = sum(len(bym[m]) * means[m] ** 2 for m in ks)
    ssw = sum(sum((x - means[m]) ** 2 for x in bym[m]) for m in ks)
    F = (ssb / (k - 1)) / (ssw / (N - k)) if ssw > 0 else 0.0
    return F, k - 1, N - k, f_sf(F, k - 1, N - k), means, bym, N


def perm_max(X, n=2000, seed=20261021):
    byyear = collections.defaultdict(list)
    for (y, m), v in X.items():
        byyear[y].append((m, v))
    rng = random.Random(seed)
    out = []
    for _ in range(n):
        sh = {}
        for y, rows in byyear.items():
            vals = [v for _, v in rows]
            rng.shuffle(vals)
            for (m, _), v in zip(rows, vals):
                sh[(y, m)] = v
        bym = collections.defaultdict(list)
        for (_, m), v in sh.items():
            bym[m].append(v)
        best = max(abs(tstat(x)[1]) for x in bym.values() if len(x) >= 5)
        out.append(best)
    out.sort()
    return out[int(0.95 * n)], out[int(0.99 * n)]


def corr(a, b):
    n = len(a)
    ma, mb = sum(a) / n, sum(b) / n
    va = sum((x - ma) ** 2 for x in a)
    vb = sum((x - mb) ** 2 for x in b)
    return sum((x - ma) * (y - mb) for x, y in zip(a, b)) / (va * vb) ** 0.5 if va > 0 and vb > 0 else 0.0


def main():
    P("=" * W)
    P("63e (mercredi 21 oct 2026, PATTERN SAISONNIER) — LE FADE REFUGE EST-IL SAISONNIER ?")
    P("Ancre = barre qui SE TERMINE a 17:00 ET (20:00 UTC en EDT, 21:00 en EST). fade>0 = SELL gagnant.")
    P("Increment D = fade(vendredi) - fade(lun-jeu meme semaine). Test primaire = ANOVA mois + demeanage annee.")
    P("=" * W)
    P()

    raw, S = {}, {}
    for p in CROSSES + CONTROL:
        raw[p], S[p] = series(p)
        ts = sorted(raw[p])
        P(f"  {p.upper():8s} n_barres={len(raw[p]):7d}  {ts[0].date()} -> {ts[-1].date()}  "
          f"jours_ancre={len(S[p])}")
    P()

    # ---------------- 1. structure des donnees
    P("=" * W)
    P("SECTION 1 — CONTROLE DE DONNEES : la grille est 24x7 remplie, et ou se situe la DERNIERE barre reelle ?")
    P("-" * W)
    P(f"  {'SERIE':8s} {'sam/dim':>9s} {'barres 23UTC':>13s} {'ven dernier reelle':>19s}")
    for p in CROSSES + CONTROL:
        b = raw[p]
        we = sum(1 for t in b if t.weekday() >= 5)
        h23 = sum(1 for t in b if t.weekday() < 5 and t.hour == 23)
        ven = collections.defaultdict(list)
        for t, v in b.items():
            if t.weekday() == 4 and v[1] > v[2]:
                ven[t.date()].append(t.hour)
        top = collections.Counter(max(v) for v in ven.values()).most_common(3)
        P(f"  {p.upper():8s} {we:9d} {h23:13d}   {str(top):>19s}")
    P("  Lecture : des barres de samedi/dimanche = grille REMPLIE. La 'derniere barre reelle du jour UTC'")
    P("  tombe alors apres la cloture du vendredi -> l'ancre doit etre la barre 17:00 ET, pas l'heure fixe.")
    P()

    # ---------------- 2. coherence des deux ancres
    P("=" * W)
    P("SECTION 2 — L'ANCRE CHANGE-T-ELLE LE FADE ? comparaison barre 17:00 ET vs derniere barre reelle du jour")
    P("-" * W)
    P(f"  {'SERIE':8s} {'n ven (17ET)':>13s} {'fade VEN 17ET':>15s} {'fade VEN dern.reelle':>21s} {'ecart':>7s}")
    for p in CROSSES:
        b = raw[p]
        last = collections.defaultdict(list)
        for t, v in b.items():
            if t.weekday() == 4 and v[1] > v[2]:
                last[t.date()].append(t)
        alt = [fade(b[max(v)][0], b[max(v)][3]) for d, v in last.items() if d in S[p]]
        alt = [x for x in alt if x is not None]
        m1, t1 = tstat([S[p][d] for d in S[p] if d.weekday() == 4])
        m2, _ = tstat(alt)
        P(f"  {p.upper():8s} {len([d for d in S[p] if d.weekday()==4]):13d} {m1:+8.2f}(t{t1:+5.1f}) "
          f"{m2:+21.2f} {m1-m2:+7.2f}")
    P("  Si l'ecart est ~0, l'ancre n'est pas le probleme et les runs 59e->62e ne sont pas invalides par elle.")
    P()

    # ---------------- 3. saisonnalite de l'increment
    P("=" * W)
    P("SECTION 3 — SAISONNALITE DE L'INCREMENT (D), pool 3 crosses refuge")
    P("-" * W)
    incr_cells = collections.defaultdict(list)
    drift_cells = collections.defaultdict(list)
    for p in CROSSES:
        wk = {}
        for d in S[p]:
            if d.weekday() != 4:
                continue
            mt = [S[p].get(d - dt.timedelta(days=k)) for k in range(1, 5)]
            mt = [x for x in mt if x is not None]
            if len(mt) < 3:
                continue
            wk[d] = S[p][d] - sum(mt) / len(mt)
        for d, v in wk.items():
            incr_cells[(d.year, d.month)].append(v)
        mtc = collections.defaultdict(list)
        for d, f in S[p].items():
            if d.weekday() < 4:
                mtc[(d.year, d.month)].append(f)
        for k, v in mtc.items():
            drift_cells[k].append(sum(v) / len(v))
    inc_cm = {k: sum(v) / len(v) for k, v in incr_cells.items()}
    dr_cm = {k: sum(v) / len(v) for k, v in drift_cells.items()}
    Xi, Xd = year_demean(inc_cm), year_demean(dr_cm)
    Fi, d1i, d2i, pi, mi, bymi, Ni = anova(Xi)
    Fd, d1d, d2d, pd, md, bymd, Nd = anova(Xd)

    P(f"  Cellules annee-mois : {len(inc_cm)} | retenues : {Ni}")
    P(f"  ANOVA INCREMENT : F({d1i},{d2i}) = {Fi:.2f}  p = {pi:.4f}  -> "
      f"{'structure detectee' if pi < 0.05 else 'AUCUNE structure'}")
    P(f"  ANOVA DRIFT (controle) : F({d1d},{d2d}) = {Fd:.2f}  p = {pd:.2e}")
    P()
    P(f"  {'MOIS':4s} {'n':>4s} {'X incr (bp)':>12s} {'t':>7s} {'D brut':>8s} {'>0':>6s}   {'X drift':>9s}")
    rows = []
    for m in range(1, 13):
        xs = bymi.get(m, [])
        if len(xs) < 5:
            continue
        mx, tx = tstat(xs)
        raw_m = [v for (y, mm), v in inc_cm.items() if mm == m]
        pos = sum(1 for v in raw_m if v > 0)
        rows.append((m, len(xs), mx, tx, sum(raw_m) / len(raw_m), pos, len(raw_m)))
        P(f"  {MONTHS[m-1]:4s} {len(xs):4d} {mx:+12.2f} {tx:+7.2f} {sum(raw_m)/len(raw_m):+8.2f} "
          f"{pos:3d}/{len(raw_m):<3d} {md.get(m, float('nan')):+9.2f}")
    bonf = 0.05 / 12
    npass = sum(1 for r in rows if t_p(r[3], r[1] - 1) < bonf)
    best = max(rows, key=lambda r: abs(r[3]))
    P(f"  Bonferroni 0.05/12 -> {npass} mois franchissent. Pic : {MONTHS[best[0]-1]} (t {best[3]:+.2f}).")
    lowest = min(rows, key=lambda r: r[4])
    P(f"  Mois le plus FAIBLE en D brut : {MONTHS[lowest[0]-1]} ({lowest[4]:+.2f} bp) | "
      f"mois NEGATIFS : {[MONTHS[r[0]-1] for r in rows if r[4] < 0] or 'aucun'}")
    q95, q99 = perm_max(Xi, 2000)
    P(f"  PLACEBO permutation (2000 tirages, mois melanges dans chaque annee) : max|t| q95={q95:.2f} "
      f"q99={q99:.2f} vs observe {abs(best[3]):.2f} "
      f"-> {'AU-DELA du bruit' if abs(best[3]) > q99 else 'DANS le bruit de selection'}")
    P()

    # ---------------- 4. miroir
    P("=" * W)
    P("SECTION 4 — MIROIR : la saison de l'increment n'est-elle que la saison du DRIFT ?")
    P("-" * W)
    ks = sorted(set(mi) & set(md))
    P(f"  r(effet mois increment, effet mois drift) = {corr([mi[m] for m in ks], [md[m] for m in ks]):+.3f}")
    for name, y0, y1 in [("2006-2015", 2006, 2015), ("2016-2026", 2016, 2026)]:
        sub_i = {k: v for k, v in Xi.items() if y0 <= k[0] <= y1}
        sub_d = {k: v for k, v in Xd.items() if y0 <= k[0] <= y1}
        _, _, _, _, m_i, _, _ = anova(sub_i)
        _, _, _, _, m_d, _, _ = anova(sub_d)
        kk = sorted(set(m_i) & set(m_d))
        P(f"  {name} : r = {corr([m_i[m] for m in kk], [m_d[m] for m in kk]):+.3f} | "
          f"pic increment {MONTHS[max(m_i, key=lambda m: abs(m_i[m]))-1]} | "
          f"pic drift {MONTHS[max(m_d, key=lambda m: abs(m_d[m]))-1]}")
    P("  r fortement negatif = la saison de D est le miroir mecanique de celle du drift.")
    P()

    # ---------------- 5. eres + pre-2006
    P("=" * W)
    P("SECTION 5 — STABILITE : eres, et OANDA PRE-2006 (annees jamais utilisees par le pipeline)")
    P("-" * W)
    P(f"  {'PERIODE':12s} {'n ven':>6s} {'D moyen':>9s} {'t':>7s} {'mois D>0':>9s} {'OCT':>8s} {'nOCT':>5s}")
    for name, y0, y1 in [("PRE-2006", 2002, 2005), ("2006-2015", 2006, 2015),
                         ("2016-2026", 2016, 2026), ("FULL", 2002, 2026)]:
        ds, oc = [], []
        for p in CROSSES:
            start = 2004 if (name == "PRE-2006" and p == "audchf") else y0
            for d in S[p]:
                if d.weekday() != 4 or not (start <= d.year <= y1):
                    continue
                mt = [S[p].get(d - dt.timedelta(days=k)) for k in range(1, 5)]
                mt = [x for x in mt if x is not None]
                if len(mt) < 3:
                    continue
                v = S[p][d] - sum(mt) / len(mt)
                ds.append(v)
                if d.month == 10:
                    oc.append(v)
        m_, t_ = tstat(ds)
        cells = [(k, v) for k, v in inc_cm.items() if y0 <= k[0] <= y1]
        P(f"  {name:12s} {len(ds):6d} {m_:+9.2f} {t_:+7.2f} "
          f"{sum(1 for _, v in cells if v>0):3d}/{len(cells):<5d} "
          f"{(tstat(oc)[0] if oc else float('nan')):+8.2f} {len(oc):5d}")
    P()

    # ---------------- 6. par cross + controle
    P("=" * W)
    P("SECTION 6 — PAR CROSS + CONTROLE CROSS-SECTION (EUR_USD : pas de jambe refuge)")
    P("-" * W)
    P(f"  {'SERIE':8s} {'n ven':>6s} {'D moyen':>9s} {'t':>7s} {'OCT':>8s} {'NOV-FEV':>9s} {'MAI-AOU':>9s}")
    for p in CROSSES + CONTROL:
        ds, oc, wnt, smer = [], [], [], []
        for d in S[p]:
            if d.weekday() != 4:
                continue
            mt = [S[p].get(d - dt.timedelta(days=k)) for k in range(1, 5)]
            mt = [x for x in mt if x is not None]
            if len(mt) < 3:
                continue
            v = S[p][d] - sum(mt) / len(mt)
            ds.append(v)
            if d.month == 10:
                oc.append(v)
            if d.month in (11, 12, 1, 2):
                wnt.append(v)
            if d.month in (5, 6, 7, 8):
                smer.append(v)
        m_, t_ = tstat(ds)
        P(f"  {p.upper():8s} {len(ds):6d} {m_:+9.2f} {t_:+7.2f} "
          f"{(tstat(oc)[0] if oc else float('nan')):+8.2f} "
          f"{(tstat(wnt)[0] if wnt else float('nan')):+9.2f} "
          f"{(tstat(smer)[0] if smer else float('nan')):+9.2f}")
    P()

    # ---------------- 7. octobre par semaine
    P("=" * W)
    P("SECTION 7 — DECISIONNEL : les vendredis d'OCTOBRE par semaine du mois (pool 3 crosses)")
    P("-" * W)
    P(f"  {'SEMAINE':10s} {'n':>4s} {'D moyen':>9s} {'t':>7s}")
    for k in range(1, 6):
        ds = []
        for p in CROSSES:
            for d in S[p]:
                if d.weekday() != 4 or d.month != 10 or (d.day - 1) // 7 + 1 != k:
                    continue
                mt = [S[p].get(d - dt.timedelta(days=j)) for j in range(1, 5)]
                mt = [x for x in mt if x is not None]
                if len(mt) < 3:
                    continue
                ds.append(S[p][d] - sum(mt) / len(mt))
        if ds:
            m_, t_ = tstat(ds)
            P(f"  S{k} d'octobre {len(ds):4d} {m_:+9.2f} {t_:+7.2f}")
    P(f"  Repère : moyenne FULL de D = {tstat([v for v in inc_cm.values()])[0]:+.2f} bp (cellules annee-mois).")
    P()

    out = "\n".join(R)
    print(out)
    path = f"{HOME}/projects/tb-research/reports/2026-10-21-fade-seasonality-final.txt"
    with open(path, "w") as f:
        f.write(out + "\n")
    print(f"\n[rapport ecrit : {path}]")


if __name__ == "__main__":
    main()
