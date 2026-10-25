#!/usr/bin/env python3
"""
64e (suite) — COMPARAISON DES FENETRES sur M5 BA (2020-2026, crosses refuge).

La config qui se deploie demain (D65, classe FridayFadeAudChf) calcule
    close = 16:00 America/New_York, entry = close-60, exit = close-10
or la CLOTURE REELLE du FX est 17:00 ET (la derniere barre H1 du vendredi est
[16,17) ET : verifie sur la donnee). Le code a donc une heure d'avance.

Fenetres comparees, exprimees en minutes relatives a la VRAIE cloture (17:00 ET) :
  CODEE   : entree -120, sortie -70
  VISEE   : entree  -60, sortie -10   (l'intention de D65)
  HEURE   : entree  -60, sortie   0   (toute la derniere heure)
  D57     : entree 13:00 UTC, sortie  0

net = fade_bid - spread_plein_a_la_sortie   (identite : short paye le spread de sortie)
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
    for r in csv.DictReader(open(p)):
        try:
            t = datetime.strptime(r["time"][:19] + "Z", FMT).replace(tzinfo=timezone.utc)
            bo, bc = float(r["bid_o"]), float(r["bid_c"])
            ao, ac = float(r["ask_o"]), float(r["ask_c"])
        except Exception:
            continue
        if bo <= 0 or ao < bo:
            continue
        out.append(dict(t=t, bo=bo, bc=bc, ao=ao, ac=ac))
    out.sort(key=lambda x: x["t"])
    return out

def sp(b, a):
    m = (b + a) / 2
    return (a - b) / m * 10000.0

def tstat(xs):
    xs = [x for x in xs if x == x]
    if len(xs) < 3:
        return 0.0
    m, sd = st.mean(xs), st.stdev(xs)
    return m / (sd / len(xs) ** 0.5) if sd else 0.0

def fridays(bars):
    by = defaultdict(list)
    for b in bars:
        if b["t"].weekday() == 4:
            by[b["t"].isocalendar()[:2]].append(b)
    return {k: sorted(v, key=lambda x: x["t"]) for k, v in by.items()}

WINDOWS = [("CODEE (D65 tel quel)", -120, -70), ("VISEE (D65 intention)", -60, -10),
           ("HEURE COMPLETE", -60, 0)]

GRAN = sys.argv[1] if len(sys.argv) > 1 else "M5"
MINBARS = 60 if GRAN == "M5" else 20

for inst in ["GBP_CHF", "AUD_CHF", "EUR_CHF"]:
    m5 = load(inst, GRAN)
    if not m5:
        print(f"{inst}: pas de {GRAN}"); continue
    fr = fridays(m5)
    print("=" * 110)
    print(f"{inst} — M5 BA, {len(fr)} vendredis ({m5[0]['t'].date()} -> {m5[-1]['t'].date()})")
    print("=" * 110)
    res = defaultdict(lambda: defaultdict(list))
    for k, bs in fr.items():
        real = [x for x in bs]
        if len(real) < MINBARS:
            continue
        close_bar = real[-1]
        close_end = close_bar["t"] + timedelta(minutes=5)     # instant de la vraie cloture
        # entree 13:00 UTC (D57)
        ent_d57 = next((x for x in real if x["t"].hour == 13 and x["t"].minute == 0), None)
        for label, lo, hi in WINDOWS:
            t_in = close_end + timedelta(minutes=lo)
            t_out = close_end + timedelta(minutes=hi)
            cand_in = [x for x in real if x["t"] >= t_in and x["t"] < t_out]
            if not cand_in:
                continue
            ent, ex = cand_in[0], cand_in[-1]
            fade = (ent["bo"] - ex["bc"]) / ent["bo"] * 10000.0
            fade_mid = (((ent["bo"] + ent["ao"]) / 2) - ((ex["bc"] + ex["ac"]) / 2)) \
                / ((ent["bo"] + ent["ao"]) / 2) * 10000.0
            s_exit = sp(ex["bc"], ex["ac"])
            res[label]["net"].append(fade - s_exit)
            res[label]["fade"].append(fade)
            res[label]["fade_mid"].append(fade_mid)
            res[label]["s_exit"].append(s_exit)
            res[label]["s_in"].append(sp(ent["bo"], ent["ao"]))
            res[label]["year"].append(ent["t"].year)
            res[label]["n"].append(1)
        # D57 : 13:00 -> vraie cloture
        if ent_d57 is not None:
            fade = (ent_d57["bo"] - close_bar["bc"]) / ent_d57["bo"] * 10000.0
            s_exit = sp(close_bar["bc"], close_bar["ac"])
            res["D57 13:00->close"]["net"].append(fade - s_exit)
            res["D57 13:00->close"]["fade"].append(fade)
            res["D57 13:00->close"]["fade_mid"].append(
                (((ent_d57["bo"] + ent_d57["ao"]) / 2) - ((close_bar["bc"] + close_bar["ac"]) / 2))
                / ((ent_d57["bo"] + ent_d57["ao"]) / 2) * 10000.0)
            res["D57 13:00->close"]["s_exit"].append(s_exit)
            res["D57 13:00->close"]["s_in"].append(sp(ent_d57["bo"], ent_d57["ao"]))
            res["D57 13:00->close"]["year"].append(ent_d57["t"].year)
            res["D57 13:00->close"]["n"].append(1)

    print(f"  {'FENETRE':24s} {'n':>5s} {'fade bid':>9s} {'spread in':>10s} {'spread out':>11s} {'NET':>8s} {'t':>7s} {'hit':>5s}")
    for label in [w[0] for w in WINDOWS] + ["D57 13:00->close"]:
        d = res.get(label)
        if not d or not d["net"]:
            continue
        nets = d["net"]
        print(f"  {label:24s} {len(nets):5d} {st.mean(d['fade']):+9.3f} {st.mean(d['s_in']):10.3f} "
              f"{st.mean(d['s_exit']):11.3f} {st.mean(nets):+8.3f} {tstat(nets):+7.2f} "
              f"{100*sum(1 for x in nets if x>0)/len(nets):4.0f}%")
    print()
    # IS / OOS de chaque fenetre
    print(f"  {'FENETRE':24s} {'IS<=2022':>18s} {'OOS>=2023':>18s}   (net moyen bp, t)")
    for label in [w[0] for w in WINDOWS] + ["D57 13:00->close"]:
        d = res.get(label)
        if not d or not d["net"]:
            continue
        a = [x for x, y in zip(d["net"], d["year"]) if y <= 2022]
        b = [x for x, y in zip(d["net"], d["year"]) if y >= 2023]
        sa = f"{st.mean(a):+7.2f} t{tstat(a):+5.2f} n{len(a)}" if len(a) > 4 else "n/a"
        sb = f"{st.mean(b):+7.2f} t{tstat(b):+5.2f} n{len(b)}" if len(b) > 4 else "n/a"
        print(f"  {label:24s} {sa:>18s} {sb:>18s}")
    print()
    # profil du spread par minute relative a la cloture
    prof = defaultdict(list)
    for k, bs in fr.items():
        if len(bs) < MINBARS:
            continue
        ce = bs[-1]["t"] + timedelta(minutes=5)
        for x in bs:
            m = int((x["t"] - ce).total_seconds() // 60)
            if -125 <= m <= 3:
                prof[m].append(sp(x["bc"], x["ac"]))
    print("  PROFIL DU SPREAD par minute relative a la cloture (bp) :")
    line = []
    for m in sorted(prof):
        if m % 5 == 0 or m >= -12:
            v = prof[m]
            line.append(f"{m:+d}:{st.mean(v):.2f}(n{len(v)})")
    print("    " + "  ".join(line))
    print()
