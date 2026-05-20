#!/bin/bash
# =============================================================================
# Batch Strategy Generator — StrategyQuant-style
# =============================================================================
# Usage:
#   ./scripts/batch-gen.sh                   # defaults: 500 strategies, all types
#   ./scripts/batch-gen.sh --count 1000      # 1000 strategies
#   ./scripts/batch-gen.sh --types trend     # only trend strategies
#   ./scripts/batch-gen.sh --output ./reports/  # custom output directory
#   ./scripts/batch-gen.sh --help            # show help
#
# Workflow:
#   1. mvn compile (with -q for quiet output)
#   2. Launch BatchStrategyRunner via Maven exec
#   3. Open ranking.html in browser
#
# Requires: Java 21+, Maven 3.6+, xdg-open (Linux) or open (macOS)
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── Colour helpers ──────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# ── Defaults ────────────────────────────────────────────────────────────────
COUNT=500
TYPES="all"
BARS=250
CAPITAL=100000
OUTPUT="${PROJECT_DIR}/batch-results"
THREADS=""

# Selection criteria mode (generate until criteria are met)
MIN_SHARPE=""
MIN_PF=""
MAX_DD=""
MIN_WIN_RATE=""
TARGET=""
MAX_ATTEMPTS=""
DATA=""

# ── Help ────────────────────────────────────────────────────────────────────
show_help() {
    cat <<EOF
${CYAN}Batch Strategy Generator${NC} — StrategyQuant-style

${YELLOW}Usage:${NC}
  ./scripts/batch-gen.sh [OPTIONS]

${YELLOW}Options (Standard Mode):${NC}
  --count N       Number of strategies to generate (default: 500)
  --types S       Strategy types: all|trend,meanrev,breakout,momentum (default: all)
  --bars N        Number of bars for backtest (default: 250)
  --capital N     Initial capital in USD (default: 100000)
  --output DIR    Output directory (default: ./batch-results/)
  --data PATH      Use real market data from CSV file (default: simulated)
  --threads N     Parallel threads (default: CPU count)

${YELLOW}Options (Selection Criteria Mode):${NC}
  Generate until X strategies pass ALL criteria, then rank & export.
  --min-sharpe S   Minimum Sharpe ratio (default: 1.0)
  --min-pf F       Minimum Profit Factor (default: 1.5)
  --max-dd D       Maximum drawdown %% (default: 25.0)
  --min-win-rate W Minimum win rate %% (default: 40.0)
  --target N       Stop after finding N good strategies (default: 10)
  --max-attempts N Max total attempts before giving up (default: 10000)

${YELLOW}Other:${NC}
  --no-open       Do not open the HTML report in browser
  --help          Show this help

${YELLOW}Examples:${NC}
  ./scripts/batch-gen.sh                                  # 500 strategies, all types
  ./scripts/batch-gen.sh --target 5 --min-sharpe 1.5      # find 5 good strategies with Sharpe≥1.5
  ./scripts/batch-gen.sh --target 10 --min-pf 2.0 --max-dd 20  # 10 strategies with PF≥2, DD≤20%
  ./scripts/batch-gen.sh --count 1000 --types trend       # 1000 trend strategies
  ./scripts/batch-gen.sh --count 50 --bars 100 --output ./quick-test/  # quick test

${YELLOW}Output:${NC}
  ranking.html          Interactive ranking dashboard with Chart.js
  ranking.json          Raw ranking data (for web/analysis)
  summary.txt           Text summary
  strategies/           Top 20 strategies as compilable Java files
EOF
    exit 0
}

# ── Parse arguments ─────────────────────────────────────────────────────────
OPEN_BROWSER=true

