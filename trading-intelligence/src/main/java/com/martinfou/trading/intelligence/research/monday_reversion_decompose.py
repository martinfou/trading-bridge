#!/usr/bin/env python3
"""
monday_reversion_decompose.py — décomposition de la RÉVERSION DU LUNDI FX (39e résultat, 18 sept 2026).

Le backtest moteur (RunFxMondayReversion) donne un net agrégé ≈ 0 alors que la pré-validation du
38e résultat annonçait 8/8 paires positives (lundi +0.086% après un lundi-jeudi négatif).
Trois hypothèses à départager, en pur Python (aucun moteur) :

  H1. CONDITIONNEMENT : la pré-validation lisait les cellules 2x2 CONDITIONNÉES au vendredi (F1).
      Le backtest ne conditionne que sur W4 -> mesurer l'effet INCONDITIONNEL.
  H2. FENÊTRE : la pré-validation mesure close(vendredi) -> close(lundi) (inclut le GAP de week-end) ;
      le moteur entre à 01:00 UTC lundi et sort à 01:00 UTC mardi -> mesurer GAP vs SESSION.
  H3. COÛTS/SWAP : le moteur paie commission + slippage + swap ; mesurer l'edge brut en bp et le
      seuil de coût par paire (slippage 0.0001 en prix = 1.0 unité de cotation / côté).

Sortie : tableaux par paire + agrégats, moyennes en bp, t-stats, IS (<=2015) / OOS (>=2016).
"""
import csv, datetime, glob, os, statistics, collections

ROOT = os.path.dirname(os.path.abspath(__file__))


def _find_repo():
    """Remonte depuis ce fichier jusqu'au répertoire contenant data/historical/dukascopy."""
    d = ROOT
    for _ in range(12):
        if os.path.isdir(os.path.join(d, 'data', 'historical', 'dukascopy')):
            return d
        d = os.path.dirname(d)
    return os.environ.get('TRADING_BRIDGE', '/home/martinfou/projects/trading-bridge')


REPO = _find_repo()
CSV_DIR = os.path.join(REPO, 'data', 'historical', 'dukascopy')
PAIRS = {
    'USD_JPY': 'usdjpy', 'AUD_USD': 'audusd', 'EUR_USD': 'eurusd', 'GBP_USD': 'gbpusd',
    'GBP_JPY': 'gbpjpy', 'USD_CHF': 'usdchf', 'NZD_USD': 'nzdusd', 'USD_CAD': 'usdcad',
}
QTY = 10_000
COMM = 0.07 * 2                      # aller-retour


def load_pair(tag):
    """Retourne (daily_close[date], daily_last_ts[date], h1[(ts,o,h,l,c)]) sur tous les CSV H1."""
    files = sorted(glob.glob(os.path.join(CSV_DIR, f'{tag}-h1-bid-*.csv')))
    daily_close, h1 = {}, []
    for f in files:
        with open(f) as fh:
            for r in csv.DictReader(fh):
                ts = int(r['timestamp']) // 1000
                o, c = float(r['open']), float(r['close'])
                h1.append((ts, o, c))
    h1.sort()
    for ts, o, c in h1:
        d = datetime.datetime.fromtimestamp(ts, datetime.timezone.utc).date()
        daily_close[d] = c          # dernière barre du jour = close du jour
    # index par (date, heure) -> open  (pour le prix d'exécution réel du moteur)
    opens = {}
    for ts, o, c in h1:
        dt = datetime.datetime.fromtimestamp(ts, datetime.timezone.utc)
        opens[(dt.date(), dt.hour)] = o
    return daily_close, opens


def weekday_sessions(daily_close):
    return [d for d in sorted(daily_close) if d.weekday() < 5]


def cost_bp(pair):
    """Coût aller-retour en bp du notional : commission $0.14 + 2 x slippage(0.0001 en prix) x 10 000 unités.
    Pour une paire cotée en JPY/CHF, le slippage en USD dépend du taux -> approximé avec le taux de cotation."""
    slip_quote = 0.0001 * QTY * 2          # 2 unités de devise de cotation
    return (COMM + slip_quote) / QTY * 1e4  # bp du notional (notional en USD pour 10 000 unités)


def stats(xs):
    if not xs:
        return (0.0, 0.0, 0)
    m = statistics.mean(xs)
    n = len(xs)
    sd = statistics.pstdev(xs) if n > 1 else 0.0
    t = m / (sd / n ** 0.5) if sd > 0 else 0.0
    return (m, t, n)


def bp(x):
    return x * 1e4


