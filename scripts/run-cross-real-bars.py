#!/usr/bin/env python3
"""54e-bis (samedi 10 oct 2026) : LE TEST DECISIF DU 53e.
Les crosses refuge (GBP_CHF, EUR_CHF, AUD_CHF) existent-ils VRAIMENT dans une serie coteе ?

Le 50e/53e mesuraient le fade du vendredi SUR UNE RECONSTRUCTION algebrique (produit de 2 jambes).
Ici : telechargement des VRAIES bougies H1 bid (OANDA practice, cle du conteneur live) puis mesure
du fade sur la serie coteе, avec la meme construction que la colonne A du 53e (derniere barre REELLE
du jour UTC) et la meme regle de retour (log-rendement en bp, barres de carry exclues).

Sorties : CSV dans data/historical/dukascopy/, .bars dans data/historical/bars/, rapport texte.
"""
import json
import os
import struct
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

HIST = "/home/martinfou/projects/trading-bridge/data/historical"
DUKA = os.path.join(HIST, "dukascopy")
BARS = os.path.join(HIST, "bars")
HOST = "https://api-fxpractice.oanda.com"
SYMS = ["GBP_CHF", "EUR_CHF", "AUD_CHF"]
# Fenetre comparable au pipeline (les 8 paires s'arretent au 19 mai 2026)
WIN_START = datetime(2006, 1, 1, tzinfo=timezone.utc)
WIN_END = datetime(2026, 5, 20, tzinfo=timezone.utc)
COST_LEG_BP = 2.14   # aller-retour d'une majeure (loi du 20 sept 2026)


