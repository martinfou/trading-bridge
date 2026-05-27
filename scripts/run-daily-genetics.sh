#!/bin/bash
# =============================================================================
# run-daily-genetics.sh — Daily Genetic Strategy Generator
# =============================================================================
# Runs the genetic algorithm daily until it finds at least 1 profitable strategy
# (Sharpe ≥ 1.0, PF ≥ 1.5, DD ≤ 25%, WR ≥ 40%).
#
# If found: exports top strategies + sends notification
# If not found: logs failure, tries again next day
#
# Install as cron:
#   0 8 * * * /home/martinfou/projects/trading-bridge/scripts/run-daily-genetics.sh
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── Config ──────────────────────────────────────────────────────────────────
DATE_STAMP=$(date +%Y-%m-%d)
BATCH_SIZE="${BATCH_SIZE:-200}"
TYPES="${TYPES:-all}"
CAPITAL="${CAPITAL:-50000}"
BARS="${BARS:-500}"
OUTPUT_DIR="${PROJECT_DIR}/genetic-results/${DATE_STAMP}"
LOG_DIR="${PROJECT_DIR}/genetic-results/logs"
mkdir -p "$OUTPUT_DIR" "$LOG_DIR"

LOG_FILE="${LOG_DIR}/genetics-${DATE_STAMP}.log"
RESULT_FILE="${OUTPUT_DIR}/promotion-ready.txt"
TELEGRAM_NOTIFY="${NOTIFY_TELEGRAM:-false}"

# Selection Criteria (defaults = "profitable")
MIN_SHARPE="${MIN_SHARPE:-1.0}"
MIN_PF="${MIN_PF:-1.5}"
MAX_DD="${MAX_DD:-25.0}"
MIN_WIN_RATE="${MIN_WIN_RATE:-40.0}"
TARGET="${TARGET:-1}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-10000}"

exec > >(tee -a "$LOG_FILE") 2>&1

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
error() { echo "[ERROR] $*"; }

# ── Banner ──────────────────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║      DAILY GENETIC STRATEGY GENERATOR                ║"
echo "║      Date: $DATE_STAMP                ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

log "Parameters: batch=$BATCH_SIZE types=$TYPES bars=$BARS capital=\$${CAPITAL}"
log "Criteria: Sharpe≥${MIN_SHARPE} PF≥${MIN_PF} DD≤${MAX_DD}% WR≥${MIN_WIN_RATE}%"
log "Target: $TARGET good strategy(ies) | Max attempts: $MAX_ATTEMPTS"
log "Output: $OUTPUT_DIR"
echo ""

# ── Step 1: Compile ─────────────────────────────────────────────────────────
log "[1/3] Compiling Maven project..."
cd "$PROJECT_DIR"

if ! mvn compile -q -DskipTests >> "$LOG_FILE" 2>&1; then
    error "Compilation failed!"
    exit 1
fi
log "✅ Compilation OK"

# ── Step 2: Run genetic generator ──────────────────────────────────────────
log "[2/3] Running genetic strategy generator (target=$TARGET)..."
echo ""

START_TIME=$(date +%s)

# Use batch-gen.sh with selection criteria mode
$SCRIPT_DIR/batch-gen.sh \
    --count "$BATCH_SIZE" \
    --types "$TYPES" \
    --bars "$BARS" \
    --capital "$CAPITAL" \
    --output "$OUTPUT_DIR" \
    --min-sharpe "$MIN_SHARPE" \
    --min-pf "$MIN_PF" \
    --max-dd "$MAX_DD" \
    --min-win-rate "$MIN_WIN_RATE" \
    --target "$TARGET" \
    --max-attempts "$MAX_ATTEMPTS" \
    --no-open

EXIT_CODE=$?
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
DURATION_MIN=$((DURATION / 60))
DURATION_SEC=$((DURATION % 60))

log "⏱️  Duration: ${DURATION_MIN}m ${DURATION_SEC}s (exit: $EXIT_CODE)"

# ── Step 3: Check results ──────────────────────────────────────────────────
log "[3/3] Analyzing results..."

if [ ! -f "$OUTPUT_DIR/summary.txt" ]; then
    log "❌ No summary file found — generation may have failed"
    exit 1
fi