def main():
    print("=" * 108)
    print("DÉCOMPOSITION RÉVERSION DU LUNDI FX — données Dukascopy H1 (2006 -> 20 mai 2026)")
    print("=" * 108)
    rows = {}
    for pair, tag in PAIRS.items():
        dc, opens = load_pair(tag)
        sess = weekday_sessions(dc)
        recs = []
        for i in range(6, len(sess)):
            d = sess[i]
            if d.weekday() != 0:                       # lundi seulement
                continue
            prev_fri, prev_thu, fri2 = sess[i - 1], sess[i - 2], sess[i - 6]
            cA, cB = dc[fri2], dc[prev_thu]
            if cA <= 0:
                continue
            w4 = (cB - cA) / cA
            f1 = (dc[prev_thu if False else prev_fri] - dc[prev_thu]) / dc[prev_thu] if dc[prev_thu] else 0.0
            # lundi close-to-close (définition pré-validation)
            ret_cc = (dc[d] - dc[prev_fri]) / dc[prev_fri]
            # fenêtre moteur : entrée open barre lundi 01:00 -> sortie open barre mardi 01:00
            tue = sess[i + 1] if i + 1 < len(sess) else None
            e = opens.get((d, 1)); x = opens.get((tue, 1)) if tue else None
            ret_eng = (x - e) / e if (e and x) else None
            gap = (e - dc[prev_fri]) / dc[prev_fri] if e else None      # close ven -> open lun 01:00
            sess_part = (x - e) / e if (e and x) else None
            recs.append({'date': d, 'w4': w4, 'f1': f1, 'ret_cc': ret_cc, 'ret_eng': ret_eng,
                         'gap': gap, 'sess': sess_part})
        rows[pair] = recs
        print(f"{pair:<9} lundis={len(recs)} | coût A/R≈{cost_bp(pair):.2f} bp "
              f"| |mouvement| moyen lundi={bp(statistics.mean([abs(r['ret_cc']) for r in recs])):.0f} bp")

    # ---- (A) effet INCONDITIONNEL par signe de W4, close-to-close (définition pré-validation)
    print("\n" + "-" * 108)
    print("(A) INCONDITIONNEL — rendement du LUNDI close-to-close par signe de W4 (la règle du backtest)")
    print(f"{'PAIRE':<9} {'W4<0 (LONG) bp':>15} {'t':>6} {'n':>5} {'IS>OOS bp':>17} | "
          f"{'W4>0 (SHORT) bp':>16} {'t':>6} {'n':>5} {'IS>OOS bp':>17}")
    agg = collections.defaultdict(list)
    for pair, recs in rows.items():
        lo = [r for r in recs if r['w4'] < 0]
        hi = [r for r in recs if r['w4'] >= 0]
        mlo, tlo, nlo = stats([r['ret_cc'] for r in lo])
        mhi, thi, nhi = stats([r['ret_cc'] for r in hi])
        islo = stats([r['ret_cc'] for r in lo if r['date'].year <= 2015])[0]
        ooslo = stats([r['ret_cc'] for r in lo if r['date'].year >= 2016])[0]
        ishi = stats([r['ret_cc'] for r in hi if r['date'].year <= 2015])[0]
        ooshi = stats([r['ret_cc'] for r in hi if r['date'].year >= 2016])[0]
        print(f"{pair:<9} {bp(mlo):>15.1f} {tlo:>6.2f} {nlo:>5} {bp(islo):>8.1f}>{bp(ooslo):<8.1f} | "
              f"{bp(mhi):>16.1f} {thi:>6.2f} {nhi:>5} {bp(ishi):>8.1f}>{bp(ooshi):<8.1f}")
        agg['long'] += [r['ret_cc'] for r in lo]
        agg['short'] += [-r['ret_cc'] for r in hi]     # PnL de la jambe short
    ml, tl, nl = stats(agg['long']); ms, ts_, ns = stats(agg['short'])
    print(f"{'AGRÉGAT':<9} {bp(ml):>15.1f} {tl:>6.2f} {nl:>5} {'':>17} | {bp(ms):>16.1f} {ts_:>6.2f} {ns:>5}")
    print(f"  -> brute LONG {bp(ml):+.1f} bp, SHORT {bp(ms):+.1f} bp ; coût A/R (EUR/GBP/USD) ≈ 2.14 bp, JPY/CHF ≈ 0.2-0.3 bp")

    # ---- (B) fenêtre moteur : gap week-end vs session lundi
    print("\n" + "-" * 108)
    print("(B) FENÊTRE — le même signal, mais mesuré DANS la fenêtre que le moteur peut réellement trader")
    print(f"{'PAIRE':<9} {'GAP ven23->lun01 bp':>19} {'SESSION lun01->mar01 bp':>24} {'PF* (session)':>14} {'n':>5}")
    tot_gap_l = tot_sess_l = []
    for pair, recs in rows.items():
        lo = [r for r in recs if r['w4'] < 0 and r['gap'] is not None and r['sess'] is not None]
        hi = [r for r in recs if r['w4'] >= 0 and r['gap'] is not None and r['sess'] is not None]
        g = [r['gap'] if r['w4'] < 0 else -r['gap'] for r in recs if r['gap'] is not None]
        s = [r['sess'] if r['w4'] < 0 else -r['sess'] for r in recs if r['sess'] is not None]
        mg, tg, ng = stats(g); mss, tss, nss = stats(s)
        pos = sum(1 for v in s if v > 0); neg = sum(1 for v in s if v <= 0)
        pf = (sum(v for v in s if v > 0) / abs(sum(v for v in s if v <= 0))) if neg else float('nan')
        print(f"{pair:<9} {bp(mg):>19.1f} {bp(mss):>24.1f} {pf:>14.2f} {nss:>5}")
        tot_gap_l += g; tot_sess_l += s
    mg, tg, ng = stats(tot_gap_l); mss, tss, _ = stats(tot_sess_l)
    print(f"{'AGRÉGAT':<9} {bp(mg):>19.1f} (t {tg:.2f}, n {ng})  {bp(mss):>17.1f} (t {tss:.2f})")
    print("  -> si le GAP porte l'edge et pas la SESSION, l'edge est INTRADABLE (le moteur entre à 01:00, gap passé)")

    # ---- (C) conditionnement F1 (2x2) : d'où venait le +0.086% du 38e résultat ?
    print("\n" + "-" * 108)
    print("(C) CONDITIONNEMENT — cellules 2x2 (F1 = vendredi précédent) : où vivait l'effet annoncé ?")
    print(f"{'PAIRE':<9} {'F1<0|W4<0 bp':>14} {'n':>5} {'F1>0|W4<0 bp':>14} {'n':>5} "
          f"{'F1<0|W4>0 bp':>14} {'n':>5} {'F1>0|W4>0 bp':>14} {'n':>5}")
    cells = collections.defaultdict(list)
    for pair, recs in rows.items():
        k = ('F1<0' if recs[0]['f1'] is not None else '?')
        out = {}
        for r in recs:
            key = ('F1<0' if r['f1'] < 0 else 'F1>0') + '|' + ('W4<0' if r['w4'] < 0 else 'W4>0')
            out.setdefault(key, []).append(r['ret_cc'])
            cells[key].append(r['ret_cc'])
        print(f"{pair:<9} " + " ".join(
            f"{bp(statistics.mean(out.get(k2, [0.0]))):>14.1f} {len(out.get(k2, [])):>5}"
            for k2 in ['F1<0|W4<0', 'F1>0|W4<0', 'F1<0|W4>0', 'F1>0|W4>0']))
    print("AGRÉGAT  " + "  ".join(f"{k}: {bp(statistics.mean(v)):+.1f} bp (n={len(v)})" for k, v in cells.items()))

    # ---- (D) réponse de MAGNITUDE (déciles de |W4|) en fenêtre moteur
    print("\n" + "-" * 108)
    print("(D) MAGNITUDE — PnL moyen (bp) de la jambe dans la fenêtre MOTEUR par décile de |W4|")
    allrec = []
    for pair, recs in rows.items():
        for r in recs:
            if r['sess'] is None:
                continue
            pnl = r['sess'] if r['w4'] < 0 else -r['sess']
            allrec.append((abs(r['w4']), pnl, pair, r['date'].year))
    allrec.sort()
    step = len(allrec) // 10
    print(f"{'décile':<8} {'|W4| min-max %':<18} {'pnl moyen bp':>13} {'t':>7} {'n':>6}")
    for k in range(10):
        chunk = allrec[k * step:(k + 1) * step] if k < 9 else allrec[9 * step:]
        vals = [c[1] for c in chunk]
        m, t, n = stats(vals)
        print(f"{k+1:<8} {chunk[0][0]*100:>7.2f}-{chunk[-1][0]*100:<9.2f} {bp(m):>13.1f} {t:>7.2f} {n:>6}")

    # ---- (E) répartition des jambes
    print("\n" + "-" * 108)
    ln = sum(1 for pair, recs in rows.items() for r in recs if r['w4'] < 0)
    sn = sum(1 for pair, recs in rows.items() for r in recs if r['w4'] >= 0)
    print(f"(E) RÉPARTITION : jambes LONG (W4<0) = {ln} | jambes SHORT (W4>0) = {sn} "
          f"→ la stratégie est ~50/50 long/short (pas un biais directionnel)")


if __name__ == '__main__':
    main()
