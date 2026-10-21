#!/usr/bin/env python3
"""63e ANNEXE — trois controles qui decident si la saisonnalite trouvee est REELLE ou un artefact d'ancre.

A) ATTRIBUTION du ±1 vendredi vs le 62e (n 1130 vs 1131) : loi du 60e, tout ecart doit etre attribue.
B) VALIDITE DE L'ANCRE : distribution de l'heure de la derniere barre REELLE par mois. Si l'hiver et l'ete
   ne mesurent pas la meme heure economique, la "saison" du drift est un artefact de selection de barre.
C) DRIFT A HEURE FIXE (barre 16:00->17:00 ET) : si la saisonnalite du drift survit a une ancre identique
   en ete et en hiver, elle est reelle ; sinon c'est l'ancre (loi du 59e : la cloture suit l'heure d'ete).
D) MIROIR : l'effet mois de l'INCREMENT est-il le miroir de l'effet mois du DRIFT ? (correlation des deux
   vecteurs + stabilite par ere). Un miroir parfait signifie : la saison est dans le drift, pas dans le fade.
E) PORTEE : le drift saisonnier existe-t-il sur les MAJEURES et AVANT 2006 (OOS le moins cher) ?
"""
import csv, datetime as dt, glob, math, os, collections

HOME = os.path.expanduser("~")
DATA = f"{HOME}/projects/tb-research/data/historical/dukascopy"
UTC = dt.timezone.utc
CROSSES = ["gbpchf", "eurchf", "audchf"]
MAJORS = ["eurusd", "gbpusd", "usdjpy"]
MONTHS = ["JAN", "FEV", "MAR", "AVR", "MAI", "JUN", "JUL", "AOU", "SEP", "OCT", "NOV", "DEC"]
W = 132
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
    if abs(d) < FPMIN:
        d = FPMIN
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
    if F <= 0:
        return 1.0
    return betai(d2 / 2.0, d1 / 2.0, d2 / (d2 + d1 * F))


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


def dst_bounds(y):
    def nth_sun(m, n):
        d = dt.date(y, m, 1)
        c = 0
        while True:
            if d.weekday() == 6:
                c += 1
                if c == n:
                    return d
            d += dt.timedelta(days=1)
    return nth_sun(3, 2), nth_sun(11, 1)


def is_edt(d):
    a, b = dst_bounds(d.year)
    return a <= d < b


def by_day(bars):
    """{date: (fade derniere barre reelle, heure de cette barre)} + compteurs de rejet"""
    byday = collections.defaultdict(list)
    for t, v in bars.items():
        if v[1] > v[2] and t.weekday() < 5:
            byday[t.date()].append(t)
    out = {}
    for d, ts in byday.items():
        t = max(ts)
        out[d] = (fade(bars[t][0], bars[t][3]), t.hour, len(ts))
    return out


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
    N = sum(len(bym[m]) for m in ks)
    k = len(ks)
    means = {m: sum(bym[m]) / len(bym[m]) for m in ks}
    ssb = sum(len(bym[m]) * means[m] ** 2 for m in ks)
    ssw = sum(sum((x - means[m]) ** 2 for x in bym[m]) for m in ks)
    F = (ssb / (k - 1)) / (ssw / (N - k)) if ssw > 0 else 0.0
    return F, k - 1, N - k, f_sf(F, k - 1, N - k), means


def corr(a, b):
    n = len(a)
    ma, mb = sum(a) / n, sum(b) / n
    va = sum((x - ma) ** 2 for x in a)
    vb = sum((x - mb) ** 2 for x in b)
    if va <= 0 or vb <= 0:
        return 0.0
    return sum((x - ma) * (y - mb) for x, y in zip(a, b)) / (va * vb) ** 0.5