while [[ $# -gt 0 ]]; do
    case "$1" in
        --count)       COUNT="$2"; shift 2 ;;
        --types)       TYPES="$2"; shift 2 ;;
        --bars)        BARS="$2"; shift 2 ;;
        --capital)     CAPITAL="$2"; shift 2 ;;
        --output)      OUTPUT="$2"; shift 2 ;;
    --data)        DATA="$2"; shift 2 ;;
        --threads)     THREADS="--threads $2"; shift 2 ;;
        --min-sharpe)  MIN_SHARPE="--min-sharpe $2"; shift 2 ;;
        --min-pf)      MIN_PF="--min-pf $2"; shift 2 ;;
        --max-dd)      MAX_DD="--max-dd $2"; shift 2 ;;
        --min-win-rate) MIN_WIN_RATE="--min-win-rate $2"; shift 2 ;;
        --target)      TARGET="--target $2"; shift 2 ;;
        --max-attempts) MAX_ATTEMPTS="--max-attempts $2"; shift 2 ;;
        --no-open)     OPEN_BROWSER=false; shift ;;
        --help|-h)     show_help ;;
        *)             echo -e "${RED}Unknown option: $1${NC}"; show_help ;;
    esac
done

# ── Resolve paths ───────────────────────────────────────────────────────────
OUTPUT_DIR="$(realpath -m "$OUTPUT")"

# ── Banner ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║         BATCH STRATEGY GENERATOR                     ║${NC}"
echo -e "${CYAN}║         StrategyQuant-style Pipeline                 ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${YELLOW}  Count:${NC}     $COUNT"
echo -e "${YELLOW}  Types:${NC}     $TYPES"
echo -e "${YELLOW}  Bars:${NC}      $BARS"
echo -e "${YELLOW}  Capital:${NC}   \$${CAPITAL}"
if [ -n "$TARGET" ]; then
    echo -e "${YELLOW}  Target:${NC}     ${TARGET#--target } good strategies"
    echo -e "${YELLOW}  Criteria:${NC}   Sharpe≥${MIN_SHARPE#--min-sharpe } PF≥${MIN_PF#--min-pf } DD≤${MAX_DD#--max-dd }%"
fi
echo -e "${YELLOW}  Output:${NC}    $OUTPUT_DIR"
echo -e "${YELLOW}  Threads:${NC}   ${THREADS:-auto}"
echo ""

# ── Phase 1: Compile ────────────────────────────────────────────────────────
echo -e "${BLUE}[1/2] Compiling project...${NC}"
cd "$PROJECT_DIR"

if mvn compile -q -DskipTests; then
    echo -e "  ${GREEN}✓ Compilation successful${NC}"
else
    echo -e "  ${RED}✗ Compilation failed${NC}"
    exit 1
fi

# Build modern classpath with Maven
echo -e "${BLUE}[2/2] Running BatchStrategyRunner...${NC}"
echo ""

# Use mvn exec:java with the project's classpath
# We pass args via -Dexec.arguments which requires careful quoting
# Using exec:java with the fully configured classpath
MAVEN_ARGS=(
    "-pl" "trading-genetics"
    "-q"
    "exec:java"
    "-Dexec.mainClass=com.martinfou.trading.genetics.BatchStrategyRunner"
    "-Dexec.arguments=\"--count,$COUNT,--types,$TYPES,--bars,$BARS,--capital,$CAPITAL,--output,$OUTPUT_DIR,$THREADS\""
)

# Alternative: use java directly with the Maven-built classpath
# Build a proper classpath from all target/classes directories
CLASSPATH=""
for module in trading-core trading-backtest trading-genetics trading-strategies; do
    dir="$PROJECT_DIR/$module/target/classes"
    if [ -d "$dir" ]; then
        if [ -n "$CLASSPATH" ]; then
            CLASSPATH="$CLASSPATH:"
        fi
        CLASSPATH="$CLASSPATH$dir"
    fi
done

# Also add Maven dependency JARs (from local repository)
# Find all JARs that the project depends on
MAVEN_REPO="${HOME}/.m2/repository"
for dep in org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar \
           com/fathzer/javaluator/3.0.3/javaluator-3.0.3.jar; do
    jar="$MAVEN_REPO/$dep"
    if [ -f "$jar" ]; then
        CLASSPATH="$CLASSPATH:$jar"
    fi
done

