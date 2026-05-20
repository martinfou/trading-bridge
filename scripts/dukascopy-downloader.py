#!/usr/bin/env python3
"""
Dukascopy Historical Data Downloader
Telecharge les donnees de marche depuis l'API publique Dukascopy.
Format: CSV compatible avec trading-bridge DataLoader.

Usage: python3 dukascopy-downloader.py --symbol EURUSD --year 2025 --month 1 --timeframe H1

Sources: 
  - Dukascopy API: datafeed.dukascopy.com (format BI5)
  - Fallback: HistData.com (format CSV)
"""

import urllib.request
import json
import struct
import gzip
import io
import os
import sys
import argparse
from datetime import datetime, timedelta, timezone
from pathlib import Path

OUTPUT_DIR = Path(__file__).parent / ".." / "data" / "historical"

SYMBOLS = {
    'EURUSD': 'EUR/USD', 'GBPUSD': 'GBP/USD', 'USDJPY': 'USD/JPY',
    'USDCAD': 'USD/CAD', 'AUDUSD': 'AUD/USD', 'GBPJPY': 'GBP/JPY',
    'USDCHF': 'USD/CHF', 'NZDUSD': 'NZD/USD', 'EURGBP': 'EUR/GBP',
    'EURJPY': 'EUR/JPY', 'AUDJPY': 'AUD/JPY', 'CHFJPY': 'CHF/JPY',
}

TIMEFRAMES = {'M1': 1, 'M5': 5, 'M15': 15, 'M30': 30, 'H1': 60, 'H4': 240, 'D1': 1440}

def dukascopy_url(symbol, year, month, day, timeframe='1h'):
    """URL de l'API Dukascopy pour les donnees historiques"""
    symbol = symbol.upper().replace('/', '')
    tf_map = {'M1': '1min', 'M5': '5min', 'M15': '15min', 'M30': '30min', 
              'H1': '1h', 'H4': '4h', 'D1': 'day'}
    return f"https://datafeed.dukascopy.com/datafeed/{symbol}/{year}/{month:02d}/{day:02d}/{tf_map.get(timeframe, '1h')}_ask.bi5"

def parse_bi5(data):
    """Parse le format binaire BI5 de Dukascopy en bougies OHLCV"""
    buf = io.BytesIO(data)
    candles = []
    while True:
        chunk = buf.read(20)  # 5 int32 = 20 bytes
        if len(chunk) < 20:
            break
        vals = struct.unpack('>5i', chunk)
        # Dukascopy: time(ms), open, high, low, close, volume (×100000 pour prix)
        ts = vals[0]
        o = vals[1] / 100000.0
        h = vals[2] / 100000.0
        l = vals[3] / 100000.0
        c = vals[4] / 100000.0
        candles.append((ts, o, h, l, c))
    return candles

def download_day(symbol, year, month, day, timeframe='H1'):
    """Telecharge 1 jour de donnees Dukascopy"""
    url = dukascopy_url(symbol, year, month, day, timeframe)
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            # Les donnees sont compressees en GZIP
            try:
                decompressed = gzip.decompress(raw)
            except:
                decompressed = raw
            return parse_bi5(decompressed)
    except Exception as e:
        print(f"  ⚠ Dukascopy error for {symbol} {year}-{month:02d}-{day:02d}: {e}")
        return []

def download_range(symbol, year, month, timeframe='H1', days=None):
    """Telecharge une plage de donnees"""
    if days is None:
        # Dernier jour du mois
        if month == 12:
            days = 31
        else:
            days = (datetime(year, month + 1, 1) - timedelta(days=1)).day
    
    all_candles = []
    print(f"Downloading {symbol} {year}-{month:02d} ({timeframe})...")
    
    for day in range(1, days + 1):
        candles = download_day(symbol, year, month, day, timeframe)
        if candles:
            all_candles.extend(candles)
        
        # Progression
        if day % 5 == 0 or day == days:
            pct = day / days * 100
            print(f"  {day}/{days} jours ({pct:.0f}%) - {len(candles)} candles aujourd'hui", end='\r')
    
    print(f"\n  ✅ Total: {len(all_candles)} candles")
    return all_candles

def save_csv(symbol, timeframe, candles, output_dir):
    """Sauvegarde en format CSV compatible trading-bridge"""
    # Grouper par jour et trier par timestamp
    candles.sort(key=lambda x: x[0])
    
    filename = f"{symbol}_{timeframe}.csv"
    filepath = output_dir / filename
    
    with open(filepath, 'w') as f:
        f.write("timestamp,open,high,low,close,volume\n")
        for ts, o, h, l, c in candles:
            # Convertir timestamp Dukascopy (ms depuis 1970-01-01 00:00:00 UTC)
            dt = datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(milliseconds=ts)
            f.write(f"{dt.isoformat()},{o:.5f},{h:.5f},{l:.5f},{c:.5f},0\n")
    
    print(f"  📁 Saved: {filepath}")
    return filepath

def main():
    parser = argparse.ArgumentParser(description='Dukascopy Historical Data Downloader')
    parser.add_argument('--symbol', default='EURUSD', help='Symbol (EURUSD, GBPUSD...)')
    parser.add_argument('--year', type=int, default=2025, help='Year')
    parser.add_argument('--month', type=int, default=1, help='Month (1-12)')
    parser.add_argument('--timeframe', default='H1', choices=TIMEFRAMES.keys(), help='Timeframe')
    parser.add_argument('--months', type=int, default=3, help='Number of months to download')
    parser.add_argument('--all', action='store_true', help='Download all major pairs')
    
    args = parser.parse_args()
    
    output_dir = OUTPUT_DIR.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    
    symbols = SYMBOLS.keys() if args.all else [args.symbol.upper()]
    
    for symbol in symbols:
        # Telecharger plusieurs mois
        all_candles = []
        for m in range(args.months):
            month = args.month + m
            year = args.year
            if month > 12:
                month -= 12
                year += 1
            candles = download_range(symbol, year, month, args.timeframe)
            all_candles.extend(candles)
        
        if all_candles:
            save_csv(symbol, args.timeframe, all_candles, output_dir)
        else:
            print(f"  ❌ No data for {symbol}")
    
    print(f"\n✅ Done! Data in {output_dir}")

if __name__ == '__main__':
    main()
