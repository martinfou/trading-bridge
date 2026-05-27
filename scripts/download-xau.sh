#!/bin/bash
# =============================================================================
# Download XAU/USD (Gold) — 20 ans de données H1 de Dukascopy
# =============================================================================
# Dukascopy a des limites de données pour XAU/USD (pas de données avant ~2010)
# Ce script télécharge année par année et convertit en .bars
# =============================================================================

set -euo pipefail
cd "$(dirname "$0")/.."

DATA_DIR="./data/historical/dukascopy"
BARS_DIR="./data/historical/bars"
mkdir -p "$DATA_DIR" "$BARS_DIR"

# Années disponibles pour XAU/USD (Dukascopy)
YEARS=(2009 2010 2011 2012 2013 2014 2015 2016 2017 2018 2019 2020 2021 2022 2023 2024 2025)
PAIR="xauusd"
TF="h1"

echo "╔══════════════════════════════════════════════════════╗"
echo "║      XAU/USD (Gold) — Data Download                  ║"
echo "║      Timeframe: $TF | 2009-2025                       ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

for year in "${YEARS[@]}"; do
    # Check if already downloaded
    bars_file="${BARS_DIR}/XAU_USD_H1_${year}.bars"
    if [ -f "$bars_file" ] && [ -s "$bars_file" ]; then
        bars=$(python3 -c "import os; print(os.path.getsize('$bars_file') // 44)")
        echo "  ${year}: ✅ déjà converti ($bars barres)"
        continue
    fi

    from="${year}-01-01"
    to="${year}-12-31"
    
    echo -n "  ${year}: 📥 téléchargement... "
    
    # Download with dukascopy-node
    npx dukascopy-node -i "$PAIR" -from "$from" -to "$to" -t "$TF" -f csv -s > /tmp/xau_${year}.log 2>&1
    
    # Find the output file
    csv_file=$(find "$DATA_DIR" -name "${PAIR}-${TF}*${year}*" 2>/dev/null | head -1)
    
    if [ -f "$csv_file" ] && [ -s "$csv_file" ]; then
        size=$(du -h "$csv_file" | cut -f1)
        echo -n "✅ ${size} → conversion... "
        
        # Convert to .bars format
        python3 -c "
import csv, struct, sys, os
with open('${csv_file}') as f:
    reader = csv.DictReader(f)
    rows = list(reader)
with open('${bars_file}', 'wb') as f:
    for r in rows:
        ts = int(r['timestamp']) // 1000
        o = float(r['open']); h = float(r['high'])
        l = float(r['low']);  c = float(r['close'])
        v = int(float(r.get('volume', 0))) if r.get('volume', '').strip() else 0
        f.write(struct.pack('<qddddi', ts, o, h, l, c, v))
count = len(rows)
print(f'✅ {count} barres')
"
    else
        echo "❌ PAS DE DONNÉES"
    fi
    
    # Small delay to be gentle
    sleep 2
done

# Merge into one file
echo ""
echo "📦 Merge des fichiers .bars..."
python3 -c "
import struct, os, sys

bars_dir = '$BARS_DIR'
files = sorted([f for f in os.listdir(bars_dir) if f.startswith('XAU_USD_H1_') and f.endswith('.bars')])

total = 0
with open(os.path.join(bars_dir, 'XAU_USD_H1.bars'), 'wb') as out:
    for fname in files:
        path = os.path.join(bars_dir, fname)
        with open(path, 'rb') as f:
            data = f.read()
            count = len(data) // 44
            out.write(data)
            total += count
            print(f'  + {fname}: {count} barres')

# Create symlink
import pathlib
link = pathlib.Path(bars_dir) / 'XAU_USD_H1_H1.bars'
if link.exists() or link.is_symlink():
    link.unlink()
link.symlink_to('XAU_USD_H1.bars')

print(f'\n✅ Total: {total} barres')
print(f'✅ Fichier: {bars_dir}/XAU_USD_H1.bars ({total * 44} bytes)')
"

echo ""
echo "✅ XAU/USD download complete!"
ls -lh "$BARS_DIR"/XAU_USD_H1*.bars 2>/dev/null
