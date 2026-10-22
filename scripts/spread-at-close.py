#!/usr/bin/env python3
"""
64e — LE SPREAD AU MOMENT DE LA SORTIE (donnee ASK, enfin disponible).
La piece manquante signalee 3x (fade 59e, lane D47, rollover 62e) : toutes les mesures
anterieures portaient sur des barres BID seules, donc le cout de sortie ne pouvait qu'etre BORNE.

Identite utilisee (edge mesure sur barres BID, position SHORT) :
    P&L realise = (bid_t0 - ask_t1) = (bid_t0 - bid_t1) - spread(t1)
  => net = fade_bid (13:00 -> cloture) - SPREAD PLEIN A LA SORTIE.
C'est le SEUL chiffre qui manquait : le spread de la barre de sortie, mesure, pas suppose.

Sections :
  0. Qualite du flux (barres, heure de la derniere barre du vendredi par mois)
  1. Profil du spread par heure UTC le vendredi (le dernier age est-il vraiment le plus large ?)
  2. Par vendredi : fade bid / spread entree / spread tenu / spread sortie / net
  3. Ratio de elargissement sortie/tenu, par ere et par annee
  4. IS/OOS du net avec le spread MESURE
  5. M5 : ou exactement le spread s'elargit dans la derniere heure (sortie a H-10 ?)
"""
import os, csv, sys, statistics as st
from datetime import datetime, timezone, timedelta
from collections import defaultdict

D = os.path.expanduser("~/projects/tb-research/data/oanda-ba")
FMT = "%Y-%m-%dT%H:%M:%SZ"

def load(inst, gran):
    p = os.path.join(D, f"{inst}_{gran}_BA.csv")
    if not os.path.exists(p):
        return []
    out = []
    with open(p) as f:
        for r in csv.DictReader(f):
            try:
                t = datetime.strptime(r["time"][:19] + "Z", FMT).replace(tzinfo=timezone.utc)
                bo, bh, bl, bc = (float(r[k]) for k in ("bid_o", "bid_h", "bid_l", "bid_c"))
                ao, ah, al, ac = (float(r[k]) for k in ("ask_o", "ask_h", "ask_l", "ask_c"))
            except Exception:
                continue
            if bo <= 0 or ao <= 0 or ao < bo:
                continue
            out.append(dict(t=t, bo=bo, bc=bc, bh=bh, bl=bl, ao=ao, ac=ac, ah=ah, al=al))
    out.sort(key=lambda x: x["t"])
    return out

def sp(bid, ask):
    m = (bid + ask) / 2.0
    return (ask - bid) / m * 10000.0 if m > 0 else 0.0

def bar_spread(b):
    """Spread moyen de la barre = moyenne des 2 instants observes (open, close)."""
    return (sp(b["bo"], b["ao"]) + sp(b["bc"], b["ac"])) / 2.0

def tstat(xs):
    xs = [x for x in xs if x == x]
    if len(xs) < 3:
        return 0.0
    m = st.mean(xs)
    sd = st.stdev(xs)
    return m / (sd / len(xs) ** 0.5) if sd > 0 else 0.0

def pct(xs, q):
    xs = sorted(x for x in xs if x == x)
    if not xs:
        return float("nan")
    i = min(len(xs) - 1, max(0, int(round(q * (len(xs) - 1)))))
    return xs[i]

def fridays(bars):
    """Regroupe par semaine ISO, garde les barres du vendredi, retourne (vendredi -> barres triees)."""
    by = defaultdict(list)
    for b in bars:
        if b["t"].weekday() == 4:
            y, w, _ = b["t"].isocalendar()
            by[(y, w)].append(b)
    for k in by:
        by[k].sort(key=lambda x: x["t"])
    return by

# ---------------------------------------------------------------- 0/1
CROSSES = ["GBP_CHF", "EUR_CHF", "AUD_CHF"]
data = {c: load(c, "H1") for c in CROSSES}
print("=" * 120)
print("64e — LE SPREAD DE SORTIE, MESURE (OANDA BA = bid+ask). Toutes les mesures anterieures etaient BID SEUL.")
print("=" * 120)
print("\n--- 0. FLUX ET ANCRE DE LA DERNIERE BARRE DU VENDREDI (controle DST) ---")
print(f"  {'SYMBOLE':10s} {'barres':>8s} {'debut':>12s} {'fin':>12s}")
for c in CROSSES:
    b = data[c]
    if not b:
        print(f"  {c:10s} ABSENT")
        continue
    print(f"  {c:10s} {len(b):8d} {b[0]['t'].date()} {b[-1]['t'].date()}")

for c in CROSSES:
    b = data[c]
    if not b:
        continue
    fr = fridays(b)
    hrs = defaultdict(lambda: defaultdict(int))
    for k, bs in fr.items():
        h = bs[-1]["t"].hour
        hrs[bs[-1]["t"].month][h] += 1
    print(f"  {c} heure UTC de la derniere barre du vendredi, par mois :")
    for m in sorted(hrs):
        tot = sum(hrs[m].values())
        top = sorted(hrs[m].items(), key=lambda x: -x[1])[:2]
        print(f"      M{m:02d} n={tot:4d} " + " ".join(f"h{h}:{n}({100*n/tot:.0f}%)" for h, n in top))

