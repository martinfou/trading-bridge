#!/bin/bash
# =============================================================================
# XAU/USD — Download manquant 2009-2024, année par année
# =============================================================================
set -euo pipefail
cd /home/martinfou/projects/trading-bridge

DATA_DIR="./data/historical/dukascopy"
BARS_DIR="./data/historical/bars"
mkdir -p "$DATA_DIR" "$BARS_DIR"

for year in $(seq 2009 2024); do
  bars_file="${BARS_DIR}/XAU_USD_H1_${year}.bars"
  csv_file=$(find "$DATA_DIR" -name "xauusd-h1-bid-${year}*" 2>/dev/null | head -1)
  
  if [ -f "$bars_file" ] && [ -s "$bars_file" ]; then
    bars=$(python3 -c "import os; print(os.path.getsize('$bars_file') // 44)")
    echo "✅ ${year}: déjà converti (${bars} barres)"
    continue
  fi

  echo -n "📥 ${year}: téléchargement... "
  
  npx dukascopy-node -i xauusd -from ${year}-01-01 -to ${year}-12-31 -t h1 -f csv -s > /tmp/xau_${year}.log 2>&1
  
  # Find the downloaded CSV
  csv_file=$(find "$DATA_DIR" -name "xauusd-h1-bid-${year}*" -newer /tmp/xau_${year}.log 2>/dev/null | head -1)
  
  if [ -f "$csv_file" ] && [ -s "$csv_file" ]; then
    size=$(du -h "$csv_file" | cut -f1)
    lines=$(wc -l < "$csv_file")
    echo -n "✅ ${size} (${lines} lignes) → conversion... "
    
    python3 -c "
import csv, struct
with open('${csv_file}') as f:
    reader = csv.DictReader(f)
    rows = list(reader)
cnt = len(rows)
with open('${bars_file}', 'wb') as f:
    for r in rows:
        ts = int(r['timestamp']) // 1000
        o = float(r['open']); h = float(r['high'])
        l = float(r['low']);  c = float(r['close'])
        v = int(float(r.get('volume', 0))) if r.get('volume', '').strip() else 0
        f.write(struct.pack('<qddddi', ts, o, h, l, c, v))
print(f'✅ {cnt} barres')
"
  else
    echo "❌ ÉCHEC (fichier vide ou absent)"
  fi
  
  sleep 3
done

# Re-merge
echo ""
echo "📦 Re-merge XAU_USD_H1.bars..."
python3 -c "
import struct, os
bars_dir = '${BARS_DIR}'
files = sorted([f for f in os.listdir(bars_dir) if f.startswith('XAU_USD_H1_') and f.endswith('.bars')])
total = 0
with open(os.path.join(bars_dir, 'XAU_USD_H1.bars'), 'wb') as out:
    for fname in files:
        path = os.path.join(bars_dir, fname)
        data = open(path, 'rb').read()
        cnt = len(data) // 44
        out.write(data)
        total += cnt
        print(f'  + {fname}: {cnt} barres')
import pathlib
link = pathlib.Path(bars_dir) / 'XAU_USD_H1_H1.bars'
if link.exists() or link.is_symlink():
    link.unlink()
link.symlink_to('XAU_USD_H1.bars')
size_mb = total * 44 / 1024 / 1024
print(f'\\n✅ Total: {total} barres ({size_mb:.1f} MB)')
" 2>&1

echo ""
ls -lh "${BARS_DIR}"/XAU_USD_H1*.bars 2>/dev/null
