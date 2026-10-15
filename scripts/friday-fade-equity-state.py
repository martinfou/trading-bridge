#!/usr/bin/env python3
"""58e (jeudi 15 oct 2026) — INTERMARKET : l'ETAT ACTIONS avant le vendredi conditionne-t-il le fade refuge ?

Contexte (57e, 14 oct) :
  - le fade du vendredi (SELL) a hold EGALISE vaut +6.91 bp sur les crosses refuge, +2.42 bp sur les
    majeures ; la hierarchie cross > majeure SURVIT au hold egal (ecart +4.48 bp) ;
  - la decomposition intra-journaliere montre que le fade vit dans la FENETRE NY 13:00 -> 21:00 UTC
    (GBP_CHF -4.98 bp brut, t -4.2, soit 68 % du total journalier) : phenomene de FIN DE SEANCE
    (de-risquage avant le week-end) ;
  - action ouverte n1 du 57e : backtester l'ENTREE DECALEE (13:00 UTC) -> meme coût par aller-retour,
    8 h d'exposition au lieu de 21 h, 60-80 % du fade capturé.

Deux livrables (jeudi = intermarket/cross-asset) :
  A) LA FENETRE NY comme strategie : fade 13:00->21:00, hold 8 h INTRADAY (0 nuit de rollover),
     net sous les coûts MESURES par paire (55e/retro), contre le controle Mon-Jeu.
  B) LE CONDITIONNEMENT CROSS-ASSET : le fade est lu comme un de-risquage. L'etat ACTIONS connu
     AVANT l'entree (veille = dernier close actions < vendredi) le module-t-il ?
       S1 = retour actions J-1          S2 = retour 5 j entrant
       S3 = drawdown vs SMA200          S4 = percentile de vol 21 j (CONTROLE, variable du 42e/43e)
     Lecture : quintiles (ORDRE = monotonie exigee avant de nommer un gradient), binaire
     pre-enregistre (etat baissier vs haussier), |t| >= 3 pour un scan, IS/OOS, placebo Mon-Jeu.

Aucune ecriture dans le repo Java. Donnees : FX = 57e (3 crosses OANDA + 3 majeures Dukascopy),
actions = MES/MNQ D1 (futures, 2006-2026).
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

# Coût A/R en bp par paire. CROSSES = mesurees au broker (55e, OANDA practice -014, spread median).
# GBP_JPY = 1.63 bp (retro 10 oct, demi-spread mesure). GBP_USD / USD_CAD : non mesures -> scenario
# conservateur 2.14 bp (coût A/R suppose du pipeline, a re-deriver par paire). Aucune valeur inventee.
COST_BP = {"GBP_CHF": 2.278, "EUR_CHF": 1.702, "AUD_CHF": 2.584,
           "GBP_JPY": 1.630, "GBP_USD": 2.140, "USD_CAD": 2.140}

# References publiees (57e) — RETOURS BRUTS du vendredi a l'ancre 21:00 UTC (r<0 = baisse le vendredi).
REF_FRI = {"GBP_CHF": -7.43, "EUR_CHF": -4.50, "AUD_CHF": -8.79,
           "GBP_JPY": -4.36, "GBP_USD": -5.10, "USD_CAD": +2.19}
# Composante NY 13:00->21:00 du 57e (retour brut, meme convention).
REF_NY_FRI = {"GBP_CHF": -4.98, "EUR_CHF": -3.58, "AUD_CHF": -7.21,
              "GBP_JPY": +0.04, "GBP_USD": -0.62, "USD_CAD": -0.88}


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
                    t = dt.datetime.fromtimestamp(v / 1000.0, tz=UTC).replace(minute=0, second=0, microsecond=0)
                    out[t] = float(row[4])
                except (ValueError, OverflowError, OSError):
                    continue
    return out


# ------------------------------------------------------------------ stats de base
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
    """Difference de moyennes (a - b) et t de Welch (variances inegales)."""
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


# ------------------------------------------------------------------ prix / fenetres
def price_at(px, day, hour):
    """Prix a l'instant hour:00 UTC du jour `day` = close de la barre ouverte a hour-1."""
    if hour == 0:
        t = dt.datetime.combine(day - dt.timedelta(days=1), dt.time(23, 0), tzinfo=UTC)
    else:
        t = dt.datetime.combine(day, dt.time(hour - 1, 0), tzinfo=UTC)
    return px.get(t)


