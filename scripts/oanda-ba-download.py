#!/usr/bin/env python3
"""
Download OANDA BA (bid+ask) candles for refuge crosses — the ASK-side data the
pipeline has been missing (flagged 3x: fade 59e, lane D47, rollover 62e).

Read-only. Cached to CSV so reruns are free.
"""
import os, json, time, sys, urllib.request, urllib.error
from datetime import datetime, timedelta, timezone

KEY = None
for line in open(os.path.expanduser("~/projects/trading-bridge/.env.dev")):
    if line.strip().startswith("OANDA_API_KEY"):
        KEY = line.split("=", 1)[1].strip().strip('"')
BASE = "https://api-fxpractice.oanda.com/v3"
OUT = os.path.expanduser("~/projects/tb-research/data/oanda-ba")
os.makedirs(OUT, exist_ok=True)

STEP = {"H1": timedelta(hours=1), "M5": timedelta(minutes=5), "M1": timedelta(minutes=1)}
FMT = "%Y-%m-%dT%H:%M:%SZ"

def fetch(inst, gran, frm, count=5000):
    url = (f"{BASE}/instruments/{inst}/candles?granularity={gran}&price=BA"
           f"&from={frm.strftime(FMT)}&count={count}")
    req = urllib.request.Request(url)
    req.add_header("Authorization", f"Bearer {KEY}")
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=40) as r:
                return json.loads(r.read()).get("candles", [])
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(2 + attempt * 3)
                continue
            print("    HTTP", e.code, e.read()[:300], "url=", url)
            return []
        except Exception:
            time.sleep(2 + attempt * 3)
    return []

def download(inst, gran, start, end):
    path = os.path.join(OUT, f"{inst}_{gran}_BA.csv")
    if os.path.exists(path):
        with open(path) as f:
            n = sum(1 for _ in f)
        if n > 10:
            print(f"  cached {path.split('/')[-1]} rows={n}")
            return path
        print(f"  incomplete cache ({n} lines), redownload")
    frm = start
    rows = 0
    with open(path, "w") as f:
        f.write("time,bid_o,bid_h,bid_l,bid_c,ask_o,ask_h,ask_l,ask_c,volume,complete\n")
        while frm < end:
            cs = fetch(inst, gran, frm)
            if not cs:
                break
            for c in cs:
                t = datetime.strptime(c["time"][:19] + "Z", FMT).replace(tzinfo=timezone.utc)
                if t > end:
                    break
                b, a = c["bid"], c["ask"]
                f.write(f"{c['time'][:19]}Z,{b['o']},{b['h']},{b['l']},{b['c']},"
                        f"{a['o']},{a['h']},{a['l']},{a['c']},{c.get('volume',0)},"
                        f"{int(c.get('complete', True))}\n")
                rows += 1
            last = datetime.strptime(cs[-1]["time"][:19] + "Z", FMT).replace(tzinfo=timezone.utc)
            nxt = last + STEP[gran]
            if nxt <= frm:
                break
            frm = nxt
            f.flush()
            if rows % 50000 < 5000:
                print(f"    {inst} {gran} {rows} rows ... {frm.date()}")
    print(f"  {inst} {gran}: {rows} rows -> {path}")
    return path

if __name__ == "__main__":
    end = datetime(2026, 10, 23, tzinfo=timezone.utc)
    print("H1 BA 2005->now, 3 refuge crosses")
    for inst in ["GBP_CHF", "EUR_CHF", "AUD_CHF"]:
        download(inst, "H1", datetime(2005, 1, 1, tzinfo=timezone.utc), end)
    print("M5 BA 2022->now, GBP_CHF + AUD_CHF")
    for inst in ["GBP_CHF", "AUD_CHF"]:
        download(inst, "M5", datetime(2022, 1, 1, tzinfo=timezone.utc), end)
