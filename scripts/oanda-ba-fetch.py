#!/usr/bin/env python3
"""
Download OANDA BA (bid+ask) candles for refuge crosses — the ASK-side data the
pipeline has been missing (flagged 3x: fade 59e, lane D47, rollover 62e).

Resumable + retry on 5xx (OANDA throws 504 on long M5 walks). Read-only.
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
HDR = "time,bid_o,bid_h,bid_l,bid_c,ask_o,ask_h,ask_l,ask_c,volume,complete\n"

def fetch(inst, gran, frm, count=5000):
    url = (f"{BASE}/instruments/{inst}/candles?granularity={gran}&price=BA"
           f"&from={frm.strftime(FMT)}&count={count}")
    for attempt in range(7):
        req = urllib.request.Request(url)
        req.add_header("Authorization", f"Bearer {KEY}")
        try:
            with urllib.request.urlopen(req, timeout=45) as r:
                return json.loads(r.read()).get("candles", [])
        except urllib.error.HTTPError as e:
            body = e.read()[:120]
            if e.code in (429, 500, 502, 503, 504):
                time.sleep(2 + attempt * 4)
                continue
            print("    HARD HTTP", e.code, body)
            return []
        except Exception:
            time.sleep(2 + attempt * 4)
    print("    give up on chunk from", frm)
    return []

def download(inst, gran, start, end):
    path = os.path.join(OUT, f"{inst}_{gran}_BA.csv")
    frm = start
    rows = 0
    mode = "w"
    if os.path.exists(path) and os.path.getsize(path) > 500:
        with open(path) as f:
            lines = f.read().strip().split("\n")
        if len(lines) > 10:
            last = lines[-1].split(",")[0]
            frm = datetime.strptime(last, FMT).replace(tzinfo=timezone.utc) + STEP[gran]
            rows = len(lines) - 1
            mode = "a"
    if frm >= end:
        print(f"  {inst} {gran}: complete ({rows} rows)")
        return path
    with open(path, mode) as f:
        if mode == "w":
            f.write(HDR)
        while frm < end:
            cs = fetch(inst, gran, frm)
            if not cs:
                break
            wrote = 0
            for c in cs:
                t = datetime.strptime(c["time"][:19] + "Z", FMT).replace(tzinfo=timezone.utc)
                if t > end:
                    break
                b, a = c["bid"], c["ask"]
                ao, bo = float(a["o"]), float(b["o"])
                if bo <= 0 or ao <= 0 or ao < bo:
                    continue
                f.write(f"{c['time'][:19]}Z,{b['o']},{b['h']},{b['l']},{b['c']},"
                        f"{a['o']},{a['h']},{a['l']},{a['c']},{c.get('volume',0)},"
                        f"{int(c.get('complete', True))}\n")
                wrote += 1
                rows += 1
            last = datetime.strptime(cs[-1]["time"][:19] + "Z", FMT).replace(tzinfo=timezone.utc)
            nxt = last + STEP[gran]
            if wrote == 0 or nxt <= frm:
                break
            frm = nxt
            f.flush()
    print(f"  {inst} {gran}: {rows} rows -> {path.split('/')[-1]}")
    return path

if __name__ == "__main__":
    end = datetime(2026, 10, 23, tzinfo=timezone.utc)
    for inst in ["AUD_CHF"]:
        download(inst, "H1", datetime(2005, 1, 1, tzinfo=timezone.utc), end)
    for inst in ["GBP_CHF", "AUD_CHF"]:
        download(inst, "M5", datetime(2020, 1, 1, tzinfo=timezone.utc), end)
