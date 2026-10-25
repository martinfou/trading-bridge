#!/usr/bin/env python3
"""60e (samedi 17 oct 2026, revue de la semaine) — QUE COLLECTE LA CONFIG QUI VA SE DEPLOYER ?

Contexte : la revue du 16 oct a etabli (59e) que le fade refuge vit ENTIEREMENT dans la derniere heure
avant la CLOTURE (k=0 = -3.35 / -1.93 / -6.16 bp ; k>=1 ~ 0) et que la cloture suit l'heure d'ete.
La config qui passe les couts est donc : entree 13:00 UTC -> sortie a la CLOTURE REELLE (+3.88/+2.55/+6.87).

Or les decisions de deploiement du 16 oct (D57 + D61) decrivent une AUTRE config :
  entree = le dernier tick AVANT le halt de 17:00 ET (= la cloture), sortie = le premier tick
  tradable apres le portage (reouverture du dimanche).
D61 dit "confirme ou deplace par le rejeu de D55". Ce harnais fournit la mesure.

Question : decomposer le hold hebdomadaire en JAMBES et chiffrer chaque config candidate :
  A = 13:00 -> cloture reelle          (la config mesuree comme passant les couts, 58e/59e)
  B = cloture -> reouverture dimanche  (la config D57/D61 : la jambe week-end SEULE)
  C = 13:00 -> reouverture dimanche    (le hold complet)
Controles : placebo de MEME DUREE ancre en milieu de semaine (lun-jeu close -> open a J+2),
            distribution du HOLD (loi du 57e), test d'ANCRAGE de la reouverture (loi du 59e : la
            reouverture du dimanche bouge-t-elle avec l'heure d'ete, comme la cloture du vendredi ?),
            IS/OOS + eres, et le TRIM 5% (une queue n'est pas un edge).

Aucune ecriture dans le repo Java. Donnees : 3 crosses refuge OANDA H1 bid + 3 majeures Dukascopy H1 bid.
Couts = demi-spreads MESURES (55e). Carry = 0.274 pip/lot/jour par 1 % de differentiel (47e) ; sur les
crosses CHF, 1 pip = 1 bp (prix ~1.0-1.3), donc le carry se lit directement en bp/jour.
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
W0 = dt.datetime(2006, 1, 1, tzinfo=UTC)
W1 = dt.datetime(2026, 5, 20, tzinfo=UTC)

CROSSES = {
    "GBP_CHF": "gbpchf-h1-bid-oanda-2002-05-07-2026-10-09.csv",
    "EUR_CHF": "eurchf-h1-bid-oanda-2002-05-06-2026-10-09.csv",
    "AUD_CHF": "audchf-h1-bid-oanda-2004-05-31-2026-10-09.csv",
}
MAJORS = ["GBP_JPY", "GBP_USD", "USD_CAD"]
DOW = ["LUN", "MAR", "MER", "JEU", "VEN"]

# Cout A/R en bp (demi-spreads MESURES, 55e).
COST_BP = {"GBP_CHF": 2.278, "EUR_CHF": 1.702, "AUD_CHF": 2.584,
           "GBP_JPY": 1.630, "GBP_USD": 2.140, "USD_CAD": 2.140}

# Differentiel de taux 3 M 2026 (%, ordre de grandeur) pour un SELL du cross = short base, long quote.
# carry_bp_par_jour = -(rate_base - rate_quote) * 0.274  (47e : 1 % de diff = 0.274 pip/lot/jour ;
# sur les crosses CHF 1 pip = 1 bp).
RATE = {"GBP": 4.00, "EUR": 2.00, "AUD": 4.10, "CHF": 0.50, "JPY": 0.50, "USD": 4.00, "CAD": 2.75}
ERAS = [(2006, 2010), (2010, 2015), (2015, 2020), (2020, 2027)]
MAX_HOLD = 120.0   # h — un hold au-dela n'est pas un portage de week-end (trou de donnees)


# ------------------------------------------------------------------ chargement OHLC
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


def welch(a, b):
    if len(a) < 3 or len(b) < 3:
        return 0.0, 0.0
    ma, mb = sum(a) / len(a), sum(b) / len(b)
    va = sum((x - ma) ** 2 for x in a) / (len(a) - 1)
    vb = sum((x - mb) ** 2 for x in b) / (len(b) - 1)
    se = (va / len(a) + vb / len(b)) ** 0.5
    return ma - mb, ((ma - mb) / se if se > 0 else 0.0)


def trim(xs, frac=0.05):
    if len(xs) < 40:
        return xs
    k = max(1, int(frac * len(xs)))
    return sorted(xs)[k:len(xs) - k]


def carry_bp_day(sym):
    b, q = sym.split("_")
    return -(RATE[b] - RATE[q]) * 0.274


# ------------------------------------------------------------------ structure hebdomadaire
def weekly_structure(ohlc, sym):
    """Un enregistrement par vendredi :
       date, close_bar_time, close_price, hour_real, hour_all,
       open_first_week_bar (premier tick tradable de la semaine suivante), t_open, hold_h."""
    import bisect
    ts = sorted(ohlc)
    byday = {}
    for t in ts:
        byday.setdefault(t.date(), []).append(t)
    out = []
    for d in sorted(byday):
        if d.weekday() != 4:
            continue
        bars = sorted(byday[d])
        if not bars:
            continue
        hour_all = bars[-1].hour
        real = [t for t in bars if ohlc[t][1] > ohlc[t][2]]
        if not real:
            continue
        close_t = real[-1]
        hour_real = close_t.hour
        close_price = ohlc[close_t][3]
        # premier tick tradable de la semaine suivante = premiere barre REELLE apres close_t
        nxt = None
        i = bisect.bisect_right(ts, close_t)
        limit = close_t + dt.timedelta(days=5)
        while i < len(ts):
            t = ts[i]
            if t > limit:
                break
            if ohlc[t][1] > ohlc[t][2]:
                nxt = t
                break
            i += 1
        if nxt is None:
            continue
        hold = (nxt - (close_t + dt.timedelta(hours=1))).total_seconds() / 3600.0
        if hold <= 0 or hold > MAX_HOLD:
            continue
        out.append({"date": d, "close_t": close_t, "close": close_price,
                    "hour_real": hour_real, "hour_all": hour_all,
                    "open_next": ohlc[nxt][0], "t_open": nxt, "hold": hold})
    return out, byday


def price_open_bar(ohlc, day, hour):
    """Prix a l'instant hour:00 UTC = open de la barre ouverte a hour (timestamps = DEBUT de barre)."""
    t = dt.datetime.combine(day, dt.time(hour % 24), tzinfo=UTC)
    if t in ohlc:
        return ohlc[t][0]
    return None


