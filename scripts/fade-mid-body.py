#!/usr/bin/env python3
"""
65e (vendredi 23 oct 2026, DEEP DIVE) — LA BRANCHE SURVIVANTE (61e, veille de conge) SURVIT-ELLE AU MID ?

Contexte. Le 64e a etabli que le fade de la derniere heure mesure sur des barres BID est
l'OUVERTURE DU SPREAD : sur le MID il vaut ~0 (t ~ -0.5). Le 61e, seul resultat encore
debout de la famille, a ete mesure sur les MEMES barres BID et avec la MEME ancre k=0
(derniere barre reelle). Deux questions, une seule experience :

  Q1  L'effet du 61e survit-il sur le MID ? (si non, c'est le meme artefact)
  Q2  Existe-t-il une fenetre du CORPS de la journee (sortie H-1, H-2, H-3) qui porte une
      derive de MID suffisante pour payer les DEUX demi-spreads MESURES ? (porte ouverte
      par le 64e : "une entree moins chere, ou un horizon plus long")

Identite (memes conventions que le 64e, mais bilaterale) :
  SELL : vend au BID a t0, rachete a l'ASK a t1
  realise = (mid0 - mid1) - (s0 + s1)/2     [s = spread du moment, en bp du mid]
  <=>  = (bid0 - bid1) - s1                 [identite du 64e, verifiee ici comme controle P0]

Unite = bp. Signe ecrit : FADE = SELL, fade > 0 = SELL gagnant.
Aucun carry : holds intra-journaliers seulement (le hold vers la session suivante est
rapporte separement et n'est PAS recommande sans table de taux par annee).
Donnees : OANDA H1 BID+ASK (price=BA), 3 crosses refuge, 2005-2026.
"""
import csv
import datetime as dt
import math
import os
import statistics as st
from collections import defaultdict

HOME = os.path.expanduser("~")
D = f"{HOME}/projects/tb-research/data/oanda-ba"
UTC = dt.timezone.utc
CROSSES = ["GBP_CHF", "EUR_CHF", "AUD_CHF"]
DOW = ["LUN", "MAR", "MER", "JEU", "VEN"]
ERAS = [(2005, 2009), (2010, 2013), (2014, 2017), (2018, 2021), (2022, 2026)]


