#!/usr/bin/env python3
"""Intermarket conditional extreme test — Simons 2026-08-06.
Conditionne le lead-lag hebdo sur les mouvements extrêmes du signal (|mom| > 1σ / 2σ).
Question : le risque extrême (risk-off violent) a-t-il un pouvoir prédictif que le
mouvement moyen n'a pas ?
"""
import numpy as np
import csv, os, glob

DATA_DIR = "data/historical/dukascopy"
PAIRS = ["EUR_USD", "GBP_USD", "USD_JPY", "USD_CAD", "AUD_USD", "NZD_USD", "USD_CHF"]
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
                    closes[int(row[0]) // 86400000] = float(row[4])
                except ValueError:
                    continue
    return closes


def main():
    closes = {p: load_pair(p) for p in PAIRS}
    common = set(closes[PAIRS[0]].keys())
    for p in PAIRS[1:]:
        common &= set(closes[p].keys())
    common = np.array(sorted(common))
    price = np.zeros((len(common), len(PAIRS)))
    for j, p in enumerate(PAIRS):
        for i, d in enumerate(common):
            price[i, j] = closes[p][d]
    idx = {p: j for j, p in enumerate(PAIRS)}
    logp = np.log(price)
    ret = np.diff(logp, axis=0)
    audjpy = logp[:, idx["AUD_USD"]] - logp[:, idx["USD_JPY"]]
    r_audjpy = np.diff(audjpy)
    R = {p: ret[:, idx[p]] for p in PAIRS}
    R["AUD_JPY"] = r_audjpy

    print("=== Test conditionnel : |momentum hebdo| > seuil ===")
    for s, t in [("AUD_JPY", "NZD_USD"), ("AUD_JPY", "EUR_USD"), ("AUD_JPY", "USD_CAD")]:
        rs = R[s]; rt = R[t]
        n = min(len(rs), len(rt))
        rs = rs[-n:]; rt = rt[-n:]
        nb = n // 5
        sig = np.array([rs[5*i:5*i+5].sum() for i in range(nb - 1)])
        fut = np.array([rt[5*i+5:5*i+10].sum() for i in range(nb - 1)])
        sd = sig.std()
        print(f"\n  {s} -> {t}: sig std={sd*100:.2f}%/sem")
        for thr in [0.0, 0.5, 1.0, 1.5, 2.0]:
            m = np.abs(sig) > thr * sd
            if m.sum() < 20:
                print(f"    |mom|>{thr:.1f}σ : n={m.sum()} (trop peu)")
                continue
            hit = (np.sign(sig[m]) == np.sign(fut[m])).mean()
            # expectancy directionnelle
            avg = (np.sign(sig[m]) * fut[m]).mean()
            print(f"    |mom|>{thr:.1f}σ : n={m.sum()} hit {hit*100:5.1f}% avg_dir {avg*100:+.3f}%")


if __name__ == "__main__":
    main()
