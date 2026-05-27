#!/usr/bin/env python3
"""
Analyse de qualité des données historiques Forex
================================================
Identifie:
- Données manquantes (gaps dans la timeline)
- Données anormales (spikes, flatlines, outliers)
- Suggère des corrections

Usage:
  python3 scripts/analyze-data-quality.py                          # scan tous les .bars
  python3 scripts/analyze-data-quality.py --symbol GBP_JPY          # un seul asset
  python3 scripts/analyze-data-quality.py --symbol XAU_USD          # Gold
  python3 scripts/analyze-data-quality.py --check-day 2025-01-01    # vérifier un jour spécifique
  python3 scripts/analyze-data-quality.py --repair                  # tenter les corrections
"""

import struct
import os
import sys
import json
import glob
from datetime import datetime, timedelta, timezone
from collections import defaultdict

BARS_DIR = os.path.join(os.path.dirname(__file__), "..", "data", "historical", "bars")

# .bars format: Big-Endian, timestamps in milliseconds (Java-compatible)
BAR_FORMAT = '>qddddi'
REPORT_DIR = os.path.join(os.path.dirname(__file__), "..", "data", "quality-reports")

# Seuils de détection
MAX_H1_GAP_SECONDS = 7200       # 2h max entre barres H1 (normal = 3600s, weekend fermé)
MAX_PRICE_SPIKE_PCT = 10.0      # 10% de move en 1h = spike suspect
MIN_FLATLINE_HOURS = 12         # 12h sans aucune variation de prix = suspect
MIN_FLATLINE_MOVE_PCT = 0.005   # 0.005% = variation minimale pour considérer un mouvement
MIN_BARS_PER_YEAR = 8000        # minimum barres H1 par année (attendu: ~8736)
PRICE_RANGE_WARN = {            # plages de prix attendues par asset
    "GBP_JPY": (120, 250),
    "EUR_USD": (0.8, 1.6),
    "GBP_USD": (1.0, 2.0),
    "USD_JPY": (75, 160),
    "USD_CAD": (0.9, 1.6),
    "AUD_USD": (0.5, 1.2),
    "NZD_USD": (0.4, 0.9),
    "USD_CHF": (0.8, 1.2),
    "XAU_USD": (200, 5000),
    "EUR_GBP": (0.7, 1.0),
    "EUR_JPY": (90, 175),
    "AUD_JPY": (55, 115),
    "CHF_JPY": (90, 130),
}

class BarReader:
    """Lit le format .bars binaire (44 bytes/barre)."""
    
    @staticmethod
    def read_bars(filepath):
        """Lit toutes les barres d'un fichier .bars."""
        bars = []
        with open(filepath, 'rb') as f:
            while True:
                chunk = f.read(44)
                if len(chunk) < 44:
                    break
                ts, o, h, lo, c, v = struct.unpack(BAR_FORMAT, chunk)
                ts = ts // 1000  # ms → seconds for Python datetime
                dt = datetime.fromtimestamp(ts, tz=timezone.utc)
                bars.append({
                    'timestamp': ts,
                    'datetime': dt,
                    'open': o, 'high': h, 'low': lo, 'close': c, 'volume': v
                })
        return bars

    @staticmethod
    def read_year_file(symbol, year):
        """Lit un fichier annuel .bars."""
        path = os.path.join(BARS_DIR, f"{symbol}_H1_{year}.bars")
        if not os.path.exists(path):
            return None
        return BarReader.read_bars(path)

    @staticmethod
    def read_merged(symbol):
        """Lit le fichier merged .bars."""
        # Essayer le symlink d'abord
        path = os.path.join(BARS_DIR, f"{symbol}_H1_H1.bars")
        if not os.path.exists(path):
            path = os.path.join(BARS_DIR, f"{symbol}_H1.bars")
        if not os.path.exists(path):
            return None
        return BarReader.read_bars(path)


