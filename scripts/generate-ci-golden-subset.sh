#!/usr/bin/env bash
# Regenerate data/ci/EUR_USD_H1_subset.csv from local Dukascopy 2012 CSV (January H1 = 744 bars).
# Requires: data/historical/dukascopy/eurusd-h1-bid-2012-01-01-2012-12-31.csv
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/data/historical/dukascopy/eurusd-h1-bid-2012-01-01-2012-12-31.csv"
OUT="$ROOT/data/ci/EUR_USD_H1_subset.csv"
if [[ ! -f "$SRC" ]]; then
  echo "Missing source: $SRC" >&2
  echo "Run: ./scripts/download-data.sh --pair EUR_USD --tf h1 --years 2012" >&2
  exit 1
fi
mkdir -p "$ROOT/data/ci"
{
  echo "timestamp,open,high,low,close,volume"
  tail -n +2 "$SRC" | head -744 | sed 's/$/,0/'
} > "$OUT"
echo "Wrote $(wc -l < "$OUT" | tr -d ' ') lines to $OUT"
echo "Re-capture baseline: mvn exec:java -pl trading-examples -Dexec.mainClass=com.martinfou.trading.examples.RunBacktest -Dexec.args='LondonOpenRangeBreakout data/ci/EUR_USD_H1_subset.csv EUR_USD --json'"
