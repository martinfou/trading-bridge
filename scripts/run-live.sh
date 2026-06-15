#!/bin/bash
# 🚀 Trading Bridge — Run strategies live on OANDA Practice
# Usage: ./scripts/run-live.sh [strategy-name] [granularity] [interval-sec]
#
# Examples:
#   ./scripts/run-live.sh 2_31_177          → Run Strategy_2_31_177_Converted
#   ./scripts/run-live.sh 2_14_147          → Run Strategy_2_14_147_Adapted
#   ./scripts/run-live.sh all               → Run ALL sqimported strategies
#   ./scripts/run-live.sh 2_31_177 H1 60    → Custom granularity/interval
#
# Available strategies: 2_14_147, 2_15_195, 2_31_175, 2_31_177,
#                       2_32_120, 2_36_190, 2_38_112

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# ────────────────────────────────────────────
# Defaults
# ────────────────────────────────────────────
STRATEGY="${1:-2_31_177}"
GRANULARITY="${2:-H1}"
INTERVAL_SEC="${3:-60}"

# ────────────────────────────────────────────
# Source OANDA credentials from .env
# ────────────────────────────────────────────
ENV_FILE="$HOME/projects/trading-dashboard/.env"
if [ ! -f "$ENV_FILE" ] && [ -f "$SCRIPT_DIR/.env" ]; then
    ENV_FILE="$SCRIPT_DIR/.env"
fi

if [ -f "$ENV_FILE" ]; then
    # shellcheck source=/dev/null
    source <(grep -E '^OANDA_' "$ENV_FILE" | sed 's/ //g' 2>/dev/null)
else
    echo -e "${RED}❌ .env file not found at $ENV_FILE or $SCRIPT_DIR/.env${NC}"
    echo "Create it with:"
    echo "  OANDA_API_KEY=your_key"
    echo "  OANDA_ACCOUNT_ID=101-002-4729622-008"
    exit 1
fi

if [ -z "$OANDA_API_KEY" ] || [ -z "$OANDA_ACCOUNT_ID" ]; then
    echo -e "${RED}❌ OANDA_API_KEY or OANDA_ACCOUNT_ID not set in $ENV_FILE${NC}"
    exit 1
fi

# ────────────────────────────────────────────
# Header
# ────────────────────────────────────────────
echo -e "${CYAN}╔═══════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   🚀 Trading Bridge — Live Strategy Runner       ║${NC}"
echo -e "${CYAN}║   OANDA Practice (demo)                          ║${NC}"
echo -e "${CYAN}╚═══════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${YELLOW}Strategy:${NC}    $STRATEGY"
echo -e "  ${YELLOW}Granularity:${NC} $GRANULARITY"
echo -e "  ${YELLOW}Interval:${NC}    ${INTERVAL_SEC}s"
echo -e "  ${YELLOW}Account:${NC}     $OANDA_ACCOUNT_ID"
echo -e "  ${YELLOW}API:${NC}         api-fxpractice.oanda.com"
echo ""

# ────────────────────────────────────────────
# Validate strategy
# ────────────────────────────────────────────
VALID_STRATEGIES="2_14_147 2_15_195 2_31_175 2_31_177 2_32_120 2_36_190 2_38_112 all"

if [ "$STRATEGY" != "all" ]; then
    found=false
    for s in $VALID_STRATEGIES; do
        [ "$s" = "$STRATEGY" ] && found=true && break
    done
    if [ "$found" = false ]; then
        echo -e "${RED}❌ Unknown strategy: $STRATEGY${NC}"
        echo -e "${YELLOW}Available:${NC}"
        for s in $VALID_STRATEGIES; do
            echo "  $s"
        done
        exit 1
    fi
fi

# ────────────────────────────────────────────
# Compile if needed
# ────────────────────────────────────────────
echo -e "${YELLOW}[1/3] Compilation...${NC}"
cd "$SCRIPT_DIR"
mvn compile -q -pl trading-core,trading-data,trading-strategies -am 2>&1 | grep -v "^$" || true
if [ $? -ne 0 ] && [ $? -ne 130 ]; then
    echo -e "${RED}❌ Compilation failed${NC}"
    exit 1
fi
echo -e "${GREEN}  ✅ Compilation OK${NC}"

# ────────────────────────────────────────────
# Resolve classpath
# ────────────────────────────────────────────
CP=$(find "$SCRIPT_DIR" -path "*/target/classes" -type d | tr '\n' ':')
# Add local Maven repo for dependencies
CP="$CP$(find "$HOME/.m2/repository" -name "*.jar" 2>/dev/null | \
    grep -E "(jackson|slf4j)" | tr '\n' ':')"

# Fallback: use Maven to build the classpath
if [ -z "$CP" ] || [ "$CP" = ":" ]; then
    echo -e "${YELLOW}Building classpath via Maven...${NC}"
    CP=$(mvn dependency:build-classpath -pl trading-strategies -q -DincludeScope=compile -Dmdep.outputFile=/dev/stdout 2>/dev/null || true)
    if [ -n "$CP" ]; then
        CP="$SCRIPT_DIR/trading-core/target/classes:$SCRIPT_DIR/trading-data/target/classes:$SCRIPT_DIR/trading-strategies/target/classes:$CP"
    fi
fi

# ────────────────────────────────────────────
# Run
# ────────────────────────────────────────────
echo -e "${YELLOW}[2/3] Starting LiveStrategyRunner...${NC}"
echo ""

# Trap Ctrl+C for clean shutdown
cleanup() {
    echo ""
    echo -e "${YELLOW}🛑 Shutting down gracefully...${NC}"
    kill $PID 2>/dev/null || true
    wait $PID 2>/dev/null || true
    echo -e "${GREEN}✅ Stopped.${NC}"
    exit 0
}
trap cleanup SIGINT SIGTERM

java -cp "$CP" \
    -Dorg.slf4j.simpleLogger.log.com.martinfou.trading=info \
    -Dorg.slf4j.simpleLogger.showDateTime=true \
    -Dorg.slf4j.simpleLogger.dateTimeFormat="yyyy-MM-dd HH:mm:ss" \
    com.martinfou.trading.strategies.LiveStrategyRunner \
    "$OANDA_API_KEY" "$OANDA_ACCOUNT_ID" "$STRATEGY" "$GRANULARITY" "$INTERVAL_SEC" &

PID=$!
wait $PID

echo -e "${GREEN}✅ Done${NC}"