print("\n--- 1. PROFIL DU SPREAD (bp du mid) PAR HEURE UTC, VENDREDIS SEULEMENT ---")
print("  Le spread de la DERNIERE barre est-il le plus large de la journee ?")
for c in CROSSES:
    b = data[c]
    if not b:
        continue
    fr = fridays(b)
    byh = defaultdict(list)
    byh_last = []
    for k, bs in fr.items():
        for x in bs[:-1]:
            byh[x["t"].hour].append(bar_spread(x))
        byh_last.append((bs[-1]["t"].hour, bar_spread(bs[-1])))
    allsp = [bar_spread(x) for bs in fr.values() for x in bs[:-1]]
    print(f"  {c} — spread moyen/median des barres NON-finales : {st.mean(allsp):.3f} / {st.median(allsp):.3f}")
    line = []
    for h in sorted(byh):
        v = byh[h]
        line.append(f"h{h:02d}:{st.mean(v):.2f}/{st.median(v):.2f}(n{len(v)})")
    print("      " + "  ".join(line))
    for h in sorted(set(x[0] for x in byh_last)):
        v = [x[1] for x in byh_last if x[0] == h]
        print(f"      DERNIERE barre h{h:02d} : mean {st.mean(v):.3f} median {st.median(v):.3f} n={len(v)}")

# ---------------------------------------------------------------- 2
print("\n--- 2. PAR VENDREDI : fade BID 13:00 -> cloture, spread entree/tenu/sortie, NET = fade - spread_sortie ---")
rows = {}
for c in CROSSES:
    b = data[c]
    if not b:
        continue
    idx = {x["t"]: i for i, x in enumerate(b)}
    fr = fridays(b)
    recs = []
    for k in sorted(fr):
        bs = fr[k]
        close_bar = bs[-1]
        # barre d'entree = 13:00 UTC du meme vendredi
        ent = next((x for x in bs if x["t"].hour == 13), None)
        if ent is None:
            continue
        i0, i1 = idx[ent["t"]], idx[close_bar["t"]]
        if i1 <= i0:
            continue
        held = b[i0:i1 + 1]
        fade = (ent["bo"] - close_bar["bc"]) / ent["bo"] * 10000.0      # SELL, barres BID
        fade_mid = (((ent["bo"] + ent["ao"]) / 2) - ((close_bar["bc"] + close_bar["ac"]) / 2)) \
            / ((ent["bo"] + ent["ao"]) / 2) * 10000.0
        s_ent = sp(ent["bo"], ent["ao"])
        s_exit = sp(close_bar["bc"], close_bar["ac"])
        s_held = st.mean(bar_spread(x) for x in held)
        recs.append(dict(d=ent["t"].date(), year=ent["t"].year, fade=fade, fade_mid=fade_mid,
                         s_ent=s_ent, s_exit=s_exit, s_held=s_held,
                         net=fade - s_exit, net_mid=fade_mid - (s_ent + s_exit) / 2))
    rows[c] = recs
    if not recs:
        continue
    nets = [r["net"] for r in recs]
    fades = [r["fade"] for r in recs]
    print(f"  {c}  n={len(recs)} vendredis ({recs[0]['d']} -> {recs[-1]['d']})")
    print(f"      fade BID        mean {st.mean(fades):+.3f} bp  median {st.median(fades):+.3f}  t {tstat(fades):+.2f}")
    print(f"      spread entree   mean {st.mean(r['s_ent'] for r in recs):.3f}  median {st.median([r['s_ent'] for r in recs]):.3f}")
    print(f"      spread tenu     mean {st.mean(r['s_held'] for r in recs):.3f}  median {st.median([r['s_held'] for r in recs]):.3f}")
    print(f"      spread SORTIE   mean {st.mean(r['s_exit'] for r in recs):.3f}  median {st.median([r['s_exit'] for r in recs]):.3f}"
          f"  p90 {pct([r['s_exit'] for r in recs], .90):.2f}")
    print(f"      NET (bid-strexit) mean {st.mean(nets):+.3f} bp  median {st.median(nets):+.3f}  t {tstat(nets):+.2f}"
          f"  hit {100*sum(1 for x in nets if x > 0)/len(nets):.0f}%")
    print(f"      NET (mid-sym)     mean {st.mean(r['net_mid'] for r in recs):+.3f} bp  t {tstat([r['net_mid'] for r in recs]):+.2f}")