def main():
    P("=" * W)
    P("63e ANNEXE — L'ANCRE ET LE MIROIR : la saisonnalite est-elle dans le FADE ou dans le DRIFT ?")
    P("=" * W)
    P()
    pairs = CROSSES + MAJORS
    bars = {p: load(p) for p in pairs}
    days = {p: by_day(x) for p, x in bars.items()}

    # ---------------- A : attribution du ±1
    P("=" * W)
    P("ANNEXE A — ATTRIBUTION DU ±1 VENDREDI vs le 62e (loi du 60e : tout ecart doit etre attribue)")
    P("-" * W)
    for p in CROSSES:
        dd = days[p]
        fri = [d for d in dd if d.weekday() == 4]
        low_hour = [d for d in fri if dd[d][1] < 17]
        no_mt = []
        for d in fri:
            mt = [dd.get(d - dt.timedelta(days=k)) for k in range(1, 5)]
            mt = [x for x in mt if x is not None]
            if len(mt) < 2:
                no_mt.append(d)
        P(f"  {p.upper():8s} vendredis bruts={len(fri):5d} | rejetes heure<17 UTC={len(low_hour):2d} "
          f"| rejetes Mon-Jeu<2={len(no_mt):2d} | retenus={len(fri)-len(set(low_hour)|set(no_mt)):5d}")
        if low_hour:
            P(f"           vendredis a heure basse : {[str(d) for d in sorted(low_hour)][:6]}")
        if no_mt:
            P(f"           vendredis sans Mon-Jeu suffisant : {[str(d) for d in sorted(no_mt)][:6]}")
    P("  Attendu 62e : n ven = 1131. Un ecart de 1 doit etre nomme ci-dessus, pas explique par du bruit.")
    P()

    # ---------------- B : validite de l'ancre
    P("=" * W)
    P("ANNEXE B — VALIDITE DE L'ANCRE : heure de la DERNIERE barre reelle, par mois (ete = 20 UTC, hiver = 21)")
    P("-" * W)
    P(f"  {'MOIS':4s} " + " ".join(f"{p[:6].upper():>13s}" for p in CROSSES) + "   part_heure<20")
    for m in range(1, 13):
        cells = []
        for p in CROSSES:
            hs = [v[1] for d, v in days[p].items() if d.month == m]
            cells.append(sum(hs) / len(hs) if hs else float("nan"))
        low = sum(1 for p in CROSSES for d, v in days[p].items() if d.month == m and v[1] < 20)
        tot = sum(1 for p in CROSSES for d, v in days[p].items() if d.month == m)
        P(f"  {MONTHS[m-1]:4s} " + " ".join(f"{c:13.3f}" for c in cells) + f"   {100.0*low/max(tot,1):5.1f}%")
    P("  Lecture : une moyenne qui glisse entre ete et hiver est NORMALE (la cloture suit l'heure d'ete).")
    P("  Le danger est une part d'heure < 20 UTC qui explose sur certains mois (selection de barre).")
    P()

    # ---------------- C : drift a heure fixe ET
    P("=" * W)
    P("ANNEXE C — DRIFT A HEURE FIXE : barre 16:00->17:00 ET (20 UTC en EDT, 21 UTC en EST), identique ete/hiver")
    P("-" * W)
    fixed = {}
    for p in CROSSES + MAJORS:
        b = bars[p]
        cells = collections.defaultdict(list)
        for t, v in b.items():
            if v[1] <= v[2] or t.weekday() > 4:
                continue
            dh = 20 if is_edt(t.date()) else 21
            if t.hour != dh:
                continue
            f = fade(v[0], v[3])
            if f is not None:
                cells[(t.year, t.month)].append(f)
        fixed[p] = {k: sum(v) / len(v) for k, v in cells.items() if len(v) >= 8}
    P(f"  {'SERIE':8s} {'n mois':>7s} {'ANOVA F(11,..)':>16s} {'p':>8s}   effet par mois (bp)")
    for p in CROSSES + MAJORS:
        X = year_demean(fixed[p])
        F, d1, d2, pv, means = anova(X)
        prof = " ".join(f"{MONTHS[m-1][0]}{means[m]:+.1f}" for m in sorted(means))
        P(f"  {p.upper():8s} {len(X):7d} {f'F({d1},{d2})={F:.2f}':>16s} {pv:8.4f}   {prof}")
    P()

    # ---------------- D : miroir
    P("=" * W)
    P("ANNEXE D — MIROIR : effet mois de l'INCREMENT (fade ven - drift) contre effet mois du DRIFT")
    P("-" * W)
    # increment par mois (recalcul)
    incr_cells, mt_cells = collections.defaultdict(list), collections.defaultdict(list)
    for p in CROSSES:
        dd = days[p]
        wk = {}
        for d, (f, h, n) in dd.items():
            if d.weekday() != 4 or h < 17:
                continue
            mt = [dd.get(d - dt.timedelta(days=k)) for k in range(1, 5)]
            mt = [x[0] for x in mt if x is not None]
            if len(mt) < 2:
                continue
            wk[d] = f - sum(mt) / len(mt)
        for d, v in wk.items():
            incr_cells[(d.year, d.month)].append(v)
        mtp = collections.defaultdict(list)
        for d, (f, h, n) in dd.items():
            if d.weekday() < 4 and h >= 17:
                mtp[(d.year, d.month)].append(f)
        for k, v in mtp.items():
            mt_cells[k].append(sum(v) / len(v))
    incr_cm = {k: sum(v) / len(v) for k, v in incr_cells.items()}
    mt_cm = {k: sum(v) / len(v) for k, v in mt_cells.items()}
    Xi, Xm = year_demean(incr_cm), year_demean(mt_cm)
    Fi, _, _, pi, mi = anova(Xi)
    Fm, _, _, pm, mm = anova(Xm)
    ks = sorted(set(mi) & set(mm))
    vi = [mi[m] for m in ks]
    vm = [mm[m] for m in ks]
    P(f"  ANOVA increment : F={Fi:.2f} p={pi:.4f}   |   ANOVA drift : F={Fm:.2f} p={pm:.6f}")
    P(f"  correlation des 12 effets-mois (increment vs drift) : r = {corr(vi, vm):+.3f}")
    P(f"  {'MOIS':4s} {'effet INCR':>11s} {'effet DRIFT':>12s}")
    for m in ks:
        P(f"  {MONTHS[m-1]:4s} {mi[m]:+11.2f} {mm[m]:+12.2f}")
    P("  Lecture : si r est fortement negatif, la 'saison' de l'increment est le MIROIR MECANIQUE de celle")
    P("  du drift (D = fade_ven - drift) -> la saison est dans le drift, pas dans le fade.")
    P()
    # stabilite de la forme par ere
    P("  Stabilite de la FORME par ere (effets-mois demeanes par annee, 3 crosses) :")
    for name, y0, y1 in [("2006-2015", 2006, 2015), ("2016-2026", 2016, 2026)]:
        sub = {k: v for k, v in Xi.items() if y0 <= k[0] <= y1}
        _, _, _, _, mmi = anova(sub)
        subf = {k: v for k, v in Xm.items() if y0 <= k[0] <= y1}
        _, _, _, _, mmm = anova(subf)
        ks2 = sorted(set(mmi) & set(mmm))
        P(f"    {name}: r(increment,drift) = {corr([mmi[m] for m in ks2], [mmm[m] for m in ks2]):+.3f} "
          f"| pic increment {MONTHS[max(mmi, key=lambda m: abs(mmi[m]))-1]} "
          f"| pic drift {MONTHS[max(mmm, key=lambda m: abs(mmm[m]))-1]}")
    P()

    # ---------------- E : portee majeures + pre-2006
    P("=" * W)
    P("ANNEXE E — PORTEE : le drift saisonnier existe-t-il sur les MAJEURES et AVANT 2006 ?")
    P("-" * W)
    P(f"  {'SERIE':8s} {'periode':12s} {'n mois':>7s} {'NOV-FEV':>9s} {'MAI-AOU':>9s} {'ecart':>8s}")
    for p in CROSSES + MAJORS:
        for name, y0, y1 in [("PRE-2006", 2002, 2005), ("2006-2015", 2006, 2015),
                             ("2016-2026", 2016, 2026), ("FULL", 2002, 2026)]:
            xs = {k: v for k, v in mt_cm.items() if y0 <= k[0] <= y1}
            w = [v for k, v in xs.items() if k[1] in (11, 12, 1, 2)]
            s = [v for k, v in xs.items() if k[1] in (5, 6, 7, 8)]
            if len(w) < 6 or len(s) < 6:
                continue
            mw, tw = tstat(w)
            ms, ts = tstat(s)
            P(f"  {p.upper():8s} {name:12s} {len(xs):7d} {mw:+9.2f} {ms:+9.2f} {mw-ms:+8.2f}"
              f"   (t {tw:+.1f} / {ts:+.1f})")
        P()

    out = "\n".join(R)
    print(out)
    path = f"{HOME}/projects/tb-research/reports/2026-10-21-fade-seasonality-annexe.txt"
    with open(path, "w") as f:
        f.write(out + "\n")
    print(f"\n[rapport ecrit : {path}]")


if __name__ == "__main__":
    main()
