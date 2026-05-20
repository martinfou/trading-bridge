#!/bin/bash
# ============================================================================
# Data Download Manager — Dukascopy Historical Data
# ============================================================================
# Usage:
#   ./scripts/download-data.sh                   # Auto: next missing year
#   ./scripts/download-data.sh --list             # Show what's downloaded
#   ./scripts/download-data.sh --year 2018        # Download specific year
#   ./scripts/download-data.sh --range 2015-2020  # Download range of years
#   ./scripts/download-data.sh --tf h1            # Timeframe: h1 or m1
#   ./scripts/download-data.sh --all              # Download MISSING years only
#
# Batch schedule (small batches):
#   Daily 01:00 → 1 year × 7 pairs H1  (≈ 7 min)
#   Weekly Sat 01:00 → 1 year M1        (≈ 42 min)
#
# $ ./scripts/download-data.sh --status
#   H1: 2025 (downloaded) | 2024-01-01..05-19 (partial) | 2006-2023 (missing)
#   M1: none
# ============================================================================

set -euo pipefail
cd "$(dirname "$0")/.."

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

DATA_DIR="./data/historical/dukascopy"
BARS_DIR="./data/historical/bars"
START_YEAR=2006
END_YEAR=2025
PAIRS=("eurusd" "gbpusd" "usdcad" "usdjpy" "audusd" "nzdusd" "usdchf")
TIMEFRAME="h1"

# ─── Parse Args ──────────────────────────────────────────────────────────

ACTION="auto"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --list|--status) ACTION="list" ;;
        --year)          ACTION="year"; YEAR="$2"; shift ;;
        --range)         ACTION="range"; RANGE="$2"; shift ;;
        --tf)            TIMEFRAME="$2"; shift ;;
        --all)           ACTION="all" ;;
        -h|--help)       ACTION="help" ;;
        *)               echo "Unknown: $1"; exit 1 ;;
    esac
    shift
done

# ─── Help ────────────────────────────────────────────────────────────────

if [ "$ACTION" = "help" ]; then
    echo -e "${CYAN}Data Download Manager${NC}"
    echo ""
    echo "Usage: $0 [OPTIONS]"
    echo "  --list|--status   Show download status per year"
    echo "  --year YYYY       Download specific year"
    echo "  --range YYYY-YYYY Download range of years"
    echo "  --all             Download ALL missing years (big batch)"
    echo "  --tf h1|m1        Timeframe (default: h1)"
    echo ""
    echo "Years: $START_YEAR-$END_YEAR"
    echo "Pairs: ${PAIRS[*]}"
    echo ""
    echo "Estimated time per year (H1, 7 pairs):  ~7 min"
    echo "Estimated time per year (M1, 7 pairs): ~42 min"
    exit 0
fi

# ─── Status ──────────────────────────────────────────────────────────────

