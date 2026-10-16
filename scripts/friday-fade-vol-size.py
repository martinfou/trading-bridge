#!/usr/bin/env python3
"""59e (vendredi 16 oct 2026) — DEEP DIVE : le modulateur de VOL est-il un OVERLAY de TAILLE
utile sur la FENETRE VIVANTE du fade refuge (13:00 -> 20:00 UTC) ?

Contexte (a trancher, action ouverte du 58e) :
  - 43e/47e : sur l'ancre 24 h (VEN 21:00 -> LUN 21:00, majeures), le modulateur de vol se monetise
    en TAILLE (2u en vol haute) : +27.6 % a exposition EGALE, WF stable 1.20 -> 1.28.
  - 58e (bloc C) : sur la fenetre NY [13,21) des CROSSES REFUGE, le MEME modulateur lu avec le seul
    proxy EQ (vol S&P) donne un mono_r NEGATIF en bp ET en z (-0.86 / -0.79 GBP_CHF) => soupcon
    d'un 5e "la metrique fabrique le verdict".
  - 45e : le meilleur proxy change avec la paire (EQ sur GBP_JPY, OWN sur GBP_USD) => quand le
    gagnant change d'une paire a l'autre, ce n'est pas UN phenomene mais un CHOIX de VARIABLE.
  - Martin (15 oct) : la lane vivante migre sur les crosses refuge, entree 13:00 UTC, sortie 20:00 UTC.

Question posee aujourd'hui :
  Le modulateur de vol est-il un modulateur de TAILLE legitime pour la FENETRE VIVANTE, quand on
  balaie les 4 proxies (EQ / OWN / AND / MEAN) sur les 6 paires, avec les controles obligatoires du
  pipeline : ORDRE des quintiles (gradient vs selection vs U), benchmark de levier uniforme a
  exposition EGALE **par periode** (FULL / IS / OOS), validation en bp ET en z, placebo Mon-Jeu,
  controle de fenetre, plateau de seuils, et la stabilite du proxy d'une paire a l'autre.

Aucune ecriture dans le repo Java. Donnees : FX = 3 crosses OANDA H1 bid + 3 majeures Dukascopy H1 bid ;
actions = data/historical/futures/MES_D1.csv (S&P 500 mini). Coûts = demi-spreads MESURES par paire (55e).
"""
import bisect
import csv
import datetime as dt
import glob
import math
import os
import statistics as st

HOME = os.path.expanduser("~")
DATA = f"{HOME}/projects/tb-research/data/historical/dukascopy"
FUT = f"{HOME}/projects/tb-research/data/historical/futures"
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

# Coût A/R en bp par paire (55e / retro 10 oct : demi-spreads MESURES, jamais inventes).
COST_BP = {"GBP_CHF": 2.278, "EUR_CHF": 1.702, "AUD_CHF": 2.584,
           "GBP_JPY": 1.630, "GBP_USD": 2.140, "USD_CAD": 2.140}

# References publiees (57e) — RETOURS BRUTS du vendredi, ancre 21:00 UTC (brut < 0 = vendredi baissier).
REF_FRI = {"GBP_CHF": -7.43, "EUR_CHF": -4.50, "AUD_CHF": -8.79,
           "GBP_JPY": -4.36, "GBP_USD": -5.10, "USD_CAD": +2.19}
# Composante NY [13,21) du vendredi (58e, retour brut).
REF_NY_FRI = {"GBP_CHF": -5.08, "EUR_CHF": -3.59, "AUD_CHF": -7.20,
              "GBP_JPY": +0.04, "GBP_USD": -0.62, "USD_CAD": -0.88}

H_IN, H_OUT = 13, 20          # fenetre VIVANTE (decision Martin du 15 oct : 13:00 -> 20:00 UTC)
ERAS = [(2006, 2010), (2010, 2015), (2015, 2020), (2020, 2027)]


# ------------------------------------------------------------------ chargement FX
def major_files(sym):
    base = sym.replace("_", "").lower()
    one = f"{DATA}/{base}-h1-bid-2006-2026.csv"
    if os.path.exists(one):
        return [one]
    return sorted(p for p in glob.glob(f"{DATA}/{base}-h1-bid-*.csv") if "oanda" not in p)


def load(p):
    files = p if isinstance(p, list) else [p]
    out = {}
    for path in files:
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
                    out[t] = float(row[4])
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


def median(xs):
    return st.median(xs) if xs else 0.0


def welch(a, b):
    """Difference (a - b) et t de Welch."""
    if len(a) < 3 or len(b) < 3:
        return 0.0, 0.0
    ma, mb = sum(a) / len(a), sum(b) / len(b)
    va = sum((x - ma) ** 2 for x in a) / (len(a) - 1)
    vb = sum((x - mb) ** 2 for x in b) / (len(b) - 1)
    se = (va / len(a) + vb / len(b)) ** 0.5
    return ma - mb, ((ma - mb) / se if se > 0 else 0.0)


def pearson(xs, ys):
    n = min(len(xs), len(ys))
    if n < 3:
        return 0.0
    mx, my = sum(xs) / n, sum(ys) / n
    sx = sum((x - mx) ** 2 for x in xs) ** 0.5
    sy = sum((y - my) ** 2 for y in ys) ** 0.5
    if sx == 0 or sy == 0:
        return 0.0
    return sum((xs[i] - mx) * (ys[i] - my) for i in range(n)) / (sx * sy)


def quintiles(pairs):
    """[(score, valeur)] -> 5 buckets par quantiles du SCORE (jamais de la valeur)."""
    vals = sorted(v for v, _ in pairs)
    if len(vals) < 25:
        return None
    cuts = [vals[int(len(vals) * k / 5)] for k in (1, 2, 3, 4)]
    out = [[], [], [], [], []]
    for v, f in pairs:
        k = 0
        while k < 4 and v >= cuts[k]:
            k += 1
        out[k].append(f)
    return out