def daily_returns(px, hour, max_hold=96.0):
    """[(date, weekday, bp, hold_h)] — snapshot a `hour` UTC, lun-ven seulement, trous > max_hold exclus."""
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
    """RETOURS BRUTS (bp) de la fenetre intraday [h0, h1) du meme jour UTC — hold egal par construction.
    {(date, weekday): bp}. Une seule observation par jour, aucun chevauchement."""
    out = {}
    d = W0.date()
    while d < W1.date():
        if d.weekday() < 5:
            a, b = price_at(px, d, h0), price_at(px, d, h1)
            if a is not None and b is not None and a > 0:
                out[(d, d.weekday())] = math.log(b / a) * 1e4
        d += dt.timedelta(days=1)
    return out


def cell(rows, want_dow, lo=None, hi=None):
    lo_d = lo.date() if lo else None
    hi_d = hi.date() if hi else None
    xs = [r for (d, w, r, h) in rows
          if (want_dow is None or w == want_dow) and (lo_d is None or lo_d <= d < hi_d)]
    m, t = tstat(xs)
    return m, t, len(xs), median(xs)


# ------------------------------------------------------------------ etat ACTIONS
class Eq:
    """Serie D1 actions : date -> close. Aucun look-ahead : tout etat d'un vendredi utilise
    STRICTEMENT les barres anterieures a ce vendredi (la barre du vendredi se termine 21:00 UTC,
    apres l'entree a 13:00 UTC)."""

    def __init__(self, path):
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
        self.dates = [d for d, _ in rows]
        self.close = [c for _, c in rows]
        self.ret = [None] + [math.log(self.close[i] / self.close[i - 1]) * 1e4
                             for i in range(1, len(rows))]
        # percentile de vol CAUSAL (57e/42e : traine glissante, jamais le futur)
        self.vol21 = [None] * len(rows)
        for i in range(len(rows)):
            if i >= 21:
                w = [x for x in self.ret[i - 20:i + 1] if x is not None]
                if len(w) >= 15 and self.ret[i] is not None:
                    self.vol21[i] = st.pstdev(w)

    def _idx(self, d):
        """Dernier indice STRICTEMENT avant la date d."""
        return bisect.bisect_left(self.dates, d) - 1

    def state(self, d):
        i = self._idx(d)
        if i < 250 or self.ret[i] is None or self.vol21[i] is None:
            return None
        r5 = math.log(self.close[i] / self.close[i - 5]) * 1e4
        sma200 = sum(self.close[i - 199:i + 1]) / 200.0
        dd = math.log(self.close[i] / sma200) * 1e4
        vol = self.vol21[i]
        past = [self.vol21[j] for j in range(max(0, i - 500), i)]
        past = [v for v in past if v is not None]
        pct = 100.0 * sum(1 for v in past if v < vol) / len(past) if past else None
        return {"asof": self.dates[i], "prev": self.ret[i], "r5": r5, "dd": dd,
                "vol": vol, "volpct": pct}

    def ret_on(self, d):
        """Retour de la barre D1 portant la date d (connu a la cloture de d) — CONTEMPORAIN."""
        j = bisect.bisect_left(self.dates, d)
        if j < len(self.dates) and self.dates[j] == d and self.ret[j] is not None:
            return self.ret[j]
        return None


def quintiles(pairs, key):
    """[(valeur_etat, fade_bp)] -> 5 buckets par quantiles de la valeur d'etat."""
    vals = sorted(v for v, _ in pairs)
    cuts = [vals[int(len(vals) * k / 5)] for k in (1, 2, 3, 4)]
    out = [[], [], [], [], []]
    for v, f in pairs:
        k = 0
        while k < 4 and v >= cuts[k]:
            k += 1
        out[k].append(f)
    return out


