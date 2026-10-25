#!/usr/bin/env python3
"""55e (lundi 12 oct 2026) — MESURE DU COUT REEL DES CROSSES REFUGE.

Pourquoi : RealCostModel.halfSpread("GBP_CHF") LEVE une exception ("Repli = refus"),
donc aucun backtest coste de la famille refuge n'est possible aujourd'hui — alors que la
news lane trade GBP_CHF en live depuis le 11 oct. Il faut mesurer le spread chez le broker
(procedure FEE-AUDIT §2 : mediane bid/ask sur des bougies H1 price=BA) et l'ajouter au modele.

Controle de methode : re-mesure EUR_USD et GBP_JPY (valeurs FEE-AUDIT du 1er oct : 1.60 et
3.40 pip de spread median) — si l'ecart est faible, la mesure du jour est comparable.

Sortie : ~/.hermes/cache/scratch/cross-costs-20261012/MEASURED.md + JSON.
"""
import json
import os
import subprocess
import statistics
import urllib.error
import urllib.request

HOST = "https://api-fxpractice.oanda.com"
OUT = os.path.expanduser("~/.hermes/cache/scratch/cross-costs-20261012")
os.makedirs(OUT, exist_ok=True)

TARGETS = ["GBP_CHF", "EUR_CHF", "AUD_CHF"]
CONTROLS = ["EUR_USD", "GBP_JPY", "GBP_USD", "USD_CAD"]


def token():
    raw = subprocess.run(
        ["docker", "inspect", "trading-live", "--format",
         "{{range .Config.Env}}{{println .}}{{end}}"],
        capture_output=True, text=True, check=True).stdout
    key = acct = None
    for line in raw.splitlines():
        if line.startswith("OANDA_API_KEY="):
            key = line.split("=", 1)[1].strip()
        if line.startswith("OANDA_ACCOUNT_ID="):
            acct = line.split("=", 1)[1].strip()
    if not key or not acct:
        raise SystemExit("cle/compte conteneur introuvables")
    return key, acct


KEY, ACCT = token()


def get(url):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + KEY})
    with urllib.request.urlopen(req, timeout=45) as r:
        return json.load(r)


def spreads(sym, count=1000):
    d = get(f"{HOST}/v3/instruments/{sym}/candles?granularity=H1&count={count}"
            "&price=BA&complete=true")
    mids, sp = [], []
    for c in d.get("candles", []):
        b, a = float(c["bid"]["c"]), float(c["ask"]["c"])
        mids.append((b + a) / 2.0)
        sp.append(a - b)
    if not sp:
        return None
    sp_sorted = sorted(sp)
    return {
        "n": len(sp),
        "median_spread": statistics.median(sp),
        "p25": sp_sorted[len(sp_sorted) // 4],
        "p75": sp_sorted[3 * len(sp_sorted) // 4],
        "mean_spread": statistics.fmean(sp),
        "first": d["candles"][0]["time"],
        "last": d["candles"][-1]["time"],
        "mid": statistics.median(mids),
    }


def instrument(sym):
    d = get(f"{HOST}/v3/accounts/{ACCT}/instruments?instruments={sym}")
    ins = d["instruments"][0]
    f = ins.get("financing", {})
    return {
        "displayPrecision": ins.get("displayPrecision"),
        "pipLocation": ins.get("pipLocation"),
        "longRate": f.get("longRate"),
        "shortRate": f.get("shortRate"),
        "marginRate": ins.get("marginRate"),
    }


out = {"targets": {}, "controls": {}}
for sym in TARGETS + CONTROLS:
    key = "targets" if sym in TARGETS else "controls"
    try:
        s = spreads(sym)
        i = instrument(sym)
    except Exception as e:  # noqa: BLE001
        print(f"[{sym}] ECHEC {type(e).__name__}: {e}")
        continue
    pip = 10 ** int(i["pipLocation"])
    s["pipSize"] = pip
    s["spread_pips"] = s["median_spread"] / pip
    s["half_spread_price"] = s["median_spread"] / 2.0
    s["half_spread_bp"] = (s["median_spread"] / 2.0) / s["mid"] * 10_000
    s["rt_cost_bp"] = s["median_spread"] / s["mid"] * 10_000
    s.update(i)
    out[key][sym] = s
    print(f"{sym:9s} spread med {s['spread_pips']:.2f} pip  "
          f"({s['median_spread']:.6f})  mid {s['mid']:.5f}  "
          f"half {s['half_spread_price']:.6f} = {s['half_spread_bp']:.3f} bp/leg  "
          f"A/R {s['rt_cost_bp']:.3f} bp | long {s['longRate']} short {s['shortRate']} "
          f"| n={s['n']} {s['first']}->{s['last']}")

with open(os.path.join(OUT, "measured.json"), "w") as fh:
    json.dump(out, fh, indent=2)

with open(os.path.join(OUT, "MEASURED.md"), "w") as fh:
    fh.write("# Cout reel des crosses refuge — mesure OANDA practice (12 oct 2026)\n\n")
    fh.write("Procedure : mediane bid/ask sur 1000 bougies H1 `price=BA` (FEE-AUDIT §2).\n\n")
    fh.write("| Symbole | spread med (pip) | half/leg (prix) | half/leg (bp) | A/R (bp) | "
             "longRate | shortRate |\n|---|---|---|---|---|---|---|\n")
    for key in ("targets", "controls"):
        for sym, s in out[key].items():
            fh.write(f"| {sym} | {s['spread_pips']:.2f} | {s['half_spread_price']:.6f} | "
                     f"{s['half_spread_bp']:.3f} | {s['rt_cost_bp']:.3f} | {s['longRate']} | "
                     f"{s['shortRate']} |\n")
    fh.write("\n## Ligne Java a ajouter a RealCostModel.HALF_SPREAD_PRICE\n\n```java\n")
    for sym, s in out["targets"].items():
        fh.write(f'"{sym}", {s["half_spread_price"]},\n')
    fh.write("```\n")
print("OK ->", OUT)