def day_index(ohlc):
    """date -> [timestamps REELLES (high > low)] triees."""
    idx = {}
    for t, (o, h, l, c) in ohlc.items():
        if h > l:
            idx.setdefault(t.date(), []).append(t)
    for d in idx:
        idx[d].sort()
    return idx


def day_close(idx, ohlc, day):
    """Close de la derniere barre REELLE du jour `day`, et son heure."""
    ts = idx.get(day)
    if not ts:
        return None, None
    t = ts[-1]
    return ohlc[t][3], t


def day_open(idx, ohlc, day):
    """Open de la premiere barre REELLE du jour `day`."""
    ts = idx.get(day)
    if not ts:
        return None
    return ohlc[ts[0]][0]


# ------------------------------------------------------------------ main
def main():
    rep = []
    P = rep.append
    P("=" * 120)
    P("60e (samedi 17 oct 2026) — QUE COLLECTE LA CONFIG QUI VA SE DEPLOYER ?")
    P("A = 13:00 UTC -> CLOTURE reelle (mesuree comme passant les couts) | B = cloture -> reouverture")
    P("dimanche (D57/D61) | C = 13:00 -> reouverture. Convention publiee : FADE (SELL) ; fade > 0 = SELL gagnant.")
    P("=" * 120)

    ohlc = {}
    for sym, fname in CROSSES.items():
        ohlc[sym] = load_ohlc([f"{DATA}/{fname}"])
    for sym in MAJORS:
        ohlc[sym] = load_ohlc(major_files(sym))

    # ---------------- 0. flux et convention ----------------
    P("\n--- 0. FLUX")
    for sym in ohlc:
        kind = "CROSS (OANDA)" if sym in CROSSES else "MAJEURE (Dukascopy)"
        P(f"  {sym:8s} {kind:18s} bars={len(ohlc[sym]):7d}")
    P("  (majeures Dukascopy = 24x7 rempli : le samedi et le dimanche portent des barres plates de carry)")

    # ---------------- 1. ANCRAGE DE LA REOPENDURE (loi du 59e appliquee a la SORTIE) ----------------
    P("\n--- 1. ANCRAGE DE LA REOPENDURE DU DIMANCHE — bouge-t-elle avec l'heure d'ete, comme la cloture ?")
    P("  Heure UTC de la premiere barre REELLE de la semaine, par mois (crosses) :")
    struct = {}
    for sym in ohlc:
        s, byday = weekly_structure(ohlc[sym], sym)
        struct[sym] = s
    for sym in CROSSES:
        cnt = {}
        for r in struct[sym]:
            cnt.setdefault(r["t_open"].hour, []).append(r["t_open"].month)
        line = f"  {sym:8s}"
        for h in sorted(cnt):
            ms = cnt[h]
            if len(ms) < 10:
                continue
            share = {m: round(100.0 * ms.count(m) / len(ms)) for m in (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)}
            top = sorted(share.items(), key=lambda kv: -kv[1])[:4]
            line += f" | h{h:02d} n={len(ms):4d} M" + "M".join(f"{m:02d}:{v}%" for m, v in top if v >= 5)
        P(line)
    P("  Lecture : si la reouverture est a h22 en hiver et h21 en ete, alors 'sortir a 21:00 UTC' (D61)")
    P("  est la MEME erreur d'ancre fixe que le 59e vient de diagnostiquer sur la cloture.")
    P("  Heure de la derniere barre du vendredi (rappel du 59e) vs heure de la reouverture :")
    P(f"  {'SYMBOLE':9s}{'cloture h20':>13s}{'cloture h21':>13s}{'cloture h22+':>13s}"
      f"{'reouv h21':>11s}{'reouv h22':>11s}{'reouv h23':>11s}")
    for sym in CROSSES:
        def cnt_h(key, hours):
            return sum(1 for r in struct[sym] if key(r) in hours)
        P(f"  {sym:9s}{cnt_h(lambda r: r['hour_real'], (20,)):13d}"
          f"{cnt_h(lambda r: r['hour_real'], (21,)):13d}"
          f"{cnt_h(lambda r: r['hour_real'], (22, 23)):13d}"
          f"{cnt_h(lambda r: r['t_open'].hour, (21,)):11d}"
          f"{cnt_h(lambda r: r['t_open'].hour, (22,)):11d}"
          f"{cnt_h(lambda r: r['t_open'].hour, (23,)):11d}")

    # ---------------- 2. DISTRIBUTION DU HOLD (loi du 57e) ----------------
    P("\n--- 2. DISTRIBUTION DU HOLD (loi du 57e : le hold est EMERGENT du flux — le publier)")
    P(f"  {'SYMBOLE':9s}{'n':>6s}{'hold moy':>10s}{'min':>8s}{'med':>8s}{'max':>8s}")
    for sym in ohlc:
        hs = [r["hold"] for r in struct[sym]]
        if not hs:
            continue
        P(f"  {sym:9s}{len(hs):6d}{sum(hs)/len(hs):10.1f}{min(hs):8.1f}"
          f"{st.median(hs):8.1f}{max(hs):8.1f}")

    # ---------------- 3. LES TROIS CONFIGS, PAR JAMBE ----------------
    P("\n--- 3. LES TROIS CONFIGS, DECOMPOSEES EN JAMBES (brut, bp de fade)")
    P("  A = 13:00 -> cloture | B = cloture -> reouverture (D57/D61) | C = 13:00 -> reouverture")
    P("  net = fade brut - cout A/R - carry (0 / 1 / 2 jours factures ; 1 jour = 1 rollover selon le")
    P("  correctif du 15 oct, le week-end n'est plus facture deux fois).")
    P(f"  {'SYMBOLE':9s}{'A brut':>9s}{'A t':>7s} | {'B brut':>9s}{'B t':>7s} | {'C brut':>9s}{'C t':>7s}"
      f" | {'cout':>6s}{'carry/j':>8s}")
    legs = {}
    for sym in ohlc:
        rows = struct[sym]
        A, B, C = [], [], []
        for r in rows:
            p13 = price_open_bar(ohlc[sym], r["date"], 13)
            if not p13 or p13 <= 0 or r["close"] <= 0 or r["open_next"] <= 0:
                continue
            a = -math.log(r["close"] / p13) * 1e4
            b = -math.log(r["open_next"] / r["close"]) * 1e4
            A.append(a); B.append(b); C.append(a + b)
        ma, ta = tstat(A); mb, tb = tstat(B); mc, tc = tstat(C)
        legs[sym] = {"A": A, "B": B, "C": C}
        P(f"  {sym:9s}{ma:+9.2f}{ta:+7.2f} | {mb:+9.2f}{tb:+7.2f} | {mc:+9.2f}{tc:+7.2f}"
          f" | {COST_BP[sym]:6.3f}{carry_bp_day(sym):+8.2f}")

    # ---------------- 3b. P0 NON-REGRESSION : reproduire la table du 59e (entree = close de la barre 12) ----------------
    P("\n--- 3b. P0 NON-REGRESSION — table du 59e (entree = CLOSE de la barre [12,13), sortie = close de la")
    P("  derniere barre reelle) : net = fade - cout. On doit retrouver +3.88 / +2.55 / +6.87 (a l'entree")
    P("  conventionnelle pres : l'entree de ce harnais est l'OPEN de [13,14), un tick plus tard).")
    P(f"  {'SYMBOLE':9s}{'brut 59e':>10s}{'net 59e':>9s}{'brut ici (A)':>13s}{'ecart':>8s}")
    for sym in CROSSES:
        xs = []
        for r in struct[sym]:
            t13 = dt.datetime.combine(r["date"], dt.time(12, 0), tzinfo=UTC)
            if t13 in ohlc[sym] and r["close"] > 0:
                xs.append(-math.log(r["close"] / ohlc[sym][t13][3]) * 1e4)
        m = tstat(xs)[0]
        ma = tstat(legs[sym]["A"])[0] if sym in legs else 0.0
        P(f"  {sym:9s}{m:+10.2f}{m - COST_BP[sym]:+9.2f}{ma:+13.2f}{ma - m:+8.2f}")

    # ---------------- 4. NET PAR CONFIG (cout + carry) ----------------
    P("\n--- 4. NET PAR CONFIG — A (0 nuit) / B (1 et 2 jours) / C (1 et 2 jours)")
    P(f"  {'SYMBOLE':9s}{'A net':>9s}{'B net j1':>10s}{'B net j2':>10s}{'C net j1':>10s}{'C net j2':>10s}")
    for sym in ohlc:
        c = COST_BP[sym]; k = carry_bp_day(sym)
        ma = tstat(legs[sym]["A"])[0]; mb = tstat(legs[sym]["B"])[0]; mc = tstat(legs[sym]["C"])[0]
        P(f"  {sym:9s}{ma-c:+9.2f}{mb-c-1*k:+10.2f}{mb-c-2*k:+10.2f}{mc-c-1*k:+10.2f}{mc-c-2*k:+10.2f}")

    # ---------------- 5. CONTROLE DE MEME DUREE ancre en milieu de semaine ----------------
    P("\n--- 5. PLACEBO DE MEME DUREE : close -> open a J+2, ancre lundi / mardi / mercredi / jeudi")
    P("  Un portage de week-end doit se distinguer d'un trou de 2 nuits ordinaire.")
    P(f"  {'SYMBOLE':9s}{'LUN->MER':>10s}{'MAR->JEU':>10s}{'MER->VEN':>10s}{'VEN->DIM':>10s}"
      f"{'diff VEN-moy':>14s}{'t':>7s}")
    for sym in ohlc:
        idx = day_index(ohlc[sym])
        cols = []
        for wd in (0, 1, 2):
            xs = []
            for r in struct[sym]:
                d0 = r["date"] - dt.timedelta(days=4 - wd)   # ramene au lundi..mercredi de la meme semaine
                c0, _ = day_close(idx, ohlc[sym], d0)
                o2 = day_open(idx, ohlc[sym], d0 + dt.timedelta(days=2))
                if c0 and o2 and c0 > 0:
                    xs.append(-math.log(o2 / c0) * 1e4)
            cols.append(xs)
        friday = legs[sym]["B"]
        mid = [v for xs in cols for v in xs]
        dv, dt_ = welch(friday, mid)
        P(f"  {sym:9s}" + "".join(f"{tstat(x)[0]:+10.2f}" for x in cols) +
          f"{tstat(friday)[0]:+10.2f}{dv:+14.2f}{dt_:+7.2f}")

    # ---------------- 6. IS/OOS + ERES + TRIM sur la jambe B ----------------
    P("\n--- 6. LA JAMBE B (le portage de week-end SEUL, ce que D57/D61 collecte) : IS/OOS, eres, TRIM")
    P(f"  {'SYMBOLE':9s}{'FULL':>9s}{'IS 06-15':>10s}{'OOS 16-26':>11s}{'mediane':>9s}{'hit>0':>7s}"
      f"{'TRIM5':>8s}{'n':>7s}{'eres (4)':>36s}")
    for sym in ohlc:
        rows = struct[sym]
        xs = []
        for r in rows:
            if r["close"] > 0 and r["open_next"] > 0:
                xs.append((r["date"], -math.log(r["open_next"] / r["close"]) * 1e4))
        full = [v for _, v in xs]
        isx = [v for d, v in xs if d < dt.date(2016, 1, 1)]
        oosx = [v for d, v in xs if d >= dt.date(2016, 1, 1)]
        med = st.median(full) if full else 0.0
        hit = 100.0 * sum(1 for v in full if v > 0) / len(full) if full else 0.0
        eras = []
        for a, b in ERAS:
            ex = [v for d, v in xs if dt.date(a, 1, 1) <= d < dt.date(b, 1, 1)]
            eras.append(f"{tstat(ex)[0]:+7.2f}" if len(ex) > 40 else "   n/a ")
        P(f"  {sym:9s}{tstat(full)[0]:+9.2f}{tstat(isx)[0]:+10.2f}{tstat(oosx)[0]:+11.2f}"
          f"{med:+9.2f}{hit:6.0f}%{tstat(trim(full))[0]:+8.2f}{len(full):7d}{''.join(eras):>36s}")

    # ---------------- 7. OU SE PERD LA JAMBE A : le profil cloture-relatif (rappel 59e) ----------------
    P("\n--- 7. DECOMPOSITION DE A EN BARRES (k barres avant la cloture reelle) — vendredi, crosses")
    P("  Si B (entree a la cloture) vaut ~0, c'est parce que la jambe k=0 est DEJA realisee a l'entree.")
    P(f"  {'SYMBOLE':9s}" + "".join(f"{'k=' + str(k):>16s}" for k in range(4)))
    for sym in CROSSES:
        line = f"  {sym:9s}"
        for k in range(4):
            xs = []
            for r in struct[sym]:
                t = r["close_t"] - dt.timedelta(hours=k)
                if t in ohlc[sym] and ohlc[sym][t][0] > 0:
                    o, h, l, c = ohlc[sym][t]
                    xs.append(-math.log(c / o) * 1e4)
            m, t_ = tstat(xs)
            line += f"{m:+10.2f}(t{t_:+4.1f})"
        P(line)
    P("  Lecture : A capte k=0 ; entrer A LA CLOTURE (D57) arrive APRES le mouvement.")

    # ---------------- 9. CONTROLE DE LA REOPENDURE : ne garder que les vraies reouvertures du dimanche ----------------
    P("\n--- 9. CONTROLE DE LA REOPENDURE — ne garder que les semaines dont le premier tick tradable est")
    P("  un DIMANCHE (soir). Les autres (h16-h20 = lundi matin ou donnee manquante) melangent la derive")
    P("  du lundi au 'portage de week-end'.")
    P(f"  {'SYMBOLE':9s}{'n dim':>6s}{'n jete':>7s}{'B brut':>9s}{'B net j1':>9s}{'C net j1':>9s}"
      f"{'A IS':>8s}{'A OOS':>8s}{'B IS':>8s}{'B OOS':>8s}{'C IS':>8s}{'C OOS':>8s}")
    for sym in ohlc:
        rows = [r for r in struct[sym] if r["t_open"].weekday() == 6]
        dropped = len(struct[sym]) - len(rows)
        A, B, C = [], [], []
        for r in rows:
            p13 = price_open_bar(ohlc[sym], r["date"], 13)
            if not p13 or p13 <= 0 or r["close"] <= 0 or r["open_next"] <= 0:
                continue
            b = -math.log(r["open_next"] / r["close"]) * 1e4
            a = -math.log(r["close"] / p13) * 1e4
            A.append((r["date"], a)); B.append((r["date"], b)); C.append((r["date"], a + b))
        if len(B) < 40:
            P(f"  {sym:9s}{len(rows):6d}{dropped:7d}   n/a")
            continue
        c = COST_BP[sym]; k = carry_bp_day(sym)
        def per(xs):
            i = [v for d, v in xs if d < dt.date(2016, 1, 1)]
            o_ = [v for d, v in xs if d >= dt.date(2016, 1, 1)]
            return tstat(i)[0], tstat(o_)[0]
        ai, ao = per(A); bi, bo = per(B); ci, co = per(C)
        P(f"  {sym:9s}{len(rows):6d}{dropped:7d}{tstat([v for _, v in B])[0]:+9.2f}"
          f"{tstat([v for _, v in B])[0]-c-k:+9.2f}{tstat([v for _, v in C])[0]-c-k:+9.2f}"
          f"{ai-c:+8.2f}{ao-c:+8.2f}{bi-c-k:+8.2f}{bo-c-k:+8.2f}"
          f"{ci-c-k:+8.2f}{co-c-k:+8.2f}")

    # ---------------- 10. SENSIBILITE AU COUT D'EXECUTION A LA CLOTURE ----------------
    P("\n--- 10. SENSIBILITE : la jambe B paie son entree A LA CLOTURE, le moment ou le spread est le plus")
    P("  large de la semaine (donnee bid SEULE : on ne peut pas le mesurer, seulement le borner).")
    P("  Net de B si le demi-spread a la cloture vaut 1x / 1.5x / 2x / 3x le demi-spread mesure de la paire :")
    P(f"  {'SYMBOLE':9s}{'cout mesure':>12s}{'B x1':>9s}{'B x1.5':>9s}{'B x2':>9s}{'B x3':>9s}"
      f"{'seuil de mort':>14s}")
    for sym in CROSSES:
        rows = [r for r in struct[sym] if r["t_open"].weekday() == 6]
        if not rows:
            continue
        bs = [-math.log(r["open_next"] / r["close"]) * 1e4 for r in rows]
        mb = tstat(bs)[0]
        c = COST_BP[sym]; k = carry_bp_day(sym)
        # seuil : multiple du cout ou le net s'annule (a carry 1 jour)
        seuil = (mb - k) / c if c else float("nan")
        P(f"  {sym:9s}{c:12.3f}{mb-c-k:+9.2f}{mb-1.5*c-k:+9.2f}{mb-2*c-k:+9.2f}{mb-3*c-k:+9.2f}"
          f"{seuil:13.2f}x")

    # ---------------- 8. VERDICT ----------------
    P("\n--- 8. LECTURE")
    P("  Le tableau 4 dit ce que chaque config encaisse net ; le tableau 5 separe un portage de week-end")
    P("  d'un trou de 2 nuits ordinaire ; le tableau 6 dit si la jambe B est stable (IS/OOS, eres, TRIM).")
    P("  Le tableau 1 dit si l'ancre de SORTIE de D61 (dimanche 21:00 UTC) est une heure fixe valide.")

    txt = "\n".join(rep)
    print(txt)
    out = f"{HOME}/projects/tb-research/reports/2026-10-17-weekend-hold.txt"
    with open(out, "w") as f:
        f.write(txt + "\n")
    print("\nrapport ->", out)


if __name__ == "__main__":
    main()