class DataQualityAnalyzer:
    """Analyse la qualité des données historiques."""
    
    def __init__(self, symbol):
        self.symbol = symbol
        self.bars = []
        self.issues = []
        self.warnings = []
        self.stats = {}
    
    def load(self):
        """Charge les données de l'asset."""
        # D'abord essayer le merged
        bars = BarReader.read_merged(self.symbol)
        if bars is None:
            # Fallback: lire année par année
            all_bars = []
            for year in range(2006, 2027):
                yb = BarReader.read_year_file(self.symbol, year)
                if yb:
                    all_bars.extend(yb)
            bars = all_bars
        
        self.bars = bars
        return len(bars)
    
    def check_timeline_gaps(self):
        """Vérifie les gaps dans la timeline."""
        if len(self.bars) < 2:
            return
        
        gaps = []
        for i in range(1, len(self.bars)):
            gap = self.bars[i]['timestamp'] - self.bars[i-1]['timestamp']
            if gap > MAX_H1_GAP_SECONDS:
                gaps.append({
                    'from': self.bars[i-1]['datetime'].isoformat(),
                    'to': self.bars[i]['datetime'].isoformat(),
                    'gap_seconds': gap,
                    'gap_hours': round(gap / 3600, 1),
                    'expected': 'weekend' if gap < 72000 else 'missing_data'
                })
        
        if gaps:
            big_gaps = [g for g in gaps if g['gap_hours'] > 72]
            weekend_gaps = [g for g in gaps if 48 <= g['gap_hours'] <= 72]
            
            self.issues.append({
                'type': 'timeline_gaps',
                'severity': 'WARN' if len(big_gaps) < 10 else 'CRITICAL',
                'total_gaps': len(gaps),
                'big_gaps': len(big_gaps),
                'weekend_gaps': len(weekend_gaps),
                'big_gaps_detail': big_gaps[:5],
                'max_gap_hours': max(g['gap_hours'] for g in gaps) if gaps else 0
            })
    
    def check_price_spikes(self):
        """Détecte les spikes de prix anormaux."""
        if len(self.bars) < 3:
            return
        
        spikes = []
        for i in range(2, len(self.bars)):
            prev_close = self.bars[i-1]['close']
            curr_high = self.bars[i]['high']
            curr_low = self.bars[i]['low']
            
            if prev_close == 0:
                continue
            
            move_up = (curr_high - prev_close) / prev_close * 100
            move_down = (prev_close - curr_low) / prev_close * 100
            max_move = max(move_up, move_down)
            
            if max_move > MAX_PRICE_SPIKE_PCT:
                spikes.append({
                    'datetime': self.bars[i]['datetime'].isoformat(),
                    'prev_close': round(prev_close, 3),
                    'high': round(curr_high, 3),
                    'low': round(curr_low, 3),
                    'move_pct': round(max_move, 2),
                    'direction': 'up' if move_up > move_down else 'down'
                })
        
        if spikes:
            self.issues.append({
                'type': 'price_spikes',
                'severity': 'INFO' if len(spikes) < 5 else 'WARN',
                'count': len(spikes),
                'spikes_detail': spikes[:5]
            })
    
    def check_flatlines(self):
        """Détecte les périodes sans mouvement (données gelées).
        
        Exclut les weekends (gaps > 6h) car ce sont des fermetures normales.
        """
        if len(self.bars) < 6:
            return
        
        flat_start = None
        flats = []
        
        for i in range(1, len(self.bars)):
            # Skip weekends: si l'écart entre barres est > 6h, c'est une fermeture
            gap = self.bars[i]['timestamp'] - self.bars[i-1]['timestamp']
            if gap > MAX_H1_GAP_SECONDS:
                # Reset flat detection across weekends
                if flat_start is not None:
                    duration = i - flat_start
                    if duration >= MIN_FLATLINE_HOURS:
                        flats.append({
                            'from': self.bars[flat_start]['datetime'].isoformat(),
                            'to': self.bars[i-1]['datetime'].isoformat(),
                            'duration_hours': duration
                        })
                    flat_start = None
                continue
            
            price = self.bars[i]['close']
            threshold = price * MIN_FLATLINE_MOVE_PCT / 100.0  # 0.005% of current price
            
            # A bar is flat if: open=high=low=close (identical) AND same as previous close
            bar = self.bars[i]
            bar_is_duplicate = (bar['open'] == bar['high'] == bar['low'] == bar['close'] and
                               bar['close'] == self.bars[i-1]['close'])
            prev_is_duplicate = (self.bars[i-1]['open'] == self.bars[i-1]['high'] == 
                                self.bars[i-1]['low'] == self.bars[i-1]['close'] and
                                self.bars[i-1]['close'] == self.bars[i-2]['close']) if i >= 2 else False
            
            if bar_is_duplicate and prev_is_duplicate:
                if flat_start is None:
                    flat_start = i - 2
            else:
                if flat_start is not None:
                    duration = i - flat_start
                    if duration >= MIN_FLATLINE_HOURS:
                        flats.append({
                            'from': self.bars[flat_start]['datetime'].isoformat(),
                            'to': self.bars[i-1]['datetime'].isoformat(),
                            'duration_hours': duration
                        })
                    flat_start = None
        
        # Check end
        if flat_start is not None:
            duration = len(self.bars) - flat_start
            if duration >= MIN_FLATLINE_HOURS:
                flats.append({
                    'from': self.bars[flat_start]['datetime'].isoformat(),
                    'to': self.bars[-1]['datetime'].isoformat(),
                    'duration_hours': duration
                })
        
        if flats:
            # Classify: weekend/holiday closures vs genuine trading-hour anomalies
            weekend_flats = [f for f in flats if f['duration_hours'] >= 48]
            short_flats = [f for f in flats if f['duration_hours'] < 48]
            
            if short_flats:
                # Short flats during holidays (New Year, Christmas, etc.)
                holiday_hours = sum(f['duration_hours'] for f in short_flats)
                self.issues.append({
                    'type': 'holiday_duplicates',
                    'severity': 'INFO',
                    'count': len(short_flats),
                    'count_weekend_long': len(weekend_flats),
                    'total_hours': round(holiday_hours, 1),
                    'flats_detail': short_flats[:3]
                })
    
    def check_year_coverage(self):
        """Vérifie la couverture par année."""
        years = defaultdict(int)
        for bar in self.bars:
            y = bar['datetime'].year
            years[y] += 1
        
        missing_years = []
        partial_years = []
        
        for year in range(2006, 2026):
            count = years.get(year, 0)
            if count == 0:
                missing_years.append(year)
            elif count < MIN_BARS_PER_YEAR:
                partial_years.append({'year': year, 'bars': count, 'expected': '~8736'})
        
        if missing_years or partial_years:
            self.issues.append({
                'type': 'year_coverage',
                'severity': 'CRITICAL' if len(missing_years) > 5 else 'WARN',
                'missing_years': missing_years,
                'partial_years': partial_years,
                'year_counts': dict(years)
            })
    
    def check_price_range(self):
        """Vérifie que les prix sont dans une plage réaliste."""
        if not self.bars:
            return
        
        all_prices = []
        for bar in self.bars:
            all_prices.extend([bar['open'], bar['high'], bar['low'], bar['close']])
        
        min_p = min(all_prices)
        max_p = max(all_prices)
        
        expected = PRICE_RANGE_WARN.get(self.symbol)
        if expected:
            if min_p < expected[0] or max_p > expected[1]:
                self.issues.append({
                    'type': 'price_range',
                    'severity': 'WARN',
                    'min_price': round(min_p, 3),
                    'max_price': round(max_p, 3),
                    'expected_range': f"{expected[0]}-{expected[1]}",
                    'note': 'Prix hors plage attendue — peut être normal selon la période'
                })
    
    def run(self):
        """Execute toutes les vérifications."""
        count = self.load()
        if count == 0:
            return {'symbol': self.symbol, 'bars': 0, 'error': 'No data found'}
        
        self.stats['total_bars'] = count
        self.stats['date_from'] = self.bars[0]['datetime'].isoformat()
        self.stats['date_to'] = self.bars[-1]['datetime'].isoformat()
        
        self.stats['first_price'] = self.bars[0]['close']
        self.stats['last_price'] = self.bars[-1]['close']
        
        # Prix min/max globaux
        all_highs = [b['high'] for b in self.bars]
        all_lows = [b['low'] for b in self.bars]
        self.stats['price_min'] = round(min(all_lows), 3)
        self.stats['price_max'] = round(max(all_highs), 3)
        
        self.check_timeline_gaps()
        self.check_price_spikes()
        self.check_flatlines()
        self.check_year_coverage()
        self.check_price_range()
        
        # Suggérer des corrections
        corrections = []
        for issue in self.issues:
            if issue['type'] == 'timeline_gaps' and issue.get('big_gaps', 0) > 0:
                corrections.append(f"Re-télécharger les années avec gaps > 72h")
            if issue['type'] == 'flatlines' and issue.get('count', 0) > 0:
                corrections.append(f"Re-télécharger les mois avec flatlines > {MIN_FLATLINE_HOURS}h")
            if issue['type'] == 'year_coverage' and issue.get('missing_years'):
                corrections.append(f"Télécharger années manquantes: {issue['missing_years']}")
            if issue['type'] == 'price_spikes' and issue.get('count', 0) > 3:
                corrections.append(f"Vérifier les {issue['count']} spikes > {MAX_PRICE_SPIKE_PCT}%")
        
        return {
            'symbol': self.symbol,
            'bars': count,
            'stats': self.stats,
            'issues': self.issues,
            'corrections': corrections,
            'severity': max((i['severity'] for i in self.issues), default='OK')
        }