if [ "$ACTION" = "list" ]; then
    echo -e "${CYAN}📊 Download Status — ${TIMEFRAME^^}${NC}"
    echo "───────────────────────────────────────────────"
    for year in $(seq $START_YEAR $END_YEAR); do
        downloaded=0
        total=${#PAIRS[@]}
        for pair in "${PAIRS[@]}"; do
            # Check if any file exists for this pair+year
            count=$(find "$DATA_DIR" -name "${pair}-${TIMEFRAME}*${year}*" 2>/dev/null | wc -l)
            if [ "$count" -gt 0 ]; then
                downloaded=$((downloaded + 1))
            fi
        done
        if [ "$downloaded" = "$total" ]; then
            echo -e "  ${year}: ${GREEN}✅ ${downloaded}/${total} pairs${NC}"
        elif [ "$downloaded" -gt 0 ]; then
            echo -e "  ${year}: ${YELLOW}⚠️  ${downloaded}/${total} pairs${NC}"
        else
            echo -e "  ${year}: ${RED}❌ 0/${total} pairs${NC}"
        fi
    done
    exit 0
fi

# ─── Download one year ───────────────────────────────────────────────────

download_year() {
    local year=$1
    local tf=$2
    local from="${year}-01-01"
    local to="${year}-12-31"
    
    if [ "$year" = "$(date +%Y)" ]; then
        to="$(date +%Y-%m-%d)"
    fi
    
    echo -e "${CYAN}📥 Downloading ${year} (${tf^^}) — ${from} → ${to}${NC}"
    echo "───────────────────────────────────────────────"
    
    mkdir -p "$DATA_DIR"
    
    for pair in "${PAIRS[@]}"; do
        echo -n "  ${pair}... "
        
        # Check if already downloaded (exact year file)
        expected_file="${DATA_DIR}/${pair}-${tf}-bid-${from}-${to}.csv"
        if [ -f "$expected_file" ] && [ -s "$expected_file" ]; then
            echo -e "${GREEN}✅ already exists${NC}"
            continue
        fi
        
        # Download
        local output
        output=$(npx --yes dukascopy-node \
            -i "$pair" \
            -from "$from" \
            -to "$to" \
            -t "$tf" \
            -f csv \
            -dir "$DATA_DIR" 2>&1)
        if echo "$output" | grep -q "saved"; then
            echo -e "${GREEN}✅${NC}"
        else
            echo -e "${RED}❌${NC} $output" | head -1
        fi
    done
    
    echo ""
}

# ─── Convert to BarStore ─────────────────────────────────────────────────

convert_to_bars() {
    local year=$1
    local tf=$2
    echo -e "${CYAN}🔄 Converting ${year} CSV → BarStore binary${NC}"
    
    mkdir -p "$BARS_DIR"
    
    for pair in "${PAIRS[@]}"; do
        # Find CSV file for this pair and year
        local csv_file
        csv_file=$(find "$DATA_DIR" -name "${pair}-${tf}-bid-${year}-*.csv" 2>/dev/null | head -1)
        if [ -z "$csv_file" ]; then
            echo -e "${YELLOW}⚠️  no CSV found${NC}"
            continue
        fi
        
        echo -n "  ${pair}... "
        
        # Map pair names
        case "$pair" in
            eurusd) sym="EUR_USD" ;;
            gbpusd) sym="GBP_USD" ;;
            usdcad) sym="USD_CAD" ;;
            usdjpy) sym="USD_JPY" ;;
            audusd) sym="AUD_USD" ;;
            nzdusd) sym="NZD_USD" ;;
            usdchf) sym="USD_CHF" ;;
        esac
        
        out_file="${BARS_DIR}/${sym}_${tf^^}_${year}.bars"
        
        # Python one-liner to convert CSV to binary
        # Convert CSV to binary using Python
        python3 -c "
import csv, struct, sys, os
from datetime import datetime, timezone

with open('${csv_file}') as f:
    reader = csv.DictReader(f)
    rows = list(reader)

with open('$out_file', 'wb') as f:
    for r in rows:
        ts = int(r['timestamp']) // 1000
        o = float(r['open'])
        h = float(r['high'])
        l = float(r['low'])
        c = float(r['close'])
        v = 0 if r.get('volume') == '' else int(float(r.get('volume', 0)))
        f.write(struct.pack('<qddddi', ts, o, h, l, c, v))

print(f'{len(rows)} bars')
"
    done
}

# ─── Actions ─────────────────────────────────────────────────────────────

case "$ACTION" in
    year)
        download_year "$YEAR" "$TIMEFRAME"
        convert_to_bars "$YEAR" "$TIMEFRAME"
        ;;
    range)
        start="${RANGE%%-*}"
        end="${RANGE##*-}"
        for y in $(seq "$start" "$end"); do
            download_year "$y" "$TIMEFRAME"
            convert_to_bars "$y" "$TIMEFRAME"
        done
        ;;
    all)
        for y in $(seq $START_YEAR $END_YEAR); do
            download_year "$y" "$TIMEFRAME"
            convert_to_bars "$y" "$TIMEFRAME"
        done
        ;;
    auto)
        # Find the next missing year (from most recent backward)
        for y in $(seq $END_YEAR -1 $START_YEAR); do
            missing=0
            for pair in "${PAIRS[@]}"; do
                count=$(find "$DATA_DIR" -name "${pair}-${TIMEFRAME}*${y}*" 2>/dev/null | wc -l)
                [ "$count" -eq 0 ] && missing=1 && break
            done
            if [ "$missing" -eq 1 ]; then
                echo -e "${YELLOW}📋 Next missing year: ${y}${NC}"
                download_year "$y" "$TIMEFRAME"
                convert_to_bars "$y" "$TIMEFRAME"
                exit 0
            fi
        done
        echo -e "${GREEN}✅ All years downloaded!${NC}"
        ;;
esac

# ─── Summary ─────────────────────────────────────────────────────────────

total_bars=$(find "$DATA_DIR" -name "*.csv" | wc -l)
bars_files=$(find "$BARS_DIR" -name "*.bars" -exec stat -c%s {} \; 2>/dev/null | paste -sd+ | bc || echo 0)
echo ""
echo -e "${CYAN}📊 Summary:${NC}"
echo "  CSV files:  $total_bars"
echo -e "  BarStore size:  ${GREEN}$(numfmt --to=iec $bars_files 2>/dev/null || echo "${bars_files}B")${NC}"
echo ""
echo -e "  Next run: ${YELLOW}./scripts/download-data.sh${NC}"
echo ""
