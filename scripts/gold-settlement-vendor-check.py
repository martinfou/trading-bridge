#!/usr/bin/env python3
"""62e annexe — LE SIGNE DE LA BARRE [16,17) ET DEPEND-IL DU FOURNISSEUR / DE L'INSTRUMENT ?
Le 59e (crosses refuge, OANDA H1 BID) mesure la derniere heure avant la cloture NEGATIVE le vendredi
(ex. GBP_CHF -3.35 bp). Si les majeures Dukascopy H1 BID portent le signe OPPOSE a la MEME heure, la
fenetre n'est pas un flux mais un artefact de microstructure (elargissement du spread au rollover).
Controle de specificite ±1 heure : [15,16) et [17,18) ET doivent alors porter la meme chose.
Donnees 2016+ (periode ou le trou de settlement de l'or est systematique).
"""
import csv, datetime as dt, glob, math, os, collections

DATA = os.path.expanduser("~/projects/tb-research/data/historical/dukascopy")
UTC = dt.timezone.utc
SERIES = {
    # crosses refuge du 59e (OANDA, un seul fichier 2002+)
    "GBP_CHF(O)": "gbpchf-h1-bid-oanda-2002-05-07-2026-10-09.csv",
    "EUR_CHF(O)": "eurchf-h1-bid-oanda-2002-05-06-2026-10-09.csv",
    "AUD_CHF(O)": "audchf-h1-bid-oanda-2004-05-31-2026-10-09.csv",
    # majeures + or Dukascopy (un fichier par an)
    "EUR_USD(D)": None, "GBP_JPY(D)": None, "USD_CHF(D)": None, "USD_CAD(D)": None, "XAU_USD(D)": None,
}


def load(key, fname):
    out = {}
    if fname:
        paths = [f"{DATA}/{fname}"]
    else:
        base = key.split("(")[0].replace("_", "").lower()
        paths = sorted(glob.glob(f"{DATA}/{base}-h1-bid-[12]*.csv"))
    for path in paths:
        with open(path, newline="") as f:
            r = csv.reader(f); next(r, None)
            for row in r:
                if len(row) < 5 or not row[0].strip().isdigit(): continue
                v = int(row[0]); v = v * 1000 if v < 10 ** 11 else v
                try:
                    t = dt.datetime.fromtimestamp(v / 1000.0, tz=UTC).replace(minute=0, second=0, microsecond=0)
                    out[t] = tuple(float(x) for x in row[1:5])
                except (ValueError, OverflowError, OSError): continue
    return {t: v for t, v in out.items() if v[1] > v[2]}


def dst(y):
    def sun(m, n):
        d = dt.date(y, m, 1); c = 0
        while True:
            if d.weekday() == 6:
                c += 1
                if c == n: return d
            d += dt.timedelta(days=1)
    return sun(3, 2), sun(11, 1)


def settle_hour(d):
    a, b = dst(d.year)
    return 20 if a <= d < b else 21


def tstat(xs):
    n = len(xs)
    if n < 3: return 0.0, 0.0
    m = sum(xs) / n
    sd = (sum((x - m) ** 2 for x in xs) / (n - 1)) ** 0.5
    return m, (m / (sd / n ** 0.5) if sd > 0 else 0.0)


print("=" * 118)
print("62e ANNEXE — barre [16,17) ET : le signe depend-il du FOURNISSEUR ? controle de specificite +-1 heure")
print("FADE = SELL ; fade = -log(close/open)*1e4 ; fade > 0 = le prix BAISSE dans la barre. 2016+ seulement.")
print("=" * 118)
print(f"\n{'SERIE':12s}{'n lun-jeu':>10s}{'[15,16)':>16s}{'[16,17)':>16s}{'[17,18)':>16s}"
      f"{'n ven':>8s}{'[15,16)':>16s}{'[16,17)':>16s}{'[17,18)':>16s}")
for key, fname in SERIES.items():
    d2 = load(key, fname)
    days = sorted({t.date() for t in d2 if t.year >= 2016 and t.weekday() < 5})
    cells = collections.defaultdict(list)
    for d in days:
        h = settle_hour(d)
        for off, tag in ((-1, "-1"), (0, "0"), (1, "+1")):
            t = dt.datetime.combine(d, dt.time(0, 0), tzinfo=UTC) + dt.timedelta(hours=h + off)
            if t not in d2: continue
            o, hi, lo, c = d2[t]
            if c <= 0 or o <= 0: continue
            v = -math.log(c / o) * 1e4
            cells[(("ven" if d.weekday() == 4 else "mj"), tag)].append(v)
    def cell(k):
        m, t = tstat(cells[k])
        return f"{m:+8.2f}({t:+5.1f})" if cells[k] else f"{'n/a':>16s}"
    P = print
    P(f"{key:12s}{len(cells[('mj','0')]):10d}{cell(('mj','-1')):>16s}{cell(('mj','0')):>16s}{cell(('mj','+1')):>16s}"
      f"{len(cells[('ven','0')]):8d}{cell(('ven','-1')):>16s}{cell(('ven','0')):>16s}{cell(('ven','+1')):>16s}")

print("\nLecture : si le signe de [16,17) s'inverse entre les crosses OANDA et les majeures/or Dukascopy,")
print("la fenetre mesure un artefact de spread au rollover, pas un flux. Le controle +-1 heure dit si l'effet")
print("est localise sur cette barre ou etale sur la journee.")