# ----------------------------------------------------------------- chargement
def load(inst):
    """{t: (bid_o, bid_h, bid_l, bid_c, ask_o, ask_c)}"""
    out = {}
    path = os.path.join(D, f"{inst}_H1_BA.csv")
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            t = dt.datetime.strptime(r["time"][:19] + "Z", "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=UTC)
            out[t] = (float(r["bid_o"]), float(r["bid_h"]), float(r["bid_l"]), float(r["bid_c"]),
                      float(r["ask_o"]), float(r["ask_c"]))
    return dict(sorted(out.items()))


def mid(o, a):
    return (o + a) / 2.0


def sp_bp(b, a):
    m = (b + a) / 2.0
    return (a - b) / m * 1e4 if m > 0 else 0.0


# ----------------------------------------------------------------- stats
def tstat(xs):
    n = len(xs)
    if n < 3:
        return (sum(xs) / n if n else 0.0), 0.0
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


# ----------------------------------------------------------------- calendrier US (repris du 61e)
def nth_weekday(year, month, weekday, n):
    d = dt.date(year, month, 1)
    c = 0
    while d.month == month:
        if d.weekday() == weekday:
            c += 1
            if c == n:
                return d
        d += dt.timedelta(days=1)
    raise ValueError


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


def us_closures(y0=2005, y1=2026):
    out = {}
    for y in range(y0, y1 + 1):
        items = [(observed(dt.date(y, 1, 1)), "NewYear"),
                 (nth_weekday(y, 1, 0, 3), "MLK"),
                 (nth_weekday(y, 2, 0, 3), "Presidents"),
                 (easter(y) - dt.timedelta(days=2), "GoodFriday"),
                 (last_weekday(y, 5, 0), "Memorial"),
                 (nth_weekday(y, 9, 0, 1), "Labor"),
                 (nth_weekday(y, 11, 3, 4), "Thanksgiving"),
                 (observed(dt.date(y, 7, 4)), "July4"),
                 (observed(dt.date(y, 12, 25)), "Christmas")]
        if y >= 2022:
            items.append((observed(dt.date(y, 6, 19)), "Juneteenth"))
        for d, name in items:
            if d.weekday() >= 5 or d.year < y0:
                continue
            out[d] = name
    return out


# ----------------------------------------------------------------- sessions
def build(d):
    """Une entree par jour de cotation de semaine, barres REELLES (bid_h > bid_l)."""
    byday = defaultdict(list)
    for t, v in d.items():
        if v[1] > v[2]:
            byday[t.date()].append(t)
    sess = []
    for day in sorted(byday):
        if day.weekday() >= 5:
            continue
        ts = sorted(byday[day])
        if len(ts) < 5:
            continue
        ct = ts[-1]
        t13 = dt.datetime.combine(day, dt.time(13, 0), tzinfo=UTC)
        if t13 not in d or t13 not in ts:
            continue
        sess.append({"date": day, "close_t": ct, "ts": ts, "t13": t13})
    for i, s in enumerate(sess):
        s["gap"] = (sess[i + 1]["date"] - s["date"]).days if i + 1 < len(sess) else None
    return sess


def tag(sess, clos):
    for s in sess:
        s["tag"] = "OTHER"
        s["holiday"] = None
    pos = {s["date"]: i for i, s in enumerate(sess)}
    for hd, name in sorted(clos.items()):
        before = [s["date"] for s in sess if s["date"] < hd]
        if not before:
            continue
        pre = before[-1]
        if (hd - pre).days > 5:
            continue
        if sess[pos[pre]]["tag"] == "OTHER":
            sess[pos[pre]]["tag"] = "PRE"
            sess[pos[pre]]["holiday"] = name
    return sess


# ----------------------------------------------------------------- jambe
def leg(d, s, kexit):
    """Retourne (bid_fade, mid_fade, s_in, s_out) pour SELL de t13.open -> sortie.
    kexit=0 : close de la derniere barre reelle ; kexit>=1 : OPEN de la barre close_t - kexit h."""
    bo, _, _, _, ao, _ = d[s["t13"]]
    m_in = mid(bo, ao)
    s_in = sp_bp(bo, ao)
    if kexit == 0:
        te = s["close_t"]
        be, _, _, bce, aoe, ace = d[te]
        b_out, a_out = bce, ace
    else:
        te = s["close_t"] - dt.timedelta(hours=kexit)
        if te not in d:
            return None
        be, _, _, _, aoe, _ = d[te]
        b_out, a_out = be, aoe
    m_out = mid(b_out, a_out)
    if m_in <= 0 or m_out <= 0:
        return None
    s_out = sp_bp(b_out, a_out)
    bid_fade = (bo - b_out) / bo * 1e4          # identite du 64e : cout = spread de sortie
    mid_fade = (m_in - m_out) / m_in * 1e4
    return bid_fade, mid_fade, s_in, s_out


def cat(s, key):
    """key : ALL / FRI / PREFRI / MIDWEEK / PRETHU / MONTUE"""
    wd = s["date"].weekday()
    if key == "ALL":
        return True
    if key == "FRI":
        return s["tag"] == "OTHER" and wd == 4 and s["gap"] == 3
    if key == "PREFRI":
        return s["tag"] == "PRE" and wd == 4
    if key == "PRETHU":
        return s["tag"] == "PRE" and wd == 3
    if key == "MIDWEEK":
        return s["tag"] == "PRE" and wd in (1, 2)
    if key == "MONTUE":
        return s["tag"] == "OTHER" and wd in (0, 1, 2, 3) and s["gap"] == 1
    return False


CATS = [("FRI ordinaire", "FRI"), ("PRE-FRI (lun ferie)", "PREFRI"),
        ("PRE-JEU (ven ferie)", "PRETHU"), ("PRE mar/mer", "MIDWEEK"),
        ("lun-jeu ordinaire", "MONTUE")]
EXITS = [("H-3", 3), ("H-2", 2), ("H-1", 1), ("close", 0)]


def main():
    rep = []
    P = rep.append
    P("=" * 132)
    P("65e (ven 23 oct 2026) — LA BRANCHE SURVIVANTE (61e) SURVIT-ELLE AU MID ?  Sortie dans le CORPS de la journee.")
    P("FADE = SELL. Unite bp. NET = fade_mid - (spread_entree + spread_sortie)/2, spreads MESURES a l'instant de chaque jambe.")
    P("=" * 132)

    clos = us_closures()
    data = {}
    P("\n--- 0. FLUX (OANDA H1 BID+ASK)")
    for c in CROSSES:
        d = load(c)
        s = tag(build(d), clos)
        data[c] = (d, s)
        ks = sorted(d)
        P(f"  {c:8s} bars={len(d):7d}  sessions={len(s):5d}  {ks[0].date()} -> {ks[-1].date()}"
          f"   PRE={sum(1 for x in s if x['tag']=='PRE')}")

    # ---------- P0 : identite comptable bid vs mid
    P("\n--- P0. IDENTITE COMPTABLE (controle de non-regression du 64e)")
    P("  realise(bid) = (bid0-bid1) - s_sortie   |   realise(mid) = (mid0-mid1) - (s_in+s_out)/2")
    P("  Les deux doivent donner le MEME net a 0.02 bp pres sur chaque ligne de sortie.")
    for c in CROSSES:
        d, s = data[c]
        diffs = []
        for x in s:
            for _, k in EXITS:
                r = leg(d, x, k)
                if r is None:
                    continue
                bid_f, mid_f, s_in, s_out = r
                a = bid_f - s_out
                b = mid_f - (s_in + s_out) / 2
                diffs.append(abs(a - b))
        P(f"  {c:8s} n={len(diffs):6d}  ecart max={max(diffs) if diffs else 0:.4f} bp  "
          f"moyen={st.mean(diffs) if diffs else 0:.4f} bp")

    # ---------- A : spread entree vs sortie, PRE vs ordinaire
    P("\n--- A. LE SPREAD, PAR CATEGORIE (le point qui explique le 61e sans effet de prix)")
    P(f"  {'CROIX':9s}{'CATEGORIE':20s}{'n':>5s}{'spread 13h':>11s}{'spread H-1':>11s}"
      f"{'spread close':>13s}{'close/H-1':>10s}")
    for c in CROSSES:
        d, s = data[c]
        for lbl, key in CATS:
            rows = [x for x in s if cat(x, key)]
            if len(rows) < 10:
                continue
            si, s1, s0 = [], [], []
            for x in rows:
                r1 = leg(d, x, 1)
                r0 = leg(d, x, 0)
                if r1:
                    si.append(r1[2])
                    s1.append(r1[3])
                if r0:
                    s0.append(r0[3])
            if not si or not s1 or not s0:
                continue

            r = st.mean(s0) / st.mean(s1) if st.mean(s1) else 0
            P(f"  {c:9s}{lbl:20s}{len(rows):5d}{st.mean(si):11.2f}{st.mean(s1):11.2f}"
              f"{st.mean(s0):13.2f}{r:10.2f}")
        P("")

    # ---------- B : le test principal, par sortie
    P("\n--- B. FADE PAR CATEGORIE ET PAR SORTIE : bid / mid / spread de sortie / NET (mid) / t")
    for c in CROSSES:
        d, s = data[c]
        P(f"\n  {c}")
        P(f"  {'CATEGORIE':20s}{'SORTIE':8s}{'n':>5s}{'fade BID':>10s}{'fade MID':>10s}"
          f"{'s_in':>7s}{'s_out':>7s}{'NET':>8s}{'t(net)':>8s}")
        for lbl, key in CATS:
            rows = [x for x in s if cat(x, key)]
            for xl, k in EXITS:
                r = [leg(d, x, k) for x in rows]
                r = [y for y in r if y]
                if len(r) < 10:
                    continue
                bf = [y[0] for y in r]
                mf = [y[1] for y in r]
                sin = [y[2] for y in r]
                sout = [y[3] for y in r]
                net = [y[1] - (y[2] + y[3]) / 2 for y in r]
                m, t = tstat(net)
                P(f"  {lbl:20s}{xl:8s}{len(r):5d}{st.mean(bf):+10.2f}{st.mean(mf):+10.2f}"
                  f"{st.mean(sin):7.2f}{st.mean(sout):7.2f}{m:+8.2f}{t:+8.2f}")
            P("")

    # ---------- B2 : le k=0 du 61e (DERNIERE BARRE SEULE), bid vs mid — le controle decisif
    P("\n--- B2. LE k=0 DU 61e (derniere barre reelle SEULE), BID vs MID — le controle decisif")
    P("  Le 61e publiait +4.77/+2.77/+7.85 bp (PRE-FRI) et +3.14/+1.84/+5.89 (FRI ordinaire) en BID.")
    P("  Si le MID est nul et le BID positif, l'effet du 61e est le MEME artefact que le 64e.")
    P(f"  {'CROIX':9s}{'CATEGORIE':20s}{'n':>5s}{'k0 BID':>9s}{'t':>7s}{'k0 MID':>9s}{'t':>7s}"
      f"{'s_open':>8s}{'s_close':>8s}{'NET':>8s}{'t':>7s}")
    for c in CROSSES:
        d, s = data[c]
        for lbl, key in CATS:
            rows = [x for x in s if cat(x, key)]
            vals = []
            for x in rows:
                ct = x["close_t"]
                if ct not in d:
                    continue
                bo, _, _, bc, ao, ac = d[ct]
                m_in, m_out = mid(bo, ao), mid(bc, ac)
                if m_in <= 0 or m_out <= 0:
                    continue
                s_in, s_out = sp_bp(bo, ao), sp_bp(bc, ac)
                vals.append(((bo - bc) / bo * 1e4, (m_in - m_out) / m_in * 1e4,
                             s_in, s_out, (m_in - m_out) / m_in * 1e4 - (s_in + s_out) / 2))
            if len(vals) < 10:
                continue
            bf = [v[0] for v in vals]
            mf = [v[1] for v in vals]
            net = [v[4] for v in vals]
            P(f"  {c:9s}{lbl:20s}{len(vals):5d}{st.mean(bf):+9.2f}{tstat(bf)[1]:+7.2f}"
              f"{st.mean(mf):+9.2f}{tstat(mf)[1]:+7.2f}"
              f"{st.mean([v[2] for v in vals]):8.2f}{st.mean([v[3] for v in vals]):8.2f}"
              f"{st.mean(net):+8.2f}{tstat(net)[1]:+7.2f}")
        P("")

    # ---------- C : stabilite du NET pour les 4 meilleures lectures (IS/OOS + eres)
    P("\n--- C. STABILITE (IS 2005-2015 / OOS 2016-2026 + 5 eres) des cellules |t| >= 2 en NET")
    P(f"  {'CROIX':9s}{'CATEGORIE':20s}{'SORTIE':8s}{'n':>5s}{'NET':>8s}{'t':>7s}"
      f"{'IS':>8s}{'OOS':>8s} | eres")
    for c in CROSSES:
        d, s = data[c]
        for lbl, key in CATS:
            rows = [x for x in s if cat(x, key)]
            for xl, k in EXITS:
                r = [(x["date"], leg(d, x, k)) for x in rows]
                r = [(dt_, y) for dt_, y in r if y]
                if len(r) < 30:
                    continue
                net = [y[1] - (y[2] + y[3]) / 2 for _, y in r]
                m, t = tstat(net)
                if abs(t) < 2.0:
                    continue
                isx = [v for dt_, v in zip([a for a, _ in r], net) if dt_ < dt.date(2016, 1, 1)]
                oos = [v for dt_, v in zip([a for a, _ in r], net) if dt_ >= dt.date(2016, 1, 1)]
                eras = []
                for a, b in ERAS:
                    ex = [v for dt_, v in zip([q for q, _ in r], net)
                          if dt.date(a, 1, 1) <= dt_ < dt.date(b, 1, 1)]
                    eras.append(f"{tstat(ex)[0]:+6.2f}" if len(ex) >= 10 else "  n/a ")
                P(f"  {c:9s}{lbl:20s}{xl:8s}{len(r):5d}{m:+8.2f}{t:+7.2f}"
                  f"{tstat(isx)[0]:+8.2f}{tstat(oos)[0]:+8.2f} | " + " ".join(eras))
    P("  Lecture : un NET positif FULL mais negatif en IS ou en OOS = late-bloomer, pas un edge.")

    # ---------- D : hold vers la session suivante (porte 'horizon plus long')
    P("\n--- D. HORIZON PLUS LONG — 13:00 -> 13:00 de la session SUIVANTE (mid, spreads mesures)")
    P("  ⚠️ hold de 24 h a 96 h : le carry N'EST PAS compte ici (table de taux par annee requise).")
    P(f"  {'CROIX':9s}{'CATEGORIE':20s}{'n':>5s}{'fade MID':>10s}{'s_in':>7s}{'s_out':>7s}"
      f"{'NET':>8s}{'t':>7s}{'hold h':>8s}")
    for c in CROSSES:
        d, s = data[c]
        pos = {x["date"]: i for i, x in enumerate(s)}
        for lbl, key in CATS:
            rows = [x for x in s if cat(x, key)]
            vals = []
            for x in rows:
                i = pos[x["date"]]
                if i + 1 >= len(s):
                    continue
                nx = s[i + 1]
                a = leg(d, x, 0)
                if a is None or nx["t13"] not in d:
                    continue
                bo, _, _, _, ao, _ = d[x["t13"]]
                m_in = mid(bo, ao)
                bo2, _, _, _, ao2, _ = d[nx["t13"]]
                m_out = mid(bo2, ao2)
                s_in = sp_bp(bo, ao)
                s_out = sp_bp(bo2, ao2)
                mid_f = (m_in - m_out) / m_in * 1e4
                net = mid_f - (s_in + s_out) / 2
                hold = (nx["t13"] - x["t13"]).total_seconds() / 3600
                vals.append((mid_f, s_in, s_out, net, hold))
            if len(vals) < 20:
                continue
            net = [v[3] for v in vals]
            m, t = tstat(net)
            P(f"  {c:9s}{lbl:20s}{len(vals):5d}{st.mean([v[0] for v in vals]):+10.2f}"
              f"{st.mean([v[1] for v in vals]):7.2f}{st.mean([v[2] for v in vals]):7.2f}"
              f"{m:+8.2f}{t:+7.2f}{st.mean([v[4] for v in vals]):8.0f}")
        P("")

    # ---------- E : profil horaire du spread (le modele de cout suppose un demi-spread CONSTANT)
    try:
        from zoneinfo import ZoneInfo
        NY = ZoneInfo("America/New_York")
    except Exception:
        NY = None
    if NY:
        P("\n--- E. PROFIL HORAIRE DU SPREAD (heure ET de FIN de barre) — le modele de cout est une CONSTANTE")
        P("  RealCostModel utilise un demi-spread mesure GLOBAL par symbole. S'il existe une structure horaire,")
        P("  toute fenetre qui touche les heures larges est SOUS-COSTEE d'un facteur mesurable.")
        for c in CROSSES:
            d, s = data[c]
            byh = defaultdict(list)
            allsp = []
            for x in s:
                ts = x["ts"]
                for t in ts:
                    v = d[t]
                    spv = sp_bp(v[3], v[5])          # spread au close de la barre
                    if spv <= 0 or spv > 200:
                        continue
                    hb = (t + dt.timedelta(hours=1)).astimezone(NY).hour   # heure ET de FIN de barre
                    byh[hb].append(spv)
                    allsp.append(spv)
            med = st.median(allsp)
            P(f"\n  {c}  spread median (toutes barres) = {med:.2f} bp   n={len(allsp)}")
            line = "   h ET :"
            v1 = "   bp   :"
            ratio = "   x med:"
            for h in range(24):
                xs = byh.get(h, [])
                line += f"{h:>6d}"
                v1 += f"{st.mean(xs):6.2f}" if len(xs) > 100 else "     -"
                ratio += f"{st.mean(xs)/med:6.2f}" if len(xs) > 100 else "     -"
            P(line)
            P(v1)
            P(ratio)
        P("\n  Lecture : h ET = heure de FIN de la barre. Un facteur 5x sur une heure d'entree/sortie suffit a")
        P("  inverser le signe d'un net ; c'est le mecanisme du 64e, generalise a toutes les heures.")

    txt = "\n".join(rep)
    print(txt)
    out = f"{HOME}/projects/tb-research/reports/2026-10-23-fade-mid-body.txt"
    with open(out, "w") as f:
        f.write(txt + "\n")
    print("\nrapport ->", out)


if __name__ == "__main__":
    main()
