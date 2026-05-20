#!/bin/bash
# ============================================================================
# Data Download Manager — Gentle Batch Mode
# ============================================================================
# Stratégie "non agressive":
# - 1 paire à la fois (pas de parallélisation)
# - 3s de pause entre chaque paire
# - 10s de pause entre chaque année
# - Streaming direct vers fichier (pas de buffer mémoire)
# - Download H1 de nuit (01:30), jamais en journée
# - Download M1 le weekend uniquement
#
# Calendrier:
#   H1 (20 ans × 7 paires = ~15 min total):
#     1 an/soir × 7 paires × 30s = 3.5 min/nuit, 20 nuits
#
#   M1 (20 ans × 7 paires = ~5h total):
#     1 an/semaine × 7 paires × 6 min = ~42 min/session
#     M1: un mois à la fois (pause 5s entre mois)
#
# Usage:
#   ./scripts/download-data.sh --gentle          # 1 pair × 1 an (très doux)
#   ./scripts/download-data.sh --year 2015       # 7 pairs × 1 an (doux)
#   ./scripts/download-data.sh --monthly 2020    # 1 mois × 7 pairs (ultra doux)
#   ./scripts/download-data.sh --list            # état des lieux
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

# Rate limiting (seconds)
DELAY_BETWEEN_PAIRS=3
DELAY_BETWEEN_YEARS=10
DELAY_BETWEEN_MONTHS=5

mkdir -p "$DATA_DIR" "$BARS_DIR"

# ─── Parse Args ──────────────────────────────────────────────────────────

ACTION="auto" ; PAIR=""; YEAR=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --list|--status) ACTION="list" ;;
        --year)       ACTION="year";   YEAR="$2"; shift ;;
        --range)      ACTION="range";  RANGE="$2"; shift ;;
        --gentle)     ACTION="gentle" ;;
        --monthly)    ACTION="monthly"; YEAR="$2"; shift ;;
        --all)        ACTION="all" ;;
        --tf)         TIMEFRAME="$2"; shift ;;
        --pair)       PAIR="$2"; shift ;;
        -h|--help)    ACTION="help" ;;
        *) echo "Unknown: $1"; exit 1 ;;
    esac; shift
done

if [ "$ACTION" = "help" ]; then
    cat << HELP
${CYAN}Data Download Manager — Gentle${NC}

${YELLOW}Small batches (recommandé):${NC}
  --gentle          1 paire × 1 an (le plus doux, ~5s)
  --year YYYY       7 paires × 1 an (~30s)
  --monthly YYYY    1 mois × 7 paires M1 (~10s)

${YELLOW}Batch complet (occasionnel):${NC}
  --range YYYY-YYYY Plage d'années
  --all             2006-2025 complet

${YELLOW}Info:${NC}
  --list            État des téléchargements
  --tf h1|m1        Timeframe (défaut: h1)
  --pair eurusd     Télécharger une paire spécifique

${YELLOW}Rate limiting intégré:${NC}
  Pause entre paires:    ${DELAY_BETWEEN_PAIRS}s
  Pause entre années:    ${DELAY_BETWEEN_YEARS}s
  Pause entre mois:      ${DELAY_BETWEEN_MONTHS}s
HELP
    exit 0
fi

# ─── Pair name mapping ───────────────────────────────────────────────────

pair_to_sym() {
    case "$1" in
        eurusd) echo "EUR_USD" ;;
        gbpusd) echo "GBP_USD" ;;
        usdcad) echo "USD_CAD" ;;
        usdjpy) echo "USD_JPY" ;;
        audusd) echo "AUD_USD" ;;
        nzdusd) echo "NZD_USD" ;;
        usdchf) echo "USD_CHF" ;;
        *)      echo "$1" | tr '[:lower:]' '[:upper:]' ;;
    esac
}

# ─── Download helpers ────────────────────────────────────────────────────

download_one() {
    local pair=$1 tf=$2 from=$3 to=$4 year=$5
    local expected_file="${DATA_DIR}/${pair}-${tf}-bid-${from}-${to}.csv"

    if [ -f "$expected_file" ] && [ -s "$expected_file" ]; then
        echo -e "  ${pair}: ${GREEN}✅ déjà là${NC}"
        return 0
    fi

    echo -e "  ${pair}: ${CYAN}📥 downloading...${NC}"

    # Download with --silent to reduce stdout noise
    npx --yes dukascopy-node \
        -i "$pair" \
        -from "$from" \
        -to "$to" \
        -t "$tf" \
        -f csv \
        -dir "$DATA_DIR" \
        -s > /tmp/dukascopy_dl.log 2>&1

    if grep -q "saved" /tmp/dukascopy_dl.log; then
        local size=$(du -h "$expected_file" 2>/dev/null | cut -f1)
        echo -e "  ${pair}: ${GREEN}✅ ${size}${NC}"
        return 0
    else
        local err=$(head -3 /tmp/dukascopy_dl.log | tr '\n' ' ')
        echo -e "  ${pair}: ${RED}❌ ${err}${NC}"
        return 1
    fi
}