# ------------------------------------------------------------------ main
def main():
    rep = []
    P = rep.append
    P("=" * 110)
    P("58e (jeudi 15 oct 2026) — INTERMARKET : l'ETAT ACTIONS conditionne-t-il le fade refuge du vendredi ?")
    P("Fenetre 2006-01-01 -> 2026-05-20 UTC. Hold EGALISE (57e). Convention : on publie le FADE (SELL),")
    P("donc fade = -retour_brut ; fade > 0 = vendredi baissier = SELL gagnant.")
    P("=" * 110)

    px = {}
    for sym, fname in CROSSES.items():
        px[sym] = load(f"{DATA}/{fname}")
    for sym in MAJORS:
        px[sym] = load(major_files(sym))

    # ---------------- 0. flux ----------------
    P("\n--- 0. FLUX ET INTEGRITE")
    for sym in px:
        cnt = {}
        for t in px[sym]:
            cnt[t.weekday()] = cnt.get(t.weekday(), 0) + 1
        kind = "CROSS (OANDA)" if sym in CROSSES else "MAJEURE (Dukascopy)"
        P(f"  {sym:8s} {kind:18s} bars={len(px[sym]):7d}  " +
          " ".join(f"{DOW[i]}={cnt.get(i,0)}" for i in range(5)) +
          f" | SAM={cnt.get(5,0)} DIM={cnt.get(6,0)}")

    # ---------------- 1. P0 ----------------
    P("\n--- 1. P0 NON-REGRESSION contre le 57e (retour brut du vendredi, ancre 21:00 UTC)")
    rows21 = {}
    P(f"  {'SYMBOLE':9s}{'brut bp':>10s}{'t':>7s}{'n':>6s} | {'ref 57e':>9s}{'ecart':>8s}")
    for sym in px:
        rows21[sym], _ = daily_returns(px[sym], 21)
        m, t, n, _ = cell(rows21[sym], 4)
        P(f"  {sym:9s}{m:+10.2f}{t:+7.2f}{n:6d} | {REF_FRI[sym]:+9.2f}{m - REF_FRI[sym]:+8.2f}")

    # ---------------- 2. entree decalee : fenetre NY ----------------
    P("\n--- 2. ENTREE DECALEE 13:00 UTC (action ouverte n1 du 57e) — fenetre NY [13:00, 21:00), hold 8 h")
    P("  Zero nuit de rollover (entree et sortie le meme jour UTC) => net = fade - coût A/R, sans carry.")
    ny = {}
    P(f"  {'SYMBOLE':9s}{'NY VEN brut':>13s}{'t':>7s} | {'FADE VEN':>9s} | {'Mon-Jeu brut':>13s}{'t':>7s} |"
      f" {'FADE MJ':>8s} | {'coût':>6s}{'NET VEN':>9s}{'NET MJ':>8s}")
    for sym in px:
        ny[sym] = window_returns(px[sym], 13, 21)
        fri = [r for (d, w), r in ny[sym].items() if w == 4]
        mj = [r for (d, w), r in ny[sym].items() if w < 4]
        mf, tf = tstat(fri)
        mm, tm = tstat(mj)
        c = COST_BP[sym]
        P(f"  {sym:9s}{mf:+13.2f}{tf:+7.2f} | {-mf:+9.2f} | {mm:+13.2f}{tm:+7.2f} |"
          f" {-mm:+8.2f} | {c:6.3f}{-mf - c:+9.2f}{-mm - c:+8.2f}")
    P("  Reference 57e (composante NY du vendredi, retour brut) : " +
      " · ".join(f"{s} {v:+.2f}" for s, v in REF_NY_FRI.items()))
    P("  ecarts 58e - 57e (au-dessus) : " +
      " · ".join(f"{s} {(tstat([r for (d,w),r in ny[s].items() if w==4])[0] - REF_NY_FRI[s]):+.2f}"
                 for s in px))
    P("  -> la fenetre NY est SPECIFIQUE au vendredi si (fade VEN - fade Mon-Jeu) > 0 nettement.")
    diff_v, diff_t = welch([-r for (d, w), r in ny["GBP_CHF"].items() if w == 4],
                           [-r for (d, w), r in ny["GBP_CHF"].items() if w < 4])
    P(f"  GBP_CHF : FADE VEN - FADE Mon-Jeu = {diff_v:+.2f} bp (t {diff_t:+.2f})")

    # stabilite IS/OOS + decennies de la fenetre NY
    P("\n--- 2b. STABILITE DE LA FENETRE NY (retour brut vendredi ; fade = -brut)")
    P(f"  {'SYMBOLE':9s}{'IS 06-15':>16s}{'OOS 16-26':>16s} |" +
      "".join(f"{s:>16s}" for s in ("2006-09", "2010-14", "2015-19", "2020-26")))
    for sym in px:
        line = f"  {sym:9s}"
        for lo, hi in ((W0.date(), dt.date(2016, 1, 1)), (dt.date(2016, 1, 1), W1.date())):
            xs = [r for (d, w), r in ny[sym].items() if w == 4 and lo <= d < hi]
            m, t = tstat(xs)
            line += f"{m:+9.2f}(t{t:+5.2f})"
        line += " |"
        for a, b in ((2006, 2010), (2010, 2015), (2015, 2020), (2020, 2027)):
            xs = [r for (d, w), r in ny[sym].items()
                  if w == 4 and dt.date(a, 1, 1) <= d < dt.date(b, 1, 1)]
            m, t = tstat(xs)
            line += f"{m:+9.2f}(t{t:+5.2f})"
        P(line)

    # ---------------- 3. alignement actions ----------------
    P("\n--- 3. SERIES ACTIONS (futures D1) — diagnostic de calendrier")
    eqs = {}
    for name, fn in (("MES_SP500", "MES_D1.csv"), ("MNQ_NASDAQ", "MNQ_D1.csv")):
        e = Eq(f"{FUT}/{fn}")
        eqs[name] = e
        wd = {}
        for d in e.dates:
            wd[d.weekday()] = wd.get(d.weekday(), 0) + 1
        P(f"  {name:11s} {e.dates[0]} -> {e.dates[-1]}  n={len(e.dates):5d}  " +
          " ".join(f"{DOW[i]}={wd.get(i,0)}" for i in range(5)) +
          f" | SAM={wd.get(5,0)} DIM={wd.get(6,0)}")
    P("  Convention supposee : barre D1 de date D = seance qui SE TERMINE le jour D (close US ~21:00 UTC),")
    P("  donc CONNUE apres l'entree de 13:00 UTC. Tout etat de conditionnement utilise D-1 et anterieur.")
    P("  Verification : aucune date de samedi/dimanche dans les series (ci-dessus) -> convention compatible.")

    # contemporaneite : le co-mouvement FX/actions est-il plus fort le vendredi ?
    P("\n--- 3b. CO-MOUVEMENT CONTEMPORAIN (fenetre NY du FX vs seance actions du meme jour)")
    P("  Correlation de Pearson par paire (une observation par jour), puis moyenne des 6 paires.")
    P("  n = nombre de PAIRES corroborees (6), pas d'observations : lire le signe et l'ecart VEN - Mon-Jeu.")
    P(f"  {'EQ':11s}{'corr VEN':>10s}{'corr Mon-Jeu':>14s}{'delta':>8s}{'paires':>8s}")
    for name, e in eqs.items():
        rf, rm = [], []
        for sym in px:
            a, b = [], []
            for (d, w), r in ny[sym].items():
                er = e.ret_on(d)
                if er is None:
                    continue
                (a if w == 4 else b).append((r, er))
            if len(a) > 30 and len(b) > 30:
                rf.append(pearson([x for x, _ in a], [y for _, y in a]))
                rm.append(pearson([x for x, _ in b], [y for _, y in b]))
        mf = sum(rf) / len(rf)
        mm = sum(rm) / len(rm)
        P(f"  {name:11s}{mf:+10.3f}{mm:+14.3f}{mf - mm:+8.3f}{len(rf):8d}")
    P("  (moyenne des correlations par paire ; le 38e avait etabli que le co-mouvement NE MONTE PAS")
    P("   le vendredi => un facteur de POSITIONNEMENT decale les moyennes sans elever les correlations.)")

    # ---------------- 4. conditionnement ----------------
    P("\n--- 4. CONDITIONNEMENT PAR L'ETAT ACTIONS ANTERIEUR (connu avant l'entree de 13:00 UTC)")
    P("  Objet = fade de la fenetre NY du vendredi (bp). Scan : 2 indices x 4 variables x 5 quintiles")
    P("  = 40 cellules => seuil Bonferroni |t| >= 3. ORDRE des quintiles exige avant de nommer un gradient.")
    VARS = [("S1 retour J-1", "prev"), ("S2 retour 5 j", "r5"),
            ("S3 dd vs SMA200", "dd"), ("S4 vol pct (Ctrl)", "volpct")]
    for name, e in eqs.items():
        P(f"\n  === {name} ===")
        for label, key in VARS:
            P(f"    {label}")
            for sym in px:
                pairs = []
                for (d, w), r in ny[sym].items():
                    if w != 4:
                        continue
                    s = e.state(d)
                    if s is None or s[key] is None:
                        continue
                    pairs.append((s[key], -r))
                if len(pairs) < 100:
                    P(f"      {sym:8s} n={len(pairs)} (insuffisant)")
                    continue
                qs = quintiles(pairs, key)
                line = f"      {sym:8s}"
                for q in qs:
                    m, t = tstat(q)
                    line += f" {m:+7.2f}(t{t:+5.1f})"
                med = median(pairs)  # mediane du fade
                allm, allt = tstat([f for _, f in pairs])
                # monotonie : correlation de rang entre l'indice de quintile et la moyenne
                ms = [tstat(q)[0] for q in qs]
                mono = pearson(list(range(5)), ms)
                P(line + f" | tout {allm:+.2f}(t{allt:+.1f}) n={len(pairs)} mono_r={mono:+.2f}")

    # binaire pre-enregistre : etat baissier (S1<0) vs haussier
    P("\n--- 4b. BINAIRE PRE-ENREGISTRE : etat actions J-1 BAISSIER vs HAUSSIER (fade de la fenetre NY)")
    P("  Hypothese : si le fade est un de-risquage, un etat actions DEJA faible avant l'entree doit")
    P("  amplifier le fade. Lecture = difference (baissier - haussier) et son t de Welch.")
    P(f"  {'EQ':11s}{'SYMBOLE':9s}{'baissier':>18s}{'haussier':>18s}{'diff':>9s}{'t':>7s}")
    store = {}
    for name, e in eqs.items():
        for sym in px:
            dn, up = [], []
            for (d, w), r in ny[sym].items():
                if w != 4:
                    continue
                s = e.state(d)
                if s is None:
                    continue
                (dn if s["prev"] < 0 else up).append(-r)
            if len(dn) < 30 or len(up) < 30:
                continue
            md, td = tstat(dn)
            mu, tu = tstat(up)
            dd, tt = welch(dn, up)
            store[(name, sym)] = (md, td, mu, tu, dd, tt, len(dn), len(up))
            P(f"  {name:11s}{sym:9s}{md:+9.2f}(t{td:+5.2f}){mu:+9.2f}(t{tu:+5.2f}){dd:+9.2f}{tt:+7.2f}")

    # 2x2 : etat baissier x vol haute (controle du 42e/43e : la vol module-t-elle le role de la direction ?)
    P("\n--- 4c. 2x2 DIRECTION x VOL (controle : la vol du 42e/43e module-t-elle la direction ?)")
    P("  vol haute = percentile causal > 60 ; baissier = S1 < 0. Cellules : baissier-volH, baissier-volB,")
    P("  haussier-volH, haussier-volB. Un mecanisme unique doit se lire dans l'ORDRE.")
    P(f"  {'EQ':11s}{'SYMBOLE':9s}{'baisse/volH':>16s}{'baisse/volB':>16s}{'hausse/volH':>16s}{'hausse/volB':>16s}")
    for name, e in eqs.items():
        for sym in px:
            c = {"dH": [], "dB": [], "uH": [], "uB": []}
            for (d, w), r in ny[sym].items():
                if w != 4:
                    continue
                s = e.state(d)
                if s is None or s["volpct"] is None:
                    continue
                k = ("d" if s["prev"] < 0 else "u") + ("H" if s["volpct"] > 60 else "B")
                c[k].append(-r)
            if min(len(v) for v in c.values()) < 20:
                continue
            line = f"  {name:11s}{sym:9s}"
            for k in ("dH", "dB", "uH", "uB"):
                m, t = tstat(c[k])
                line += f"{m:+9.2f}(t{t:+5.1f})"
            P(line)

    # ---------------- 4d. controle de FENETRE ----------------
    P("\n--- 4d. LA MEME VARIABLE SUR D'AUTRES FENETRES (controle : le conditionneur change-t-il de signe ?)")
    P("  Objets : [00,13) ASIE+LONDRES · [13,21) NY · [00,21) JOUR complet · 57e = JEU 21:00 -> VEN 21:00 (24 h).")
    P("  mono_r = correlation rang-vs-moyenne de quintile (|mono_r| = 1 : ordre parfait). Un mecanisme")
    P("  reel doit donner le MEME SIGNE de mono_r sur les fenetres, pas seulement sur celle qui arrange.")
    P("  S1 = retour actions J-1 (directional, pre-enregistre) ; S4 = percentile de vol (controle du 42e).")
    w_am = {sym: window_returns(px[sym], 0, 13) for sym in px}
    w_day = {sym: window_returns(px[sym], 0, 21) for sym in px}
    w_57 = {sym: {d: r for (d, w, r, h) in rows21[sym] if w == 4} for sym in px}
    objects = [("[00,13)", "am", lambda s, d: w_am[s].get((d, 4))),
               ("[13,21)", "ny", lambda s, d: ny[s].get((d, 4))),
               ("[00,21)", "day", lambda s, d: w_day[s].get((d, 4))),
               ("57e 24h", "e57", lambda s, d: w_57[s].get(d))]
    for name, e in eqs.items():
        P(f"\n  === {name} ===  (Q1 | Q5 | tout | mono_r | t(Q5-Q1), en bp de FADE)")
        for label, key in (("S1", "prev"), ("S4", "volpct")):
            P(f"    {label}")
            for sym in px:
                line = f"      {sym:8s}"
                for olabel, _, get in objects:
                    pairs = []
                    for d in sorted({d for (d, w) in ny[sym] if w == 4}):
                        r = get(sym, d)
                        s = e.state(d)
                        if r is None or s is None or s[key] is None:
                            continue
                        pairs.append((s[key], -r))
                    if len(pairs) < 100:
                        line += f" | {olabel:8s} n<100"
                        continue
                    qs = quintiles(pairs, key)
                    m1, t1 = tstat(qs[0])
                    m5, t5 = tstat(qs[-1])
                    ms = [tstat(q)[0] for q in qs]
                    mono = pearson(list(range(5)), ms)
                    dv, dt_ = welch(qs[-1], qs[0])
                    allm = sum(f for _, f in pairs) / len(pairs)
                    line += (f" | {olabel:8s}{m1:+7.2f}|{m5:+7.2f}|{allm:+7.2f}"
                             f"|r{mono:+.2f}|t{dv:+6.2f}")
                P(line)

    # ---------------- 4e. validation en z ----------------
    P("\n--- 4e. VALIDATION EN z (loi du 42e : un edge conditionne par la vol se lit en z ET en bp)")
    P("  z = fade_bp / sigma_pair, sigma_pair = ecart-type des retours quotidiens (ancre 21:00) sur les")
    P("  21 observations STRICTEMENT anterieures au vendredi (normalisateur causal, sans look-ahead).")
    P("  Un conditionnement qui n'existe qu'en bp et disparait en z est un effet de VOLATILITE, pas d'edge.")
    sig = {}
    for sym in px:
        ser = sorted((d, r) for (d, w, r, h) in rows21[sym] if w == 4)
        sd = {}
        for i in range(len(ser)):
            w21 = [r for _, r in ser[max(0, i - 21):i]]
            if len(w21) >= 15:
                sd[ser[i][0]] = st.pstdev(w21)
        sig[sym] = sd
    P(f"  {'EQ':11s}{'SYMBOLE':9s}{'VAR':8s}{'Q1 bp':>10s}{'Q1 z':>8s}{'Q5 bp':>10s}{'Q5 z':>8s}"
      f"{'tout bp':>10s}{'tout z':>8s}{'mono_r bp':>11s}{'mono_r z':>11s}")
    for name, e in eqs.items():
        for sym in px:
            for label, key in (("S1", "prev"), ("S4", "volpct")):
                bp, zz = [], []
                for d in sorted({d for (d, w) in ny[sym] if w == 4}):
                    r = ny[sym].get((d, 4))
                    s = e.state(d)
                    sd = sig[sym].get(d)
                    if r is None or s is None or s[key] is None or not sd:
                        continue
                    bp.append((s[key], -r))
                    zz.append((s[key], -r / sd))
                if len(bp) < 100:
                    continue
                qb, qz = quintiles(bp, key), quintiles(zz, key)
                mb = [tstat(q)[0] for q in qb]
                mz = [tstat(q)[0] for q in qz]
                P(f"  {name:11s}{sym:9s}{label:8s}"
                  f"{mb[0]:+10.2f}{mz[0]:+8.3f}{mb[-1]:+10.2f}{mz[-1]:+8.3f}"
                  f"{sum(f for _, f in bp)/len(bp):+10.2f}{sum(f for _, f in zz)/len(zz):+8.3f}"
                  f"{pearson(list(range(5)), mb):+11.2f}{pearson(list(range(5)), mz):+11.2f}")

    # ---------------- 5. placebo ----------------
    P("\n--- 5. PLACEBO : le MEME conditionnement sur les jours Mon-Jeu (aucun fade attendu)")
    P("  Si le conditionnement marche aussi Mon-Jeu, il ne decrit pas le vendredi : c'est un")
    P("  conditionnement de la fenetre NY generique (ou du niveau de vol), pas du de-risquage hebdo.")
    P(f"  {'EQ':11s}{'SYMBOLE':9s}{'baissier MJ':>18s}{'haussier MJ':>18s}{'diff':>9s}{'t':>7s}")
    for name, e in eqs.items():
        for sym in px:
            dn, up = [], []
            for (d, w), r in ny[sym].items():
                if w == 4:
                    continue
                s = e.state(d)
                if s is None:
                    continue
                (dn if s["prev"] < 0 else up).append(-r)
            if len(dn) < 30 or len(up) < 30:
                continue
            md, td = tstat(dn)
            mu, tu = tstat(up)
            dd, tt = welch(dn, up)
            P(f"  {name:11s}{sym:9s}{md:+9.2f}(t{td:+5.2f}){mu:+9.2f}(t{tu:+5.2f}){dd:+9.2f}{tt:+7.2f}")

    # ---------------- 6. derive de l'etat actions ----------------
    P("\n--- 6. CONTROLE DE DERIVE : l'etat actions lui-meme est-il porteur du fade ?")
    P("  (le 18e/57e : un effet jour peut n'etre que la derive d'ere de l'actif conditionnant)")
    for name, e in eqs.items():
        fri = [e.ret_on(d) for d in e.dates if d.weekday() == 4]
        fri = [x for x in fri if x is not None]
        m, t = tstat(fri)
        mj = [e.ret_on(d) for d in e.dates if d.weekday() < 4]
        mj = [x for x in mj if x is not None]
        mm, tm = tstat(mj)
        P(f"  {name:11s} seance du VENDREDI : {m:+.2f} bp (t {t:+.2f}, n {len(fri)})  |  "
          f"Mon-Jeu : {mm:+.2f} (t {tm:+.2f}, n {len(mj)})")
    # part des vendredis ou l'etat est baissier, par ere (le conditionnant derive-t-il ?)
    for name, e in eqs.items():
        line = f"  {name:11s} part de vendredis 'J-1 baissier' par ere :"
        for a, b in ((2006, 2011), (2011, 2016), (2016, 2021), (2021, 2027)):
            xs = []
            for d in e.dates:
                if d.weekday() == 4 and dt.date(a, 1, 1) <= d < dt.date(b, 1, 1):
                    s = e.state(d)
                    if s:
                        xs.append(1 if s["prev"] < 0 else 0)
            line += f" {a}-{str(b)[2:]}={100*sum(xs)/len(xs):.0f}%(n{len(xs)})" if xs else " -"
        P(line)

    txt = "\n".join(rep)
    print(txt)
    out = f"{HOME}/projects/tb-research/reports/2026-10-15-equity-state-friday-fade.txt"
    with open(out, "w") as f:
        f.write(txt + "\n")
    print("\nrapport ->", out)


if __name__ == "__main__":
    main()