# Find all dependencies from Maven's classpath file if it exists
CP_FILE="$PROJECT_DIR/trading-genetics/target/classpath.txt"
if [ -f "$CP_FILE" ]; then
    CLASSPATH="$CLASSPATH:$(cat "$CP_FILE")"
fi

# Run the batch runner
CMD_ARGS=""
CMD_ARGS="$CMD_ARGS --count $COUNT"
CMD_ARGS="$CMD_ARGS --types $TYPES"
CMD_ARGS="$CMD_ARGS --bars $BARS"
CMD_ARGS="$CMD_ARGS --capital $CAPITAL"
CMD_ARGS="$CMD_ARGS --output $OUTPUT_DIR"
if [ -n "$THREADS" ]; then
    CMD_ARGS="$CMD_ARGS $THREADS"
fi
if [ -n "$MIN_SHARPE" ]; then CMD_ARGS="$CMD_ARGS $MIN_SHARPE"; fi
if [ -n "$MIN_PF" ]; then CMD_ARGS="$CMD_ARGS $MIN_PF"; fi
if [ -n "$MAX_DD" ]; then CMD_ARGS="$CMD_ARGS $MAX_DD"; fi
if [ -n "$MIN_WIN_RATE" ]; then CMD_ARGS="$CMD_ARGS $MIN_WIN_RATE"; fi
if [ -n "$TARGET" ]; then CMD_ARGS="$CMD_ARGS $TARGET"; fi
if [ -n "$MAX_ATTEMPTS" ]; then CMD_ARGS="$CMD_ARGS $MAX_ATTEMPTS"; fi
if [ -n "$DATA" ]; then CMD_ARGS="$CMD_ARGS --data $DATA"; fi

set +e
java \
    -Xmx4g \
    -cp "$CLASSPATH" \
    com.martinfou.trading.genetics.BatchStrategyRunner \
    $CMD_ARGS

EXIT_CODE=$?
set -e

if [ $EXIT_CODE -ne 0 ]; then
    echo ""
    echo -e "${RED}✗ Batch generation failed (exit code: $EXIT_CODE)${NC}"
    echo "  Check the error output above for details."
    echo "  Tip: Try 'mvn compile' to ensure everything builds."
    exit $EXIT_CODE
fi

# ── Open in browser ─────────────────────────────────────────────────────────
HTML_FILE="$OUTPUT_DIR/ranking.html"

if [ "$OPEN_BROWSER" = true ] && [ -f "$HTML_FILE" ]; then
    echo ""
    echo -e "${GREEN}✓ Batch generation complete!${NC}"
    echo ""
    echo -e "  ${CYAN}📊 Opening report:${NC} $HTML_FILE"
    echo ""

    if command -v xdg-open &>/dev/null; then
        xdg-open "$HTML_FILE" &
    elif command -v open &>/dev/null; then
        open "$HTML_FILE" &
    elif command -v sensible-browser &>/dev/null; then
        sensible-browser "$HTML_FILE" &
    else
        echo -e "  ${YELLOW}Could not detect browser opener.${NC}"
        echo -e "  Open manually: ${CYAN}$HTML_FILE${NC}"
    fi
fi

# ── Final summary ───────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   OUTPUT FILES                                      ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${CYAN}📊${NC} ranking.html    → ${CYAN}$OUTPUT_DIR/ranking.html${NC}"
echo -e "  ${CYAN}📊${NC} ranking.json    → ${CYAN}$OUTPUT_DIR/ranking.json${NC}"
echo -e "  ${CYAN}📄${NC} summary.txt     → ${CYAN}$OUTPUT_DIR/summary.txt${NC}"
echo -e "  ${CYAN}📁${NC} strategies/     → ${CYAN}$OUTPUT_DIR/strategies/${NC}"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo -e "  - Open ranking.html in your browser to view the dashboard"
echo -e "  - Check summary.txt for top strategy details"
echo -e "  - Run a strategy: java -cp ... com.martinfou.trading.strategies.generated.Top1_*"
echo ""