def mono(qs):
    return pearson(list(range(5)), [tstat(q)[0] for q in qs])


# ------------------------------------------------------------------ prix / fenetres
def price_at(px, day, hour):
    """Prix a l'instant hour:00 UTC = close de la barre ouverte a hour-1 (timestamps = DEBUT de barre)."""
    if hour == 0:
        t = dt.datetime.combine(day - dt.timedelta(days=1), dt.time(23, 0), tzinfo=UTC)
    else:
        t = dt.datetime.combine(day, dt.time(hour - 1, 0), tzinfo=UTC)
    return px.get(t)


def daily_returns(px, hour, max_hold=96.0):
    """[(date, weekday, bp, hold_h)] — snapshot a `hour` UTC, lun-ven, trous > max_hold exclus."""
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


def window_returns(px, h0, h1):
    """{(date, weekday): bp BRUT} de la fenetre intraday [h0, h1) du meme jour UTC — hold egal."""
    out = {}
    d = W0.date()
    while d < W1.date():
        if d.weekday() < 5:
            a, b = price_at(px, d, h0), price_at(px, d, h1)
            if a is not None and b is not None and a > 0:
                out[(d, d.weekday())] = math.log(b / a) * 1e4
        d += dt.timedelta(days=1)
    return out


def bar_ret(px, day, hour):
    """Retour BRUT (bp) de la barre [hour, hour+1) du jour UTC `day`."""
    a, b = price_at(px, day, hour), price_at(px, day, hour + 1)
    if a is None or b is None or a <= 0:
        return None
    return math.log(b / a) * 1e4


# ------------------------------------------------------------------ percentile de vol CAUSAL
class VolPct:
    """Percentile de vol CAUSAL : vol = ecart-type des 21 derniers retours STRICTEMENT anterieurs
    a la date ; percentile = part des vols des <=500 observations STRICTEMENT anterieures qui sont
    inferieures. Aucun look-ahead : l'etat d'un vendredi n'utilise que des closes du jeudi 21:00 UTC."""

    def __init__(self, date_ret):
        rows = sorted((d, r) for d, r in date_ret.items() if r is not None)
        self.dates = [d for d, _ in rows]
        rets = [r for _, r in rows]
        self.pct = {}
        vols = [None] * len(rows)
        for i in range(len(rows)):
            if i >= 21:
                w = rets[i - 21:i]
                if len(w) >= 15:
                    vols[i] = st.pstdev(w)
        for i in range(len(rows)):
            if vols[i] is None:
                continue
            past = [v for v in vols[max(0, i - 500):i] if v is not None]
            if len(past) >= 100:
                self.pct[self.dates[i]] = 100.0 * sum(1 for v in past if v < vols[i]) / len(past)

    def asof(self, d):
        """Dernier percentile STRICTEMENT anterieur a la date d."""
        i = bisect.bisect_left(self.dates, d) - 1
        while i >= 0:
            p = self.pct.get(self.dates[i])
            if p is not None:
                return p
            i -= 1
        return None

    def drift(self):
        """Part des observations en vol haute (>60) par ere — le conditionnant derive-t-il ?"""
        out = []
        for a, b in ERAS:
            xs = [p for d, p in self.pct.items() if dt.date(a, 1, 1) <= d < dt.date(b, 1, 1)]
            out.append((a, b, 100.0 * sum(1 for v in xs if v > 60) / len(xs), len(xs)))
        return out


def eq_daily_returns(path):
    """date -> retour bp des closes D1 actions (convention : barre de date D = seance qui finit le jour D)."""
    rows = []
    with open(path, newline="") as f:
        r = csv.reader(f)
        next(r)
        for row in r:
            if len(row) < 5:
                continue
            try:
                rows.append((dt.date.fromisoformat(row[0]), float(row[4])))
            except ValueError:
                continue
    rows.sort()
    out = {}
    for i in range(1, len(rows)):
        c0, c1 = rows[i - 1][1], rows[i][1]
        if c0 > 0 and c1 > 0:
            out[rows[i][0]] = math.log(c1 / c0) * 1e4
    return out


# ------------------------------------------------------------------ evaluation d'un overlay de TAILLE
def evaluate(sample, thresh, high_mult=2.0):
    """sample = [(score, net_bp)] ; net_bp = fade - coût (par unite).
    Retour : (delta bp/unite a exposition EGALE, net/unite de l'overlay, net/unite du FLAT, n, nH)."""
    if len(sample) < 40:
        return None
    sizes = [(high_mult if s >= thresh else 1.0, net) for s, net in sample]
    tot_s = sum(s for s, _ in sizes)
    ovr = sum(s * net for s, net in sizes) / tot_s
    flat = sum(net for _, net in sample) / len(sample)
    nH = sum(1 for s, _ in sample if s >= thresh)
    return ovr - flat, ovr, flat, len(sample), nH


def per_period(sample_dated):
    """sample_dated = [(date, score, net_bp)] -> dict periode -> liste."""
    out = {"FULL": [], "IS 06-15": [], "OOS 16-26": []}
    for d, s, n in sample_dated:
        out["FULL"].append((s, n))
        (out["IS 06-15"] if d < dt.date(2016, 1, 1) else out["OOS 16-26"]).append((s, n))
    return out