# ---------------------------------------------------------------- 3
print("\n--- 3. ELARGISSEMENT A LA SORTIE : ratio spread_sortie / spread_tenu, par ere ---")
ERAS = [(2005, 2009), (2010, 2014), (2015, 2019), (2020, 2026)]
for c in CROSSES:
    recs = rows.get(c) or []
    if not recs:
        continue
    print(f"  {c}")
    print(f"      {'ERE':12s} {'n':>5s} {'ratio moy':>10s} {'med':>7s} {'>1.5x':>7s} {'>2x':>7s} {'>3x':>7s} {'net moy':>9s} {'net t':>7s}")
    for a, z in ERAS:
        sub = [r for r in recs if a <= r["year"] <= z]
        if len(sub) < 5:
            continue
        rat = [r["s_exit"] / r["s_held"] for r in sub if r["s_held"] > 0]
        nets = [r["net"] for r in sub]
        print(f"      {a}-{z} {len(sub):5d} {st.mean(rat):10.2f} {st.median(rat):7.2f} "
              f"{100*sum(1 for x in rat if x>1.5)/len(rat):6.0f}% "
              f"{100*sum(1 for x in rat if x>2)/len(rat):6.0f}% "
              f"{100*sum(1 for x in rat if x>3)/len(rat):6.0f}% "
              f"{st.mean(nets):+9.3f} {tstat(nets):+7.2f}")
    rat = [r["s_exit"] / r["s_held"] for r in recs if r["s_held"] > 0]
    print(f"      {'FULL':12s} {len(recs):5d} {st.mean(rat):10.2f} {st.median(rat):7.2f} "
          f"{100*sum(1 for x in rat if x>1.5)/len(rat):6.0f}% "
          f"{100*sum(1 for x in rat if x>2)/len(rat):6.0f}% "
          f"{100*sum(1 for x in rat if x>3)/len(rat):6.0f}% "
          f"{st.mean([r['net'] for r in recs]):+9.3f} {tstat([r['net'] for r in recs]):+7.2f}")

# ---------------------------------------------------------------- 4
print("\n--- 4. IS / OOS DU NET AVEC LE SPREAD MESURE (IS 2006-2015, OOS 2016-2026) + decennies ---")
for c in CROSSES:
    recs = rows.get(c) or []
    if not recs:
        continue
    def blk(sub, lbl):
        if len(sub) < 5:
            return f"      {lbl:10s} n<5"
        nets = [r["net"] for r in sub]
        return (f"      {lbl:10s} n={len(sub):4d} net {st.mean(nets):+7.3f} bp  t {tstat(nets):+6.2f}  "
                f"med {st.median(nets):+7.3f}  hit {100*sum(1 for x in nets if x>0)/len(nets):3.0f}%")
    print(f"  {c}")
    print(blk([r for r in recs if r["year"] <= 2015], "IS 06-15"))
    print(blk([r for r in recs if r["year"] >= 2016], "OOS 16-26"))
    for a, z in ERAS:
        print(blk([r for r in recs if a <= r["year"] <= z], f"{a}-{z}"))
    print(blk(recs, "FULL"))

# ---------------------------------------------------------------- 5
print("\n--- 5. M5 : OU le spread s'elargit dans la derniere heure (sortie H-10 vs cloture) ---")
for c in ["GBP_CHF", "AUD_CHF"]:
    m5 = load(c, "M5")
    if not m5:
        print(f"  {c} M5 : absent")
        continue
    idx = {x["t"]: i for i, x in enumerate(m5)}
    fr = fridays(m5)
    buckets = [(-60, -45), (-45, -30), (-30, -20), (-20, -10), (-10, -5), (-5, 0)]
    acc = defaultdict(list)
    nets = defaultdict(list)
    nfri = 0
    for k in sorted(fr):
        bs = fr[k]
        close = bs[-1]
        # barre d'entree 13:00 UTC
        ent = next((x for x in bs if x["t"].hour == 13 and x["t"].minute == 0), None)
        if ent is None:
            continue
        t_close_end = close["t"] + timedelta(minutes=5)
        fringe = [x for x in bs if x["t"] >= t_close_end - timedelta(minutes=60) and x["t"] < t_close_end]
        if len(fringe) < 10:
            continue
        nfri += 1
        for lo, hi in buckets:
            a = t_close_end + timedelta(minutes=lo)
            z = t_close_end + timedelta(minutes=hi)
            sel = [x for x in fringe if a <= x["t"] < z]
            if sel:
                acc[(lo, hi)].append(st.mean(bar_spread(x) for x in sel))
        # net si sortie a H-|lo| minutes avant la fin
        for lo, hi in [(-60, -60)] + buckets:
            a = t_close_end + timedelta(minutes=lo)
            cand = [x for x in fringe if x["t"] < a]
            if not cand:
                continue
            ex = cand[-1]
            fade = (ent["bo"] - ex["bc"]) / ent["bo"] * 10000.0
            nets[(lo, hi)].append(fade - sp(ex["bc"], ex["ac"]))
    print(f"  {c} — {nfri} vendredis M5")
    print("      spread moyen par tranche avant la cloture :")
    for lo, hi in buckets:
        v = acc.get((lo, hi))
        if v:
            print(f"        [{lo:+3d},{hi:+3d}) min  mean {st.mean(v):6.3f}  median {st.median(v):6.3f}  n={len(v)}")
    print("      NET si sortie a H-x (fade bid depuis 13:00 - spread de la barre de sortie) :")
    for lo, hi in [(-60, -60)] + buckets:
        v = nets.get((lo, hi))
        if v:
            print(f"        sortie <= {hi:+3d} min  net {st.mean(v):+7.3f} bp  t {tstat(v):+6.2f}  n={len(v)}")
    print()