convert_to_barstore() {
    local pair=$1 tf=$2 from=$3 to=$4 year=$5
    local csv_file="${DATA_DIR}/${pair}-${tf}-bid-${from}-${to}.csv"
    local sym=$(pair_to_sym "$pair")

    if [ ! -f "$csv_file" ]; then
        return 0
    fi

    local out_file="${BARS_DIR}/${sym}_${tf^^}_${year}.bars"

    python3 -c "
import csv, struct, sys, os
count = 0
with open('${csv_file}') as f:
    reader = csv.DictReader(f)
    rows = list(reader)
with open('${out_file}', 'wb') as f:
    for r in rows:
        ts = int(r['timestamp']) // 1000
        o = float(r['open']); h = float(r['high'])
        l = float(r['low']);  c = float(r['close'])
        v = int(float(r.get('volume', 0))) if r.get('volume', '').strip() else 0
        f.write(struct.pack('<qddddi', ts, o, h, l, c, v))
        count += 1
    print(f'  Converted {count} bars')
"
}

# ─── Download a year, pair-by-pair, with delays ─────────────────────────

download_year() {
    local year=$1 tf=$2
    local from="${year}-01-01"
    local to="${year}-12-31"
    [ "$year" = "$(date +%Y)" ] && to="$(date +%Y-%m-%d)"

    echo -e "\n${CYAN}📥 ${tf^^} — ${year} (${from} → ${to})${NC}"
    echo "───────────────────────────────────"

    local pairs_to_dl=("${PAIRS[@]}")
    [ -n "$PAIR" ] && pairs_to_dl=("$PAIR")

    local i=0
    for pair in "${pairs_to_dl[@]}"; do
        download_one "$pair" "$tf" "$from" "$to" "$year"
        convert_to_barstore "$pair" "$tf" "$from" "$to" "$year"

        # Gentle delay between pairs (not after last)
        i=$((i + 1))
        if [ "$i" -lt "${#pairs_to_dl[@]}" ]; then
            echo -e "  ${YELLOW}⏳ pause ${DELAY_BETWEEN_PAIRS}s...${NC}"
            sleep "$DELAY_BETWEEN_PAIRS"
        fi
    done
}

# ─── Download a single month (for M1 granularity) ────────────────────────

download_month() {
    local year=$1 month=$2 tf=$3
    local from=$(printf "%04d-%02d-01" "$year" "$month")
    local to=$(printf "%04d-%02d-01" "$year" $((month + 1)))
    # Last month: compute last day
    if [ "$month" -eq 12 ]; then
        to="$((year + 1))-01-01"
    fi
    # Trim to hypen
    to=$(date -d "$to -1 day" +%Y-%m-%d 2>/dev/null || echo "$year-$(printf '%02d' $((month % 12 + 1)))-01")
    # Use end of month
    if [ "$month" -eq 12 ]; then
        to="${year}-12-31"
    else
        to=$(date -d "$(printf '%04d-%02d-01' "$year" $((month + 1))) -1 day" +%Y-%m-%d 2>/dev/null || echo "$(printf '%04d-%02d-28' "$year" $month)")
    fi

    echo -e "\n${CYAN}📥 ${tf^^} — ${year}-$(printf '%02d' $month) (${from} → ${to})${NC}"

    local pairs_to_dl=("${PAIRS[@]}")
    [ -n "$PAIR" ] && pairs_to_dl=("$PAIR")

    local i=0
    for pair in "${pairs_to_dl[@]}"; do
        download_one "$pair" "$tf" "$from" "$to" "$year-$month"
        convert_to_barstore "$pair" "$tf" "$from" "$to" "$year-$month"

        i=$((i + 1))
        if [ "$i" -lt "${#pairs_to_dl[@]}" ]; then
            sleep "$DELAY_BETWEEN_MONTHS"
        fi
    done
}

# ─── Status ──────────────────────────────────────────────────────────────