# ------------------------------------------------------------------ main
def main():
    rep = []
    P = rep.append
    P("=" * 118)
    P("59e (vendredi 16 oct 2026) — DEEP DIVE : le modulateur de VOL est-il un OVERLAY de TAILLE")
    P(f"utile sur la FENETRE VIVANTE {H_IN:02d}:00 -> {H_OUT:02d}:00 UTC du fade refuge ?")
    P("Fenetre 2006-01-01 -> 2026-05-20 UTC. Hold intraday (0 nuit de rollover) => net = fade - coût A/R.")
    P("Convention : on publie le FADE (SELL) ; fade > 0 = vendredi baissier = SELL gagnant.")
    P("=" * 118)

    px = {}
    for sym, fname in CROSSES.items():
        px[sym] = load(f"{DATA}/{fname}")
    for sym in MAJORS:
        px[sym] = load(major_files(sym))

    # ---------------- 0. flux ----------------
    P("\n--- 0. FLUX")
    for sym in px:
        cnt = {}
        for t in px[sym]:
            cnt[t.weekday()] = cnt.get(t.weekday(), 0) + 1
        kind = "CROSS (OANDA)" if sym in CROSSES else "MAJEURE (Dukascopy)"
        P(f"  {sym:8s} {kind:18s} bars={len(px[sym]):7d}  " +
          " ".join(f"{DOW[i]}={cnt.get(i,0)}" for i in range(5)))

    # ---------------- 0b. structure horaire du VENDREDI (ou est la vraie cloture ?) ----------------
    P("\n--- 0b. STRUCTURE HORAIRE DU VENDREDI (prober la convention AVANT de mesurer)")
    P("  Nombre de barres OANDA/Dukascopy par heure UTC le vendredi — localise la CLOTURE REELLE.")
    P(f"  {'SYMBOLE':9s}" + "".join(f"{h:5d}" for h in range(17, 24)))
    for sym in px:
        cnt = {}
        for t in px[sym]:
            if t.weekday() == 4:
                cnt[t.hour] = cnt.get(t.hour, 0) + 1
        P(f"  {sym:9s}" + "".join(f"{cnt.get(h,0):5d}" for h in range(17, 24)))
    P("  (les crosses OANDA s'arretent a la cloture du vendredi ; les majeures Dukascopy sont remplies 24x7)")
    P("\n--- 1. P0 NON-REGRESSION (retour brut du vendredi ; 57e ancre 21:00 / 58e fenetre [13,21))")
    rows21 = {}
    P(f"  {'SYMBOLE':9s}{'brut 21:00':>12s}{'t':>7s} | {'ref 57e':>9s}{'ecart':>8s} |"
      f" {'brut [13,21)':>13s}{'t':>7s} | {'ref 58e':>9s}{'ecart':>8s}")
    ny21 = {}
    for sym in px:
        rows21[sym], _ = daily_returns(px[sym], 21)
        fri21 = [r for (d, w, r, h) in rows21[sym] if w == 4]
        m21, t21 = tstat(fri21)
        ny21[sym] = window_returns(px[sym], 13, 21)
        friNY = [r for (d, w), r in ny21[sym].items() if w == 4]
        mny, tny = tstat(friNY)
        P(f"  {sym:9s}{m21:+12.2f}{t21:+7.2f} | {REF_FRI[sym]:+9.2f}{m21 - REF_FRI[sym]:+8.2f} |"
          f" {mny:+13.2f}{tny:+7.2f} | {REF_NY_FRI[sym]:+9.2f}{mny - REF_NY_FRI[sym]:+8.2f}")

    # ---------------- 2. la fenetre vivante ----------------
    P(f"\n--- 2. FENETRE VIVANTE [{H_IN:02d}:00, {H_OUT:02d}:00) UTC (sortie AVANT le rollover 17:00 ET)")
    ny = {}
    P(f"  {'SYMBOLE':9s}{'brut VEN':>10s}{'t':>7s} | {'FADE VEN':>9s}{'coût':>7s}{'NET VEN':>9s} |"
      f" {'brut Mon-Jeu':>13s}{'t':>7s} | {'NET MJ':>8s} | {'spec VEN-MJ':>11s}{'t':>7s}")
    for sym in px:
        ny[sym] = window_returns(px[sym], H_IN, H_OUT)
        fri = [r for (d, w), r in ny[sym].items() if w == 4]
        mj = [r for (d, w), r in ny[sym].items() if w < 4]
        mf, tf = tstat(fri)
        mm, tm = tstat(mj)
        dv, dvt = welch([-r for r in fri], [-r for r in mj])
        c = COST_BP[sym]
        P(f"  {sym:9s}{mf:+10.2f}{tf:+7.2f} | {-mf:+9.2f}{c:7.3f}{-mf - c:+9.2f} |"
          f" {mm:+13.2f}{tm:+7.2f} | {-mm - c:+8.2f} | {dv:+11.2f}{dvt:+7.2f}")
    P("  spec VEN-MJ = FADE VEN - FADE Mon-Jeu (bp) : > 0 net => la fenetre porte quelque chose de propre au vendredi.")

    # ---------------- 3. les 4 proxies de vol ----------------
    P("\n--- 3. LES 4 PROXIES DE VOL (percentile causal, traîne <= 500 obs, etat du JEUDI 21:00)")
    own = {}
    for sym in px:
        own[sym] = VolPct({d: r for (d, w, r, h) in rows21[sym]})
    eq_mes = VolPct(eq_daily_returns(f"{FUT}/MES_D1.csv"))
    P(f"  EQ = MES D1 (S&P 500 mini) : {len(eq_mes.pct)} percentiles calcules")
    P("  OWN = vol 21 j de la PAIRE elle-meme (retours quotidiens ancre 21:00 UTC)")
    P("  AND = min(EQ, OWN)  ·  MEAN = (EQ + OWN)/2")
    P("  Derive du conditionnant (part d'observations en vol haute > 60, par ere) :")
    P(f"    {'PROXY':10s}" + "".join(f"{a}-{str(b)[2:]:>8s}" for a, b in ERAS))
    d = eq_mes.drift()
    P(f"    {'EQ (MES)':10s}" + "".join(f"{v:6.0f}%(n{n})" for _, _, v, n in d))
    for sym in px:
        d = own[sym].drift()
        P(f"    {'OWN ' + sym:10s}" + "".join(f"{v:6.0f}%(n{n})" for _, _, v, n in d))

    def scores(sym, d):
        e, o = eq_mes.asof(d), own[sym].asof(d)
        if e is None or o is None:
            return None
        return {"EQ": e, "OWN": o, "AND": min(e, o), "MEAN": (e + o) / 2.0}

    # ---------------- 4. ORDRE des quintiles (gradient, U, ou bruit) ----------------
    P("\n--- 4. ORDRE DES QUINTILES (loi du 45e : exiger la MONOTONIE avant de nommer un gradient)")
    P("  Objet = fade (bp) net du coût, par quintile de percentile de vol. Q1 = vol la plus BASSE.")
    P(f"  {'PROXY':6s}{'SYMBOLE':10s}" + "".join(f"{'Q' + str(i):>16s}" for i in range(1, 6)) +
      f"{'mono':>7s}{'tout':>8s}{'n':>6s}")
    quints = {}
    for prox in ("EQ", "OWN", "AND", "MEAN"):
        for sym in px:
            sample = []
            for (dd, w), r in ny[sym].items():
                if w != 4:
                    continue
                sc = scores(sym, dd)
                if sc is None:
                    continue
                sample.append((sc[prox], -r - COST_BP[sym]))
            qs = quintiles(sample)
            if qs is None:
                continue
            quints[(prox, sym)] = qs
            line = f"  {prox:6s}{sym:10s}"
            for q in qs:
                m, t = tstat(q)
                line += f"{m:+9.2f}(t{t:+5.1f})"
            allm, allt = tstat([v for _, v in sample])
            line += f"{mono(qs):+7.2f}{allm:+8.2f}{len(sample):6d}"
            P(line)

    # ---------------- 5. OVERLAY a exposition EGALE, PAR PERIODE ----------------
    P("\n--- 5. OVERLAY DE TAILLE (2u si percentile >= 60) — benchmark = LEVIER UNIFORME a exposition EGALE")
    P("  delta = net/unite de l'overlay - net/unite du FLAT (le FLAT porte le meme levier MOYEN).")
    P("  Lecture par PERIODE (le 45e/47e : un gain FULL peut etre un artefact d'ere).")
    P(f"  {'PROXY':6s}{'SYMBOLE':10s}{'FULL d':>9s}{'IS d':>9s}{'OOS d':>9s} |"
      f"{'FULL net/FLAT':>18s}{'IS net/FLAT':>16s}{'OOS net/FLAT':>16s}")
    for prox in ("EQ", "OWN", "AND", "MEAN"):
        agg = {"FULL": [], "IS 06-15": [], "OOS 16-26": []}
        for sym in px:
            sd = []
            for (dd, w), r in ny[sym].items():
                if w != 4:
                    continue
                sc = scores(sym, dd)
                if sc is None:
                    continue
                sd.append((dd, sc[prox], -r - COST_BP[sym]))
            per = per_period(sd)
            line = f"  {prox:6s}{sym:10s}"
            for k in ("FULL", "IS 06-15", "OOS 16-26"):
                e = evaluate(per[k], 60.0)
                line += f"{e[0]:+9.2f}" if e else f"{'--':>9s}"
                agg[k].append(e)
            line += " |"
            for k in ("FULL", "IS 06-15", "OOS 16-26"):
                e = evaluate(per[k], 60.0)
                line += f"{e[1]:+9.2f}/{e[2]:+7.2f}" if e else f"{'--':>18s}"
            P(line)
        line = f"  {prox:6s}{'MOY 3 CROSSES':10s}"
        for k in ("FULL", "IS 06-15", "OOS 16-26"):
            vs = [agg[k][i][0] for i, s in enumerate(px) if s in CROSSES and agg[k][i] is not None]
            line += f"{(sum(vs)/len(vs)):+9.2f}" if vs else f"{'--':>9s}"
        P(line + "   <== moyenne des 3 CROSSES REFUGE (la config vivante)")
        line = f"  {prox:6s}{'MOY 6 PAIRES':10s}"
        for k in ("FULL", "IS 06-15", "OOS 16-26"):
            vs = [e[0] for e in agg[k] if e is not None]
            line += f"{(sum(vs)/len(vs)):+9.2f}" if vs else f"{'--':>9s}"
        P(line)

    # ---------------- 6. CONTREPARTIE : 2u en vol BASSE ----------------
    P("\n--- 6. CONTRAPOSITIF (loi du 43e/45e) : le MEME overlay inverse (2u en vol BASSE, percentile < 40)")
    P("  Un conditionnement en U rend les DEUX cotes positifs gratuitement => lire la paire de colonnes.")
    P(f"  {'PROXY':6s}{'SYMBOLE':10s}{'2u volHAUTE':>14s}{'2u volBASSE':>14s}{'difference':>12s}")
    for prox in ("EQ", "OWN", "AND", "MEAN"):
        for sym in px:
            sd = []
            for (dd, w), r in ny[sym].items():
                if w != 4:
                    continue
                sc = scores(sym, dd)
                if sc is None:
                    continue
                sd.append((sc[prox], -r - COST_BP[sym]))
            hi = evaluate(sd, 60.0)
            lo = evaluate([(100.0 - s, n) for s, n in sd], 60.0)   # 2u si percentile < 40
            if hi and lo:
                P(f"  {prox:6s}{sym:10s}{hi[0]:+14.2f}{lo[0]:+14.2f}{hi[0] - lo[0]:+12.2f}")
        P("")

    # ---------------- 7. PLACEBO Mon-Jeu + controle de FENETRE ----------------
    P("\n--- 7. PLACEBO (meme overlay sur les jours Mon-Jeu) : specificite au vendredi exigee")
    P(f"  {'PROXY':6s}{'SYMBOLE':10s}{'VEN delta':>12s}{'MON-JEU delta':>16s}{'ecart':>9s}")
    for prox in ("EQ", "OWN", "AND", "MEAN"):
        for sym in px:
            v, mj = [], []
            for (dd, w), r in ny[sym].items():
                sc = scores(sym, dd)
                if sc is None:
                    continue
                (v if w == 4 else mj).append((sc[prox], -r - COST_BP[sym]))
            ev, em = evaluate(v, 60.0), evaluate(mj, 60.0)
            if ev and em:
                P(f"  {prox:6s}{sym:10s}{ev[0]:+12.2f}{em[0]:+16.2f}{ev[0] - em[0]:+9.2f}")

    P("\n--- 7b. CONTROLE DE FENETRE : mono_r du conditionneur sur [00,13) ASIE+LONDRES et [00,21) JOUR")
    P("  (loi du 58e : un mecanisme reel a le MEME SIGNE sur les fenetres disjointes.)")
    w_am = {sym: window_returns(px[sym], 0, 13) for sym in px}
    w_day = {sym: window_returns(px[sym], 0, 21) for sym in px}
    P(f"  {'PROXY':6s}{'SYMBOLE':10s}{'[00,13) mono':>14s}{'[13,20) mono':>14s}{'[00,21) mono':>14s}")
    for prox in ("EQ", "OWN", "AND", "MEAN"):
        for sym in px:
            line = f"  {prox:6s}{sym:10s}"
            for wsrc in (w_am, ny, w_day):
                pairs = []
                for (dd, w) in wsrc[sym]:
                    if w != 4:
                        continue
                    sc = scores(sym, dd)
                    r = wsrc[sym].get((dd, 4))
                    if sc is None or r is None:
                        continue
                    pairs.append((sc[prox], -r - COST_BP[sym]))
                qs = quintiles(pairs)
                line += f"{(mono(qs)):+14.2f}" if qs else f"{'--':>14s}"
            P(line)

    # ---------------- 8. VALIDATION EN z ----------------
    P("\n--- 8. VALIDATION EN z (loi du 42e/45e : en bp ET en z, deux questions differentes)")
    P("  z = fade / sigma des 21 retours quotidiens STRICTEMENT anterieurs (normalisateur causal).")
    sig = {}
    for sym in px:
        ser = sorted((dd, r) for (dd, w, r, h) in rows21[sym] if w == 4)
        sd_ = {}
        for i in range(len(ser)):
            w21 = [r for _, r in ser[max(0, i - 21):i]]
            if len(w21) >= 15:
                sd_[ser[i][0]] = st.pstdev(w21)
        sig[sym] = sd_
    P(f"  {'PROXY':6s}{'SYMBOLE':10s}{'Q1 bp':>10s}{'Q1 z':>8s}{'Q5 bp':>10s}{'Q5 z':>8s}"
      f"{'mono bp':>9s}{'mono z':>9s}{'delta z':>9s}")
    for prox in ("EQ", "OWN", "AND", "MEAN"):
        for sym in px:
            bp, zz = [], []
            for (dd, w), r in ny[sym].items():
                if w != 4:
                    continue
                sc = scores(sym, dd)
                sdv = sig[sym].get(dd)
                if sc is None or not sdv:
                    continue
                bp.append((sc[prox], -r - COST_BP[sym]))
                zz.append((sc[prox], (-r - COST_BP[sym]) / sdv))
            qb, qz = quintiles(bp), quintiles(zz)
            if qb is None:
                continue
            mb, mz = [tstat(q)[0] for q in qb], [tstat(q)[0] for q in qz]
            ez = evaluate([(s, z) for s, z in zz], 60.0)
            P(f"  {prox:6s}{sym:10s}{mb[0]:+10.2f}{mz[0]:+8.3f}{mb[-1]:+10.2f}{mz[-1]:+8.3f}"
              f"{mono(qb):+9.2f}{mono(qz):+9.2f}{ez[0]:+9.3f}")

    # ---------------- 9. PLATEAU DE SEUILS ----------------
    P("\n--- 9. PLATEAU DE SEUILS (loi : un PIC de seuil = curve fitting ; un PLATEAU est acceptable)")
    P(f"  {'PROXY':6s}{'SEUIL':6s}{'MOY 3 CROSSES delta':>22s}{'MOY 6 PAIRES delta':>20s}"
      f"{'positives':>11s}")
    for prox in ("EQ", "OWN", "AND", "MEAN"):
        for th in (50.0, 60.0, 70.0, 80.0):
            cr, allp = [], []
            for sym in px:
                sd = []
                for (dd, w), r in ny[sym].items():
                    if w != 4:
                        continue
                    sc = scores(sym, dd)
                    if sc is None:
                        continue
                    sd.append((sc[prox], -r - COST_BP[sym]))
                e = evaluate(sd, th)
                if e:
                    allp.append(e[0])
                    if sym in CROSSES:
                        cr.append(e[0])
            P(f"  {prox:6s}{th:6.0f}{sum(cr)/len(cr):+22.2f}{sum(allp)/len(allp):+20.2f}"
              f"{sum(1 for v in allp if v > 0):6d}/{len(allp)}")

    # ---------------- 10. OU VIT LE FADE DANS LA JOURNEE ? (decomposition horaire) ----------------
    P("\n--- 10. DECOMPOSITION HORAIRE DU VENDREDI (loi du 57e : localiser l'effet DANS la journee)")
    P("  Retour BRUT moyen de la barre [h, h+1) UTC, le vendredi, par paire (6 paires).")
    P("  Puis le MEME profil le lundi (placebo) : un mecanisme unique doit se lire dans l'ordre des heures.")
    hours = {sym: {h: window_returns(px[sym], h, h + 1) for h in range(6, 21)} for sym in px}
    P(f"  {'HEURE UTC':10s}" + "".join(f"{s:>17s}" for s in px))
    for h in range(8, 21):
        line = f"  [{h:02d},{h+1:02d})    "
        for sym in px:
            fri = [r for (d, w), r in hours[sym][h].items() if w == 4]
            m, t = tstat(fri)
            line += f"{m:+10.2f}(t{t:+4.1f})"
        P(line)
    P("  Cumul depuis 13:00 UTC (retour brut, vendredi) — ou se concentre le fade :")
    P(f"  {'SORTIE h':10s}" + "".join(f"{s:>17s}" for s in px))
    cum = {sym: {} for sym in px}
    for x in range(13, 22):
        line = f"  13:00->{x:02d}:00"
        for sym in px:
            fri = [r for (d, w), r in window_returns(px[sym], 13, x).items() if w == 4]
            m, t = tstat(fri)
            cum[sym][x] = (m, t, len(fri))
            line += f"{m:+10.2f}(t{t:+4.1f})"
        P(line)
    P("\n  PLACEBO — meme cumul 13:00 -> x sur les jours Mon-Jeu (retour brut moyen) :")
    P(f"  {'SORTIE h':10s}" + "".join(f"{s:>17s}" for s in px))
    for x in range(13, 22):
        line = f"  13:00->{x:02d}:00"
        for sym in px:
            mj = [r for (d, w), r in window_returns(px[sym], 13, x).items() if w < 4]
            m, t = tstat(mj)
            line += f"{m:+10.2f}(t{t:+4.1f})"
        P(line)

    # ---------------- 11. BALAYAGE DE L'HEURE DE SORTIE (net apres coût) ----------------
    P("\n--- 11. BALAYAGE DE LA SORTIE : net = fade(13:00 -> x) - coût A/R (0 nuit si x < 21:00 UTC)")
    P("  Lecture = NET (brut negatif => fade positif => SELL gagnant). Le 58e sortait a 21:00 ;")
    P("  Martin (15 oct) a retenu 20:00 pour rester HORS du rollover (17:00 ET). Comparer les deux.")
    P(f"  {'SORTIE':9s}{'COUT':6s}" + "".join(f"{s:>10s}" for s in px) + f"{'MOY CROSSES':>13s}")
    for x in range(14, 22):
        line = f"  ->{x:02d}:00   "
        cr = []
        for sym in px:
            m = cum[sym][x][0]
            net = -m - COST_BP[sym]
            line += f"{net:+10.2f}"
            if sym in CROSSES:
                cr.append(net)
        P(line + f"{sum(cr)/len(cr):+13.2f}")
    P("  Detail par periode (FULL / IS 06-15 / OOS 16-26) sur les 3 crosses, net moyen :")
    for x in (17, 18, 19, 20, 21):
        line = f"  ->{x:02d}:00   "
        for lo, hi, lab in ((W0.date(), W1.date(), "FULL"), (W0.date(), dt.date(2016, 1, 1), "IS 06-15"),
                            (dt.date(2016, 1, 1), W1.date(), "OOS 16-26")):
            nets = []
            for sym in CROSSES:
                fri = [r for (d, w), r in window_returns(px[sym], 13, x).items()
                       if w == 4 and lo <= d < hi]
                m, t = tstat(fri)
                nets.append(-m - COST_BP[sym])
            line += f"  {lab}: {sum(nets)/len(nets):+6.2f}"
        P(line)
    P("  Seuil de rupture du carry si on sort a 21:00 UTC (= 17:00 ET = rollover) :")
    P("  1 jour de rollover = 0.274 pip/lot/jour par 1 % de differentiel de taux (regle du 47e).")
    P("  Sur un cross cote ~1.0-1.2, 1 pip = ~1 bp : il faudrait un differentiel d'environ 8-12 %")
    P("  (inexistant) pour effacer la derniere heure. Le carry ne peut PAS justifier la sortie a 20:00.")

    # ---------------- 12. ROBUSTESSE DE LA DERNIERE HEURE ----------------
    P("\n--- 12. ROBUSTESSE DE LA DERNIERE HEURE [20:00, 21:00) UTC (la jambe qui decide)")
    P("  Une jambe de 1 h sur ~1000 vendredis : verifier mediane, hit, IS/OOS, eres, et le TRIM")
    P("  (retirer les 5 % d'observations les plus favorables) — sinon c'est une queue, pas un edge.")
    P(f"  {'SYMBOLE':9s}{'brut moy':>10s}{'t':>7s}{'mediane':>9s}{'hit<0':>7s}{'n':>6s}"
      f"{'TRIM5%':>9s}{'IS':>9s}{'OOS':>9s}{'eres (4)':>34s}")
    for sym in px:
        leg = sorted((d, r) for (d, w), r in hours[sym][20].items() if w == 4)
        xs = [r for _, r in leg]
        if len(xs) < 100:
            P(f"  {sym:9s}  n={len(xs)} — donnee insuffisante")
            continue
        m, t = tstat(xs)
        med = median(xs)
        hit = 100.0 * sum(1 for v in xs if v < 0) / len(xs)
        k = max(1, int(0.05 * len(xs)))
        trimmed = sorted(xs)[k:len(xs) - k]
        mt, tt = tstat(trimmed)
        isx = [r for d, r in leg if d < dt.date(2016, 1, 1)]
        oosx = [r for d, r in leg if d >= dt.date(2016, 1, 1)]
        mi, ti = tstat(isx)
        mo, to = tstat(oosx)
        eras = []
        for a, b in ERAS:
            ex = [r for d, r in leg if dt.date(a, 1, 1) <= d < dt.date(b, 1, 1)]
            mm, _ = tstat(ex)
            eras.append(f"{mm:+6.2f}" if len(ex) > 50 else "  n/a ")
        P(f"  {sym:9s}{m:+10.2f}{t:+7.2f}{med:+9.2f}{hit:6.0f}%{len(xs):6d}"
          f"{mt:+9.2f}{mi:+9.2f}{mo:+9.2f}{''.join(eras):>34s}")
    # placebo horaire : la derniere heure porte-t-elle la meme chose Mon-Jeu ?
    P("\n  PLACEBO : la meme heure [20,21) les jours Mon-Jeu :")
    P(f"  {'SYMBOLE':9s}{'LUN':>10s}{'MAR':>10s}{'MER':>10s}{'JEU':>10s}{'VEN':>10s}")
    for sym in px:
        line = f"  {sym:9s}"
        for w in range(5):
            xs = [r for (d, ww), r in hours[sym][20].items() if ww == w]
            m, t = tstat(xs)
            line += f"{m:+10.2f}"
        P(line)

    # ---------------- 13. ANCRAGE : la cloture du vendredi est-elle a heure UTC FIXE ? ----------------
    P("\n--- 13. ANCRAGE CLOTURE-RELATIF (le vendredi ne ferme PAS a une heure UTC fixe)")
    P("  Pour chaque vendredi on identifie la DERNIERE barre de la semaine dans la serie, puis on mesure")
    P("  le retour des barres dans l'ordre CLOTURE-RELATIF (k=0 = derniere heure). Condition d'ancrage.")
    byday = {}
    for sym in px:
        bd = {}
        for t in px[sym]:
            bd.setdefault(t.date(), []).append(t.hour)
        byday[sym] = bd
    lastH = {}
    for sym in px:
        lastH[sym] = {d: max(hs) for d, hs in byday[sym].items() if d.weekday() == 4}
    P("  Distribution du mois des vendredis selon l'heure de la derniere barre (crosses) :")
    for sym in CROSSES:
        line = f"  {sym:8s}"
        for hh in (19, 20, 21, 22, 23):
            ms = [d.month for d, h in lastH[sym].items() if h == hh]
            if ms:
                cnt = {}
                for m in ms:
                    cnt[m] = cnt.get(m, 0) + 1
                top = sorted(cnt.items(), key=lambda kv: -kv[1])[:4]
                line += f" | h{hh:02d} n={len(ms):4d} " + " ".join(f"M{m:02d}:{c}" for m, c in top)
        P(line)
    P("  Lecture : une derniere barre a h20 = cloture 21:00 UTC (heure d'ete EDT) ; h21 = 22:00 UTC (EST).")
    P("  => toute mesure a heure UTC FIXE melange la DERNIERE heure et son avant-derniere selon la saison.")

    P("\n  Profil CLOTURE-RELATIF du vendredi (k barres AVANT la cloture reelle) — brut, par paire :")
    P(f"  {'SYMBOLE':9s}" + "".join(f"{'k=' + str(k):>17s}" for k in range(6)))
    for sym in px:
        line = f"  {sym:9s}"
        for k in range(6):
            xs = []
            for d, H in lastH[sym].items():
                h = H - k
                r = bar_ret(px[sym], d, h)
                if r is not None:
                    xs.append(r)
            m, t = tstat(xs)
            line += f"{m:+10.2f}(t{t:+4.1f})"
        P(line)
    P("  PLACEBO cloture-relatif : MEME heure H que le vendredi de la meme semaine, sur lundi-jeudi :")
    P(f"  {'SYMBOLE':9s}" + "".join(f"{'k=' + str(k):>17s}" for k in range(6)))
    for sym in px:
        line = f"  {sym:9s}"
        for k in range(6):
            xs = []
            for d, H in lastH[sym].items():
                for off in (1, 2, 3, 4):
                    dd = d - dt.timedelta(days=off)
                    r = bar_ret(px[sym], dd, H - k)
                    if r is not None:
                        xs.append(r)
            m, t = tstat(xs)
            line += f"{m:+10.2f}(t{t:+4.1f})"
        P(line)

    P("\n  Cumul 13:00 UTC -> CLOTURE REELLE, net du coût (crosses = config vivante) :")
    P(f"  {'SYMBOLE':9s}{'FULL':>9s}{'IS 06-15':>10s}{'OOS 16-26':>11s}{'t':>7s}{'n':>7s}"
      f" | {'ete (avr-oct)':>14s}{'hiver':>9s}")
    for sym in CROSSES:
        vals, t_full, n = {}, 0.0, 0
        for lab, lo, hi in (("FULL", W0.date(), W1.date()), ("IS", W0.date(), dt.date(2016, 1, 1)),
                            ("OOS", dt.date(2016, 1, 1), W1.date())):
            xs = []
            for d, H in lastH[sym].items():
                if not (lo <= d < hi):
                    continue
                a, b = price_at(px[sym], d, 13), price_at(px[sym], d, H + 1)
                if a and b and a > 0:
                    xs.append(-math.log(b / a) * 1e4 - COST_BP[sym])
            m, t = tstat(xs)
            vals[lab] = (m, t, len(xs))
            if lab == "FULL":
                t_full, n = t, len(xs)
        ea, eb = [], []
        for d, H in lastH[sym].items():
            a, b = price_at(px[sym], d, 13), price_at(px[sym], d, H + 1)
            if not (a and b and a > 0):
                continue
            (ea if 4 <= d.month <= 10 else eb).append(-math.log(b / a) * 1e4 - COST_BP[sym])
        me, _ = tstat(ea)
        mw, _ = tstat(eb)
        P(f"  {sym:9s}{vals['FULL'][0]:+9.2f}{vals['IS'][0]:+10.2f}{vals['OOS'][0]:+11.2f}"
          f"{t_full:+7.2f}{n:7d} | {me:+14.2f}{mw:+9.2f}")

    P("\n  TEST DECISIF — la derniere heure mesuree a l'ancrage CLOTURE vs a l'heure UTC FIXE [20,21) :")
    P("  Si l'effet est la VRAIE derniere heure, l'ancrage cloture la porte en ete ET en hiver ; si c'est")
    P("  un artefact d'ancrage, il se concentre sur la saison ou [20,21) EST la derniere heure.")
    P(f"  {'SYMBOLE':9s}{'ancre CLOTURE':>15s}{'ancre [20,21)':>15s}{'ete cloture':>13s}"
      f"{'hiver cloture':>15s}{'ete [20,21)':>13s}{'hiver [20,21)':>15s}")
    for sym in px:
        cl_sum, cl_win = [], []
        for d, H in lastH[sym].items():
            r = bar_ret(px[sym], d, H)
            if r is not None:
                cl_sum.append((d, r))
        for d in sorted(lastH[sym]):
            r = bar_ret(px[sym], d, 20)
            if r is not None:
                cl_win.append((d, r))
        def split(rows):
            e = [r for d, r in rows if 4 <= d.month <= 10]
            w = [r for d, r in rows if not (4 <= d.month <= 10)]
            me, te = tstat(e)
            mw, tw = tstat(w)
            mall, tall = tstat([r for _, r in rows])
            return mall, tall, me, mw
        a1 = split(cl_sum)
        a2 = split(cl_win)
        P(f"  {sym:9s}{a1[0]:+9.2f}(t{a1[1]:+4.1f}){a2[0]:+9.2f}(t{a2[1]:+4.1f})"
          f"{a1[2]:+13.2f}{a1[3]:+15.2f}{a2[2]:+13.2f}{a2[3]:+15.2f}")

    # ---------------- 14. TEST DE ROBUSTESSE : derniere BARRE REELLE (high > low), 2 fournisseurs ----------------
    P("\n--- 14. CONTROLE DU BIAIS DE QUOTE : derniere BARRE REELLE (high > low, loi du 40e/56e)")
    P("  Une barre de carry/plate (high == low) au bord de la semaine pourrait fabriquer le mouvement.")
    P("  On refait l'ancrage sur la derniere barre NON PLATE, sur les 2 FOURNISSEURS (majeures Dukascopy).")
    ohlc = {}
    for sym in px:
        d_ = {}
        for path in ([f"{DATA}/{CROSSES[sym]}"] if sym in CROSSES else major_files(sym)):
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
                        t = dt.datetime.fromtimestamp(v / 1000.0, tz=UTC)
                        d_[t] = (float(row[1]), float(row[2]), float(row[3]), float(row[4]))
                    except (ValueError, OverflowError, OSError):
                        continue
        ohlc[sym] = d_
    realH = {}
    for sym in px:
        bd = {}
        for t, (o, h, l, c) in ohlc[sym].items():
            if t.weekday() == 4 and h > l:
                bd[t.date()] = max(bd.get(t.date(), -1), t.hour)
        realH[sym] = bd
    P(f"  {'SYMBOLE':9s}{'derniere barre reelle (mois)':>34s}{'VEN k=0':>16s}{'Mon-Jeu k=0':>16s}"
      f"{'diff':>9s}{'t':>7s}")
    for sym in px:
        ms = {}
        for d, h in realH[sym].items():
            ms.setdefault(h, []).append(d.month)
        desc = " ".join(f"h{h:02d}:{len(v)}" for h, v in sorted(ms.items()) if len(v) > 20)
        fri, mj = [], []
        for d, H in realH[sym].items():
            r = bar_ret(px[sym], d, H)
            if r is not None:
                fri.append(r)
            for off in (1, 2, 3, 4):
                rr = bar_ret(px[sym], d - dt.timedelta(days=off), H)
                if rr is not None:
                    mj.append(rr)
        mf, tf = tstat(fri)
        mm, tm = tstat(mj)
        dv, dt_ = welch(fri, mj)
        P(f"  {sym:9s}{desc:>34s}{mf:+10.2f}(t{tf:+4.1f}){mm:+10.2f}(t{tm:+4.1f})"
          f"{dv:+9.2f}{dt_:+7.2f}")
    P("  Lecture : si l'effet survit sur la derniere barre REELLE des DEUX fournisseurs, ce n'est ni une")
    P("  barre de carry ni un artefact de la serie OANDA.")

    # ---------------- 15. LE MODULATEUR DE VOL SUR LE BON OBJET (13:00 -> cloture reelle) ----------------
    P("\n--- 15. LE MODULATEUR DE VOL SUR LE BON OBJET : fade 13:00 UTC -> CLOTURE REELLE")
    P("  (la fenetre [13,20) testee en section 5 ne contenait PAS la jambe qui porte l'edge.)")
    P(f"  {'PROXY':6s}{'SYMBOLE':10s}{'Q1':>16s}{'Q2':>16s}{'Q3':>16s}{'Q4':>16s}{'Q5':>16s}"
      f"{'mono':>7s}{'delta':>8s}{'n':>6s}")
    for prox in ("EQ", "OWN", "AND", "MEAN"):
        for sym in CROSSES:
            sd = []
            for d, H in lastH[sym].items():
                sc = scores(sym, d)
                if sc is None:
                    continue
                a, b = price_at(px[sym], d, 13), price_at(px[sym], d, H + 1)
                if not (a and b and a > 0):
                    continue
                sd.append((d, sc[prox], -math.log(b / a) * 1e4 - COST_BP[sym]))
            qs = quintiles([(s, n) for _, s, n in sd])
            if qs is None:
                continue
            line = f"  {prox:6s}{sym:10s}"
            for q in qs:
                m, t = tstat(q)
                line += f"{m:+9.2f}(t{t:+5.1f})"
            e = evaluate([(s, n) for _, s, n in sd], 60.0)
            P(line + f"{mono(qs):+7.2f}{(e[0] if e else float('nan')):+8.2f}{len(sd):6d}")
        P("")

    txt = "\n".join(rep)
    print(txt)
    out = f"{HOME}/projects/tb-research/reports/2026-10-16-vol-size-overlay.txt"
    with open(out, "w") as f:
        f.write(txt + "\n")
    print("\nrapport ->", out)


if __name__ == "__main__":
    main()
