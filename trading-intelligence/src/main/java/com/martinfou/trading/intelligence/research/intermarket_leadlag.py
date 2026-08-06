#!/usr/bin/env python3
"""Intermarket lead-lag v3 — NON-OVERLAPPING weekly test. Simons 2026-08-06.

Le v2 a montré des hit rates gonflés par fenêtres chevauchantes. Ici :
1. Lead-lag JOURNALIER propre : corr(r_s[t], r_t[t+k]), k=1..5 (aucun chevauchement).
2. Test HEBDOMADAIRE non-chevauchant : momentum 5j du signal (semaine S) ->
   rendement 5j suivant de la cible (semaine S+1). Chaque paire (semaine, semaine+1)
   est indépendante. Sample ~1330 semaines par paire.
"""
import numpy as np
import csv, os, glob

DATA_DIR = "data/historical/dukascopy"
PAIRS = ["EUR_USD", "GBP_USD", "USD_JPY", "GBP_JPY", "USD_CAD", "AUD_USD", "NZD_USD", "USD_CHF"]
FILE_PATTERN = {p: p.lower().replace("_", "") + "-h1-bid-*.csv" for p in PAIRS}


def load_pair(pair):
    files = sorted(glob.glob(os.path.join(DATA_DIR, FILE_PATTERN[pair])))
    closes = {}
    for f in files:
        with open(f) as fh:
            r = csv.reader(fh)
            next(r, None)
            for row in r:
                if len(row) < 5:
                    continue
                try:
                    ts = int(row[0]); close = float(row[4])
                except ValueError:
                    continue
                closes[ts // 86400000] = close
    return closes


def corr(a, b):
    if len(a) != len(b) or len(a) < 30:
        return np.nan
    a = a - a.mean(); b = b - b.mean()
    denom = np.sqrt((a * a).sum() * (b * b).sum())
    if denom == 0:
        return np.nan
    return float((a * b).sum() / denom)


def main():
    closes = {p: load_pair(p) for p in PAIRS}
    common = set(closes[PAIRS[0]].keys())
    for p in PAIRS[1:]:
        common &= set(closes[p].keys())
    common = np.array(sorted(common))
    print(f"Jours communs: {len(common)}")

    price = np.zeros((len(common), len(PAIRS)))
    for j, p in enumerate(PAIRS):
        for i, d in enumerate(common):
            price[i, j] = closes[p][d]
    idx = {p: j for j, p in enumerate(PAIRS)}
    logp = np.log(price)
    ret = np.diff(logp, axis=0)

    audjpy = logp[:, idx["AUD_USD"]] - logp[:, idx["USD_JPY"]]
    gbpjpy = logp[:, idx["GBP_USD"]] - logp[:, idx["USD_JPY"]]
    r_audjpy = np.diff(audjpy)
    r_gbpjpy = np.diff(gbpjpy)
    R = {p: ret[:, idx[p]] for p in PAIRS}
    R["AUD_JPY"] = r_audjpy
    R["GBP_JPY"] = r_gbpjpy

    # ---- 1. Lead-lag journalier propre ----
    print("\n=== 1. Lead-lag journalier corr(r_s[t], r_t[t+k]) ===")
    tests = [("AUD_JPY", "NZD_USD"), ("AUD_JPY", "EUR_USD"), ("GBP_JPY", "EUR_USD"),
             ("USD_CHF", "EUR_USD"), ("EUR_USD", "GBP_USD"), ("AUD_JPY", "USD_CAD")]
    for s, t in tests:
        rs = R[s]; rt = R[t]
        n = min(len(rs), len(rt))
        rs = rs[-n:]; rt = rt[-n:]
        c0 = corr(rs, rt)
        outs = [f"k0:{c0:+.3f}"]
        for k in [1, 2, 3, 5]:
            c = corr(rs[:-k], rt[k:])
            outs.append(f"k{k}:{c:+.3f}")
        print(f"  {s} -> {t}: " + "  ".join(outs))

    # ---- 2. Test hebdomadaire non-chevauchant ----
    print("\n=== 2. Hebdo non-chevauchant : momentum 5j (sem S) -> 5j suivant (sem S+1) ===")
    print("  (chaque échantillon = une semaine indépendante)")
    for s, t, inverse in [("AUD_JPY", "NZD_USD", False), ("AUD_JPY", "EUR_USD", False),
                           ("GBP_JPY", "EUR_USD", False), ("GBP_JPY", "GBP_USD", False),
                           ("USD_CHF", "EUR_USD", True), ("EUR_USD", "GBP_USD", False)]:
        rs = R[s]; rt = R[t]
        n = min(len(rs), len(rt))
        rs = rs[-n:]; rt = rt[-n:]
        # buckets de 5 jours consécutifs, non-chevauchants
        nb = n // 5
        sig = np.array([rs[5*i:5*i+5].sum() for i in range(nb - 1)])
        fut = np.array([rt[5*i+5:5*i+10].sum() for i in range(nb - 1)])
        if inverse:
            pred = -np.sign(sig)
        else:
            pred = np.sign(sig)
        hit = (pred == np.sign(fut)).mean()
        # expectancy: rendement moyen conditionnel
        long_avg = fut[sig > 0].mean() if (sig > 0).any() else 0
        short_avg = fut[sig < 0].mean() if (sig < 0).any() else 0
        if inverse:
            # quand CHF monte (sig>0) on short EUR -> gain = -fut
            g = -fut
            avg_when_pos = g[sig > 0].mean() if (sig > 0).any() else 0
            avg_when_neg = g[sig < 0].mean() if (sig < 0).any() else 0
            spread = avg_when_pos - avg_when_neg
        else:
            avg_when_pos = fut[sig > 0].mean() if (sig > 0).any() else 0
            avg_when_neg = fut[sig < 0].mean() if (sig < 0).any() else 0
            spread = avg_when_pos - avg_when_neg
        n_pos = (sig > 0).sum(); n_neg = (sig < 0).sum()
        print(f"  {s}->{t} (inv={inverse}): n={len(sig)} pos={n_pos} neg={n_neg} | hit {hit*100:5.1f}% | "
              f"avg_pos {avg_when_pos*100:+.3f}% avg_neg {avg_when_neg*100:+.3f}% | spread {spread*100:+.3f}% | "
              f"corr {corr(sig, fut):+.3f}")

    # ---- 3. Régimes : le lead-lag AUD_JPY->NZD_USD tient-il par décennie ? ----
    print("\n=== 3. Stabilité décennale (hebdo non-chevauchant) ===")
    rs = R["AUD_JPY"]; rt = R["NZD_USD"]
    n = min(len(rs), len(rt))
    rs = rs[-n:]; rt = rt[-n:]
    nb = n // 5
    sig = np.array([rs[5*i:5*i+5].sum() for i in range(nb - 1)])
    fut = np.array([rt[5*i+5:5*i+10].sum() for i in range(nb - 1)])
    half = len(sig) // 2
    for label, lo, hi in [("2006-2015", 0, half), ("2016-2026", half, len(sig))]:
        s_ = sig[lo:hi]; f_ = fut[lo:hi]
        hit = (np.sign(s_) == np.sign(f_)).mean()
        spread = (f_[s_ > 0].mean() if (s_ > 0).any() else 0) - (f_[s_ < 0].mean() if (s_ < 0).any() else 0)
        print(f"  {label}: n={len(s_)} hit {hit*100:5.1f}% | spread {spread*100:+.3f}%")


if __name__ == "__main__":
    main()