def token():
    raw = subprocess.run(
        ["docker", "inspect", "trading-live", "--format", "{{range .Config.Env}}{{println .}}{{end}}"],
        capture_output=True, text=True, check=True).stdout
    for line in raw.splitlines():
        if line.startswith("OANDA_API_KEY="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("cle conteneur introuvable")


KEY = token()


def fetch_all(sym):
    """Pagination en arriere : 5000 bougies H1 bid par requete."""
    out = []
    end = None
    while True:
        url = f"{HOST}/v3/instruments/{sym}/candles?granularity=H1&count=5000&price=B&complete=true"
        if end:
            url += "&to=" + end
        req = urllib.request.Request(url, headers={"Authorization": "Bearer " + KEY})
        try:
            with urllib.request.urlopen(req, timeout=45) as r:
                d = json.load(r)
        except urllib.error.HTTPError as e:
            print(f"  [{sym}] arret HTTP {e.code} apres {len(out)} bougies", file=sys.stderr)
            break
        c = d.get("candles", [])
        if not c:
            break
        out = c + out
        end = c[0]["time"]
        if len(c) < 2:
            break
    return out


def tstat(xs):
    n = len(xs)
    if n < 3:
        return 0.0, 0.0
    m = sum(xs) / n
    var = sum((x - m) ** 2 for x in xs) / (n - 1)
    sd = var ** 0.5
    return m, (m / (sd / n ** 0.5) if sd > 0 else 0.0)


def median(xs):
    s = sorted(xs)
    n = len(s)
    return s[n // 2] if n % 2 else 0.5 * (s[n // 2 - 1] + s[n // 2])


def daily_returns(candles):
    """Retour par jour UTC : derniere barre REELLE (high>low) vs celle du jour precedent.
    Retourne une liste de (date, weekday, retour bp)."""
    byday = {}
    for c in candles:
        if not c.get("complete"):
            continue
        hi, lo, cl = float(c["bid"]["h"]), float(c["bid"]["l"]), float(c["bid"]["c"])
        if hi <= lo:                       # barre de carry/degenerate
            continue
        dt = datetime.fromisoformat(c["time"].replace("Z", "+00:00"))
        d = dt.date()
        if d not in byday or dt > byday[d][0]:
            byday[d] = (dt, cl)
    days = sorted(byday)
    out = []
    for i in range(1, len(days)):
        d0, d1 = days[i - 1], days[i]
        (_, c0), (dt1, c1) = byday[d0], byday[d1]
        import math
        r = math.log(c1 / c0) * 1e4
        out.append((d1, dt1.weekday(), r))
    return out


def main():
    report = []
    report.append("=" * 78)
    report.append("54e (hors serie, samedi) — LE FADE SUR LES VRAIES BARRES DES CROSSES REFUGE")
    report.append("Source : OANDA practice H1 bid (cle du conteneur live, compte -014)")
    report.append("Reference a confronter : 53e colonne A (reconstruction algebrique par produit des 2 jambes)")
    report.append("=" * 78)
    ref_a = {"GBP_CHF": (9.2396, None), "EUR_CHF": (7.0015, None), "AUD_CHF": (8.6001, None)}
    for sym in SYMS:
        candles = fetch_all(sym)
        if not candles:
            report.append(f"\n## {sym} : AUCUNE DONNEE")
            continue
        first = candles[0]["time"][:10]
        last = candles[-1]["time"][:10]
        n = len(candles)
        # ecriture CSV (meme convention que les autres paires)
        csv = os.path.join(DUKA, f"{sym.replace('_','').lower()}-h1-bid-oanda-{first}-{last}.csv")
        with open(csv, "w") as f:
            f.write("timestamp,open,high,low,close\n")
            for c in candles:
                if not c.get("complete"):
                    continue
                dt = datetime.fromisoformat(c["time"].replace("Z", "+00:00"))
                ms = int(dt.timestamp() * 1000)
                b = c["bid"]
                f.write(f"{ms},{b['o']},{b['h']},{b['l']},{b['c']}\n")
        # ecriture .bars (big-endian >qddddi, ms, volume=0) — yen ne pas ecraser les majors
        bars = os.path.join(BARS, f"{sym}_H1.bars")
        with open(bars, "wb") as f:
            for c in candles:
                if not c.get("complete"):
                    continue
                dt = datetime.fromisoformat(c["time"].replace("Z", "+00:00"))
                ms = int(dt.timestamp() * 1000)
                b = c["bid"]
                f.write(struct.pack(">qddddi", ms, float(b["o"]), float(b["h"]), float(b["l"]),
                                    float(b["c"]), 0))
        rets = daily_returns(candles)
        for (label, lo, hi) in [("FULL 2002-2026", datetime(2002, 1, 1, tzinfo=timezone.utc), WIN_END),
                                ("2006-2026 (pipeline)", WIN_START, WIN_END),
                                ("IS 2006-2015", WIN_START, datetime(2016, 1, 1, tzinfo=timezone.utc)),
                                ("OOS 2016-2026", datetime(2016, 1, 1, tzinfo=timezone.utc), WIN_END)]:
            sub = [r for (d, w, r) in rets if lo <= datetime(d.year, d.month, d.day, tzinfo=timezone.utc) < hi]
            fri = [-r for (d, w, r) in rets
                   if lo <= datetime(d.year, d.month, d.day, tzinfo=timezone.utc) < hi and w == 4]
            mj = [-r for (d, w, r) in rets
                  if lo <= datetime(d.year, d.month, d.day, tzinfo=timezone.utc) < hi and w < 4]
            mf, tf = tstat(fri)
            mm, tm = tstat(mj)
            report.append(
                f"  {label:22s} n_FRI={len(fri):5d} | FADE {mf:+8.4f} bp (t {tf:+5.2f}, med {median(fri):+8.4f})"
                f" | Mon-Jeu {mm:+7.4f} (t {tm:+5.2f})")
        # decennies
        dec = []
        for start in (2006, 2010, 2015, 2020):
            lo = datetime(start, 1, 1, tzinfo=timezone.utc)
            hi2 = datetime(start + (4 if start != 2020 else 7), 1, 1, tzinfo=timezone.utc)
            fri = [-r for (d, w, r) in rets if lo <= datetime(d.year, d.month, d.day, tzinfo=timezone.utc) < hi2 and w == 4]
            m, t = tstat(fri)
            dec.append(f"{start}s {m:+7.3f} (t {t:+5.2f}, n {len(fri)})")
        report.append(f"  decennies : " + " | ".join(dec))
        m6, t6 = tstat([-r for (d, w, r) in rets
                        if WIN_START <= datetime(d.year, d.month, d.day, tzinfo=timezone.utc) < WIN_END and w == 4])
        report.append(f"  REFERENCE 53e (colonne A, reconstruction) = {ref_a[sym][0]:+.4f} bp"
                      f" | MESURE REELLE = {m6:+.4f} bp (t {t6:+.2f})"
                      f" | ECART = {m6 - ref_a[sym][0]:+.4f} bp")
        report.append(f"  COUT (cross = 2 jambes, {2*COST_LEG_BP:.2f} bp A/R) : net {m6 - 2*COST_LEG_BP:+.4f} bp/evenement"
                      f" | {len([1 for (d,w,r) in rets if WIN_START <= datetime(d.year,d.month,d.day,tzinfo=timezone.utc) < WIN_END and w==4])} vendredis")
        report.append(f"  FICHIERS : {csv} | {bars} | bougies={n} ({first} -> {last})")
        report.append("")
    txt = "\n".join(report)
    print(txt)
    out = "/home/martinfou/projects/tb-research/reports/2026-10-10-cross-real-bars.txt"
    with open(out, "w") as f:
        f.write(txt + "\n")
    print("rapport ->", out)


if __name__ == "__main__":
    main()
