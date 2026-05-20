#!/usr/bin/env python3
"""
OANDA Historical Data Downloader
Utilise l'API OANDA pour telecharger des donnees historiques.
Plus fiable que Dukascopy (on a deja la connexion).

Usage: python3 oanda-downloader.py --symbol EUR_USD --year 2025 --count 5000
"""

import os, sys, json, time, argparse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

OUTPUT_DIR = Path(__file__).parent / ".." / "data" / "historical"

# Lire les credentials depuis le .env de trading-dashboard
ENV_PATH = os.path.expanduser("~/projects/trading-dashboard/.env")
API_KEY = None
ACCOUNT_ID = None

if os.path.exists(ENV_PATH):
    with open(ENV_PATH) as f:
        for line in f:
            if line.startswith('OANDA_API_KEY'):
                API_KEY = line.split('=')[1].strip()
            elif line.startswith('OANDA_ACCOUNT_ID'):
                ACCOUNT_ID = line.split('=')[1].strip()

PAIRS = {
    'EUR_USD': 'EUR/USD', 'GBP_USD': 'GBP/USD', 'USD_JPY': 'USD/JPY',
    'USD_CAD': 'USD/CAD', 'AUD_USD': 'AUD/USD', 'GBP_JPY': 'GBP/JPY',
    'USD_CHF': 'USD/CHF', 'NZD_USD': 'NZD/USD', 'EUR_GBP': 'EUR/GBP',
    'EUR_JPY': 'EUR/JPY', 'AUD_JPY': 'AUD/JPY', 'CHF_JPY': 'CHF/JPY',
}

GRANULARITIES = {
    'M1': 'M1', 'M5': 'M5', 'M15': 'M15', 'M30': 'M30',
    'H1': 'H1', 'H4': 'H4', 'D1': 'D', 'W1': 'W',
}

def oanda_request(url):
    req = urllib.request.Request(url)
    req.add_header('Authorization', f'Bearer {API_KEY}')
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read())

def download_oanda(symbol, granularity='H1', count=5000):
    """Download historical candles from OANDA"""
    base = "https://api-fxpractice.oanda.com/v3"
    url = f"{base}/instruments/{symbol}/candles?granularity={granularity}&count={count}"
    
    print(f"Downloading {symbol} ({granularity}) - {count} candles...")
    data = oanda_request(url)
    
    candles = []
    for c in data.get('candles', []):
        if not c.get('complete'):
            continue
        m = c['mid']
        candle = {
            'time': c['time'][:19],  # ISO format sans TZ
            'open': float(m['o']),
            'high': float(m['h']),
            'low': float(m['l']),
            'close': float(m['c']),
            'volume': c.get('volume', 0),
        }
        candles.append(candle)
    
    return candles

def save_csv(symbol, granularity, candles, output_dir):
    """Sauvegarder en CSV"""
    output_dir.mkdir(parents=True, exist_ok=True)
    filename = f"{symbol}_{granularity}.csv"
    filepath = output_dir / filename
    
    with open(filepath, 'w') as f:
        f.write("timestamp,open,high,low,close,volume\n")
        for c in candles:
            f.write(f"{c['time']},{c['open']:.5f},{c['high']:.5f},{c['low']:.5f},{c['close']:.5f},{c['volume']}\n")
    
    print(f"  📁 Saved: {filepath} ({len(candles)} candles)")
    return filepath

def main():
    parser = argparse.ArgumentParser(description='OANDA Historical Data Downloader')
    parser.add_argument('--symbol', default='EUR_USD')
    parser.add_argument('--timeframe', default='H1', choices=GRANULARITIES.keys())
    parser.add_argument('--count', type=int, default=5000, help='Candles per pair')
    parser.add_argument('--all', action='store_true', help='Download all 12 major pairs')
    parser.add_argument('--months', type=int, default=6, help='Months of data per pair')
    
    args = parser.parse_args()
    
    if not API_KEY:
        print("❌ OANDA_API_KEY not found in ~/projects/trading-dashboard/.env")
        sys.exit(1)
    
    print(f"📥 OANDA Historical Data Downloader")
    print(f"   Account: {ACCOUNT_ID}")
    
    output_dir = OUTPUT_DIR.resolve()
    symbols = list(PAIRS.keys()) if args.all else [args.symbol.upper()]
    
    for symbol in symbols:
        try:
            candles = download_oanda(symbol, args.timeframe, args.count)
            if candles:
                save_csv(symbol, args.timeframe, candles, output_dir)
                print(f"   Period: {candles[0]['time'][:10]} → {candles[-1]['time'][:10]}")
                print(f"   Range: {candles[-1]['close']:.5f} - {candles[0]['close']:.5f}")
            time.sleep(0.5)  # Rate limiting OANDA
        except Exception as e:
            print(f"  ❌ Error: {e}")
    
    print(f"\n✅ Done! Data in {output_dir}")

if __name__ == '__main__':
    main()