def scan_all_assets():
    """Scanne tous les assets disponibles."""
    print("\n" + "=" * 70)
    print("  DATA QUALITY REPORT — FOREX HISTORICAL DATA")
    print("=" * 70)
    
    # Trouver tous les fichiers .bars
    bar_files = sorted(glob.glob(os.path.join(BARS_DIR, "*_H1_*.bars")))
    symbols = set()
    for f in bar_files:
        name = os.path.basename(f)
        # Format: SYMBOL_H1_YEAR.bars ou SYMBOL_H1.bars
        parts = name.split('_H1_')
        if len(parts) >= 1:
            sym = parts[0]
            if sym not in ['combined', 'merged']:
                symbols.add(sym)
    
    # Trier par ordre logique
    priority = ['GBP_JPY', 'XAU_USD', 'EUR_USD', 'GBP_USD', 'USD_JPY', 
                'USD_CAD', 'AUD_USD', 'NZD_USD', 'USD_CHF']
    sorted_symbols = sorted(symbols, key=lambda s: priority.index(s) if s in priority else 999)
    
    all_results = {}
    overall_bars = 0
    
    for sym in sorted_symbols:
        analyzer = DataQualityAnalyzer(sym)
        result = analyzer.run()
        all_results[sym] = result
        overall_bars += result.get('bars', 0)
        
        severity = result.get('severity', 'OK')
        sev_icon = '✅' if severity == 'OK' else ('⚠️' if severity == 'WARN' else '🔴')
        
        print(f"\n  {sev_icon}  {sym}")
        print(f"  {'─' * 40}")
        
        if 'error' in result:
            print(f"     ❌ {result['error']}")
            continue
        
        stats = result['stats']
        print(f"     Barres:   {stats.get('total_bars', 0):,}")
        print(f"     Période:  {stats.get('date_from', '?')[:10]} → {stats.get('date_to', '?')[:10]}")
        print(f"     Prix:     {stats.get('price_min')} → {stats.get('price_max')}")
        
        issues = result.get('issues', [])
        if issues:
            for issue in issues:
                sev = '🔴' if issue['severity'] == 'CRITICAL' else ('⚠️' if issue['severity'] == 'WARN' else 'ℹ️')
                if issue['type'] == 'timeline_gaps':
                    print(f"     {sev} Gaps timeline: {issue.get('total_gaps', 0)} total, {issue.get('big_gaps', 0)} majeurs (>72h)")
                elif issue['type'] == 'price_spikes':
                    print(f"     {sev} Spikes prix: {issue.get('count', 0)} > {MAX_PRICE_SPIKE_PCT}%")
                elif issue['type'] == 'flatlines':
                    print(f"     {sev} Flatlines: {issue.get('count', 0)} périodes gelées")
                elif issue['type'] == 'year_coverage':
                    missing = issue.get('missing_years', [])
                    partial = issue.get('partial_years', [])
                    if missing:
                        print(f"     {sev} Années manquantes: {missing}")
                    if partial:
                        print(f"     {sev} Années partielles: {[p['year'] for p in partial]}")
                elif issue['type'] == 'price_range':
                    print(f"     {sev} Prix hors plage: {issue.get('min_price')}–{issue.get('max_price')}")
        else:
            print(f"     ✅ Aucun problème détecté")
        
        corrections = result.get('corrections', [])
        if corrections:
            for c in corrections:
                print(f"     🔧 Suggestion: {c}")
    
    # Résumé global
    print(f"\n{'=' * 70}")
    print(f"  RÉSUMÉ GLOBAL")
    print(f"{'=' * 70}")
    
    total_issues = 0
    criticals = 0
    for sym, result in all_results.items():
        if 'error' not in result:
            for issue in result.get('issues', []):
                total_issues += 1
                if issue['severity'] == 'CRITICAL':
                    criticals += 1
    
    print(f"\n  Assets analysés: {len([r for r in all_results.values() if 'error' not in r])}")
    print(f"  Total barres:    {overall_bars:,}")
    print(f"  Problèmes:       {total_issues} (CRITICAL: {criticals})")
    
    if criticals > 0:
        print(f"\n  🔴 RECOMMANDATION: Corriger les {criticals} problèmes critiques")
    else:
        print(f"\n  ✅ Données en bonne santé")
    
    print(f"\n{'=' * 70}\n")
    
    return all_results