if [ "$ACTION" = "list" ]; then
    echo -e "${CYAN}📊 Data Status — ${TIMEFRAME^^}${NC}"
    echo "═══════════════════════════════════════"
    local_count=0
    for year in $(seq $START_YEAR $END_YEAR); do
        dl=0
        for pair in "${PAIRS[@]}"; do
            f=$(find "$DATA_DIR" -name "${pair}-${TIMEFRAME}*${year}*" 2>/dev/null | head -1)
            [ -n "$f" ] && dl=$((dl + 1))
        done
        total="${#PAIRS[@]}"
        pct=$((dl * 100 / total))
        if [ "$pct" = "100" ]; then
            echo -e "  ${GREEN}✅${NC} ${year}: ${dl}/${total} (${pct}%)"
            local_count=$((local_count + 1))
        elif [ "$dl" -gt 0 ]; then
            echo -e "  ${YELLOW}⚠️${NC} ${year}: ${dl}/${total} (${pct}%)"
        else
            echo -e "  ${RED}❌${NC} ${year}: 0/${total}"
        fi
    done
    echo "═══════════════════════════════════════"
    total_years=$((END_YEAR - START_YEAR + 1))
    echo -e "  ${GREEN}${local_count}/${total_years} years complete${NC}"
    exit 0
fi

# ─── Actions ─────────────────────────────────────────────────────────────

case "$ACTION" in
    gentle)
        # Find first missing pair for the most recent missing year
        if [ -z "$YEAR" ]; then
            for y in $(seq $END_YEAR -1 $START_YEAR); do
                for pair in "${PAIRS[@]}"; do
                    f=$(find "$DATA_DIR" -name "${pair}-${TIMEFRAME}*${y}*" 2>/dev/null | head -1)
                    if [ -z "$f" ]; then
                        YEAR=$y; PAIR=$pair
                        break 2
                    fi
                done
            done
        fi
        if [ -z "$YEAR" ]; then
            echo -e "${GREEN}✅ All years downloaded!${NC}"
            exit 0
        fi
        echo -e "${YELLOW}Gentle download: ${PAIR} ${TIMEFRAME} ${YEAR}${NC}"
        download_year "$YEAR" "$TIMEFRAME"
        ;;

    year)
        download_year "$YEAR" "$TIMEFRAME"
        ;;

    monthly)
        if [ -z "$YEAR" ]; then
            echo "Usage: --monthly YYYY"
            exit 1
        fi
        for m in $(seq 1 12); do
            download_month "$YEAR" "$m" "$TIMEFRAME"
            sleep "$DELAY_BETWEEN_YEARS"
        done
        ;;

    range)
        start="${RANGE%%-*}"; end="${RANGE##*-}"
        for y in $(seq "$start" "$end"); do
            download_year "$y" "$TIMEFRAME"
            echo -e "${YELLOW}⏳ pause ${DELAY_BETWEEN_YEARS}s entre années...${NC}"
            sleep "$DELAY_BETWEEN_YEARS"
        done
        ;;

    all)
        for y in $(seq $START_YEAR $END_YEAR); do
            # Check if already fully downloaded
            dl=0
            for pair in "${PAIRS[@]}"; do
                f=$(find "$DATA_DIR" -name "${pair}-${TIMEFRAME}*${y}*" 2>/dev/null | head -1)
                [ -n "$f" ] && dl=$((dl + 1))
            done
            [ "$dl" = "${#PAIRS[@]}" ] && echo -e "  ${year}: ${GREEN}✅ déjà complet${NC}" && continue

            download_year "$y" "$TIMEFRAME"
            echo -e "  ${YELLOW}⏳ Fin année ${y} — pause ${DELAY_BETWEEN_YEARS}s...${NC}"
            sleep "$DELAY_BETWEEN_YEARS"
        done
        ;;

    auto)
        # Auto-detect next missing year (from oldest upward to backfill quickly)
        for y in $(seq $START_YEAR $END_YEAR); do
            dl=0
            for pair in "${PAIRS[@]}"; do
                f=$(find "$DATA_DIR" -name "${pair}-${TIMEFRAME}*${y}*" 2>/dev/null | head -1)
                [ -n "$f" ] && dl=$((dl + 1))
            done
            if [ "$dl" = "0" ]; then
                echo -e "${YELLOW}📋 Next missing: ${y}${NC}"
                download_year "$y" "$TIMEFRAME"
                echo -e "${GREEN}✅ ${y} done${NC}"
                exit 0
            fi
        done
        echo -e "${GREEN}✅ All years downloaded!${NC}"
        ;;
esac

# ─── Summary ─────────────────────────────────────────────────────────────

echo ""
echo -e "${CYAN}📊 Summary${NC}"
csv_files=$(find "$DATA_DIR" -name "*.csv" | wc -l)
bars_files=0
bars_size=0
for f in "$BARS_DIR"/*.bars; do
    [ -f "$f" ] && bars_files=$((bars_files + 1)) && bars_size=$((bars_size + $(stat -c%s "$f")))
done
echo "  CSV files:  $csv_files (raw Dukascopy)"
echo -e "  BarStore:   ${bars_files} files, ${GREEN}$(numfmt --to=iec $bars_size 2>/dev/null)${NC}"
echo ""
