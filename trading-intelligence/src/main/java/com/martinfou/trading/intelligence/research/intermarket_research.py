#!/usr/bin/env python3
"""Intermarket / cross-asset research v2 — Simons jeudi 2026-08-06.

v2 : alignement jour-à-jour SYSTÉMATIQUE (intersection des jours communs).
Toutes les corrélations sont calculées sur les rendements quotidiens alignés.
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
                day = ts // 86400000
                closes[day] = close
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
    print("=== Chargement ===")
    closes = {}
    for p in PAIRS:
        closes[p] = load_pair(p)
        print(f"  {p}: {len(closes[p])} jours")

    # Jour commun à TOUTES les paires
    common = set(closes[PAIRS[0]].keys())
    for p in PAIRS[1:]:
        common &= set(closes[p].keys())
    common = np.array(sorted(common))
    print(f"  Jours communs à toutes les paires: {len(common)}")

    # Matrice de prix alignée + rendements
    price = np.zeros((len(common), len(PAIRS)))
    for j, p in enumerate(PAIRS):
        for i, d in enumerate(common):
            price[i, j] = closes[p][d]
    ret = np.diff(np.log(price), axis=0)  # (N-1, 8)
    print(f"  Matrice rendements: {ret.shape}")

    # Proxies construits : AUD_JPY = AUD_USD * (1/USD_JPY), GBP_JPY = GBP_USD/USD_JPY
    idx = {p: j for j, p in enumerate(PAIRS)}
    audjpy = np.log(price[:, idx["AUD_USD"]]) - np.log(price[:, idx["USD_JPY"]])
    gbpjpy = np.log(price[:, idx["GBP_USD"]]) - np.log(price[:, idx["USD_JPY"]])
    r_audjpy = np.diff(audjpy)
    r_gbpjpy = np.diff(gbpjpy)

    names = PAIRS + ["AUD_JPY", "GBP_JPY"]
    R = {n: ret[:, idx[n]] for n in PAIRS}
    R["AUD_JPY"] = r_audjpy
    R["GBP_JPY"] = r_gbpjpy

    # ---- 1. Corrélations contemporaines (alignées) ----
    print("\n=== 1. Corrélations contemporaines quotidiennes (alignées) ===")
    tgt = ["NZD_USD", "AUD_USD", "EUR_USD", "USD_CHF", "USD_JPY", "USD_CAD", "GBP_USD"]
    sig = ["AUD_JPY", "GBP_JPY", "USD_CHF", "EUR_USD", "USD_CAD"]
    hdr = "signal".ljust(10) + "".join(t.ljust(11) for t in tgt)
    print("Full 2006-2026")
    print(hdr)
    for s in sig:
        row = s.ljust(10)
        for t in tgt:
            row += (f"{corr(R[s], R[t]):+.2f}" if s != t else "  —").ljust(11)
        print(row)

    # par décennie (alignée)
    print("\nPar décennie : 2006-2015 (moitié 1) | 2016-2026 (moitié 2)")
    half = len(common) // 2
    for s in ["AUD_JPY", "GBP_JPY", "USD_CHF"]:
        row = s.ljust(10)
        for t in tgt:
            if s == t:
                row += "  —".ljust(11)
                continue
            c1 = corr(R[s][:half], R[t][:half])
            c2 = corr(R[s][half:], R[t][half:])
            row += (f"{c1:+.2f}/{c2:+.2f}").ljust(11)
        print(row)

    # ---- 2. Lead-lag aligné : momentum 5j du proxy -> rendement futur cible ----
    print("\n=== 2. Lead-lag : corr(momentum5j_signal_t, rendement_cible_{t+k}) ===")
    def mom5(x):
        return np.convolve(x, np.ones(5), "valid")

    def futk(x, k):
        # rendement de x sur k jours commençant à l'index i (i+k fin)
        return np.array([x[i:i+k].sum() for i in range(k, len(x) + 1 - 0)])[:len(x) - k + 1] if False else np.array([x[i:i+k].sum() for i in range(len(x) - k + 1)])

    for s in ["AUD_JPY", "GBP_JPY", "USD_CHF"]:
        m = mom5(R[s])  # len N-5
        for t in ["NZD_USD", "AUD_USD", "EUR_USD", "USD_CAD"]:
            rt = R[t]
            outs = []
            for k in [1, 2, 3, 5]:
                # fut = rendement de rt sur k jours, la fenêtre [i+1 .. i+k] suit le momentum finissant en i
                fut = np.array([rt[i+1:i+1+k].sum() for i in range(len(m) - k + 1)])
                mm = m[:len(fut)]
                outs.append(f"k={k}:{corr(mm, fut):+.3f}")
            print(f"  {s} -> {t}: " + "  ".join(outs))

    # ---- 3. Test directionnel simple (aligné) : signal 5j -> 5j futur ----
    print("\n=== 3. Test directionnel : signal 5j -> cible 5j future ===")
    for s, t in [("AUD_JPY", "NZD_USD"), ("AUD_JPY", "AUD_USD"), ("GBP_JPY", "EUR_USD"),
                 ("GBP_JPY", "GBP_USD"), ("USD_CHF", "EUR_USD")]:
        m = mom5(R[s])
        rt = R[t]
        # rendement futur sur 5 jours après le point i (i = fin du momentum)
        fut = np.array([rt[i+1:i+6].sum() for i in range(len(m) - 5)])
        mm = m[:len(fut)]
        sign_fut = np.sign(fut)
        if t == "EUR_USD" and s == "USD_CHF":
            # miroir : CHF up -> EUR down
            pred = -np.sign(mm)
        else:
            pred = np.sign(mm)
        hit = (pred == sign_fut).mean()
        avg_pos = fut[pred > 0].mean() if (pred > 0).any() else 0
        avg_neg = fut[pred < 0].mean() if (pred < 0).any() else 0
        n_pos = (pred > 0).sum(); n_neg = (pred < 0).sum()
        # expectancy en pips approx (NZD/EUR ~ 0.0001, GBP ~ 0.0001)
        print(f"  {s} -> {t}: hit {hit*100:5.1f}% | n_pos {n_pos} avg {avg_pos*100:+.3f}% | n_neg {n_neg} avg {avg_neg*100:+.3f}% | spread {100*(avg_pos-avg_neg):+.3f}%")

    # ---- 4. EUR/USD vs USD/CHF miroir - qui mène ? (aligné) ----
    print("\n=== 4. EUR/USD vs USD/CHF (aligné) ===")
    rs = R["EUR_USD"]; rt = R["USD_CHF"]
    print(f"  Contemporaine: {corr(rs, rt):+.3f}")
    for k in [1, 2, 3, 5]:
        c_lead_eur = corr(rs[:-k], rt[k:])
        c_lead_chf = corr(rt[:-k], rs[k:])
        print(f"  k={k}: corr(EUR_t, CHF_{{t+{k}}})={c_lead_eur:+.3f} | corr(CHF_t, EUR_{{t+{k}}})={c_lead_chf:+.3f}")

    # ---- 5. Corrélation EUR/USD vs indices implicites ----
    print("\n=== 5. Le bloc EUR (EUR/USD, EUR/CHF implicite) ===")
    # EUR/CHF implicite = EUR/USD * USD/CHF
    eurchf = np.log(price[:, idx["EUR_USD"]]) + np.log(price[:, idx["USD_CHF"]])
    r_eurchf = np.diff(eurchf)
    print(f"  EUR/CHF (implicite) vs EUR/USD: {corr(r_eurchf, rs):+.3f}")
    print(f"  EUR/CHF (implicite) vs USD/CHF: {corr(r_eurchf, rt):+.3f}")
    print(f"  EUR/CHF (implicite) vs AUD_JPY: {corr(r_eurchf, r_audjpy):+.3f}")


if __name__ == "__main__":
    main()