# Extract key metrics
TOTAL_STRATS=$(grep -oP 'Total:\s*\K\d+' "$OUTPUT_DIR/summary.txt" || echo "0")
PROMISING=$(grep -oP 'Promising:\s*\K\d+' "$OUTPUT_DIR/summary.txt" || echo "0")
MEDIUM=$(grep -oP 'Medium:\s*\K\d+' "$OUTPUT_DIR/summary.txt" || echo "0")

# Check if any strategy met the criteria
GOOD_COUNT=$(grep -c 'passedSelection=true' "$LOG_FILE" 2>/dev/null || echo "0")

# Look at top strategy from summary
TOP_STRAT=""
TOP_SHARPE=""
TOP_SCORE=""
if [[ -f "$OUTPUT_DIR/summary.txt" ]]; then
    TOP_LINE=$(grep -m1 '^1 ' "$OUTPUT_DIR/summary.txt" 2>/dev/null || echo "")
    if [ -n "$TOP_LINE" ]; then
        TOP_STRAT=$(echo "$TOP_LINE" | awk '{print $2}')
        TOP_SHARPE=$(echo "$TOP_LINE" | awk '{print $3}')
        TOP_SCORE=$(echo "$TOP_LINE" | awk '{print $NF}')
    fi
fi

log ""
log "═══════════════════════════════════════════"
log "RESULTS:"
log "  Total strategies generated: $TOTAL_STRATS"
log "  Promising (score≥70):       $PROMISING"
log "  Medium (score 40-70):       $MEDIUM"
log "  Strategies passing criteria:$GOOD_COUNT"
log "  Top strategy:               $TOP_STRAT"
log "  Top Sharpe:                 $TOP_SHARPE"
log "  Top Score:                  $TOP_SCORE"
log "═══════════════════════════════════════════"
echo ""

# Check if we found at least one good strategy
if [ -f "$OUTPUT_DIR/strategies/Top1_*.java" ] || [ "$PROMISING" -gt 0 ] 2>/dev/null; then
    log "🎉 SUCCESS: At least one profitable strategy found!"

    # Write promotion-ready indicator
    echo "DATE=$DATE_STAMP" > "$RESULT_FILE"
    echo "FOUND=true" >> "$RESULT_FILE"
    echo "TOP_STRAT=${TOP_STRAT:-unknown}" >> "$RESULT_FILE"
    echo "TOP_SHARPE=${TOP_SHARPE:-0}" >> "$RESULT_FILE"
    echo "TOP_SCORE=${TOP_SCORE:-0}" >> "$RESULT_FILE"
    echo "OUTPUT_DIR=$OUTPUT_DIR" >> "$RESULT_FILE"
    echo "STRATEGIES=$TOTAL_STRATS" >> "$RESULT_FILE"
    echo "PROMISING=$PROMISING" >> "$RESULT_FILE"

    log "✅ Promotion-ready file written to: $RESULT_FILE"

    # Copy top strategies to a monitored folder
    PROMO_DIR="${PROJECT_DIR}/deploy/weekly-plans/genetic-${DATE_STAMP}"
    mkdir -p "$PROMO_DIR"
    if compgen -G "$OUTPUT_DIR/strategies/Top*.java" > /dev/null; then
        cp "$OUTPUT_DIR"/strategies/Top*.java "$PROMO_DIR/"
        log "📁 Top strategies copied to: $PROMO_DIR"
    fi

    log ""
    log "📋 NEXT STEPS:"
    log "  1. Review strategies: cat $OUTPUT_DIR/summary.txt"
    log "  2. Open dashboard:   $OUTPUT_DIR/ranking.html"
    log "  3. Promote manually or via cron-promote"
    log ""

    exit 0
else
    log "😔 No profitable strategy found today."
    log "    Generated $TOTAL_STRATS strategies, but none passed all criteria."
    log "    Will try again tomorrow with fresh random seeds."

    echo "DATE=$DATE_STAMP" > "$RESULT_FILE"
    echo "FOUND=false" >> "$RESULT_FILE"
    echo "STRATEGIES=$TOTAL_STRATS" >> "$RESULT_FILE"
    echo "TOP_SCORE=${TOP_SCORE:-0}" >> "$RESULT_FILE"
    echo "TOP_SHARPE=${TOP_SHARPE:-0}" >> "$RESULT_FILE"

    exit 0
fi