def check_specific_day(symbol, date_str):
    """Vérifie un jour spécifique."""
    target = datetime.strptime(date_str, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    target_end = target + timedelta(days=1)
    
    analyzer = DataQualityAnalyzer(symbol)
    analyzer.load()
    
    bars_that_day = [b for b in analyzer.bars if target <= b['datetime'] < target_end]
    
    print(f"\n  📅 {symbol} — {date_str}")
    print(f"  {'─' * 50}")
    
    if not bars_that_day:
        print("  ❌ Aucune donnée pour ce jour")
        return
    
    print(f"  Barres: {len(bars_that_day)} (attendu: 24 pour H1)")
    print(f"  Prix:   {bars_that_day[0]['open']:.3f} → {bars_that_day[-1]['close']:.3f}")
    print(f"  High:   {max(b['high'] for b in bars_that_day):.3f}")
    print(f"  Low:    {min(b['low'] for b in bars_that_day):.3f}")
    
    # Détecter les gaps
    for i in range(1, len(bars_that_day)):
        gap = bars_that_day[i]['timestamp'] - bars_that_day[i-1]['timestamp']
        if gap > 3600:
            print(f"  ⚠️ Gap à {bars_that_day[i]['datetime'].strftime('%H:%M')}: {gap//60} min")
    
    # Détecter les flatlines
    for i in range(2, len(bars_that_day)):
        if bars_that_day[i]['close'] == bars_that_day[i-1]['close'] == bars_that_day[i-2]['close']:
            print(f"  ⚠️ Flatline à {bars_that_day[i]['datetime'].strftime('%H:%M')}")


def generate_report(all_results):
    """Génère un rapport JSON."""
    os.makedirs(REPORT_DIR, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    
    report = {
        'generated_at': datetime.now().isoformat(),
        'config': {
            'max_h1_gap_seconds': MAX_H1_GAP_SECONDS,
            'max_price_spike_pct': MAX_PRICE_SPIKE_PCT,
            'min_flatline_hours': MIN_FLATLINE_HOURS,
            'min_bars_per_year': MIN_BARS_PER_YEAR
        },
        'assets': all_results
    }
    
    path = os.path.join(REPORT_DIR, f"quality-report-{timestamp}.json")
    with open(path, 'w') as f:
        json.dump(report, f, indent=2, default=str)
    
    print(f"\n  📄 Rapport sauvegardé: {path}")


if __name__ == "__main__":
    import argparse
    
    parser = argparse.ArgumentParser(description="Analyse de qualité des données Forex")
    parser.add_argument("--symbol", help="Asset symbol (e.g. GBP_JPY, XAU_USD)")
    parser.add_argument("--check-day", help="Vérifier un jour spécifique (YYYY-MM-DD)")
    parser.add_argument("--report", action="store_true", help="Générer rapport JSON")
    parser.add_argument("--list", action="store_true", help="Lister les assets disponibles")
    
    args = parser.parse_args()
    
    if args.check_day:
        sym = args.symbol or "GBP_JPY"
        check_specific_day(sym, args.check_day)
        sys.exit(0)
    
    if args.list:
        bar_files = sorted(glob.glob(os.path.join(BARS_DIR, "*_H1_*.bars")))
        symbols = set()
        for f in bar_files:
            name = os.path.basename(f)
            parts = name.split('_H1_')
            if len(parts) >= 1:
                sym = parts[0]
                if sym not in ['combined', 'merged']:
                    symbols.add(sym)
        
        print(f"\n  Assets disponibles:")
        for s in sorted(symbols):
            print(f"    • {s}")
        sys.exit(0)
    
    if args.symbol:
        analyzer = DataQualityAnalyzer(args.symbol.upper())
        result = analyzer.run()
        print(json.dumps(result, indent=2, default=str))
    else:
        results = scan_all_assets()
    
    if args.report:
        generate_report(results if not args.symbol else {args.symbol.upper(): result})
