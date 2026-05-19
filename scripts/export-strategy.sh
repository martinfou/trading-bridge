#!/bin/bash
# 🧬 export-strategy.sh — Export, compile, and backtest a genetic trading strategy
#
# Usage:
#   ./scripts/export-strategy.sh [options]
#
# Options:
#   --type trend|meanrev|breakout|momentum   Strategy type (default: trend)
#   --name StrategyName                       Class name (default: GeneratedStrategy)
#   --backtest                                Also compile & backtest with synthetic data
#   --bars N                                  Number of synthetic bars (default: 250)
#   --capital N                               Initial capital (default: 100000)
#   --help                                    Show this help
#
# Examples:
#   ./scripts/export-strategy.sh --type trend --name TrendV1
#   ./scripts/export-strategy.sh --type breakout --name BreakoutV1 --backtest --bars 500
#   ./scripts/export-strategy.sh --type meanrev --name MeanRev_ES1 --backtest --capital 50000
#
# What it does:
#   1. Compile the full project (mvn compile)
#   2. Run StrategyExporter CLI to create a chromosome and export Java source
#   3. Write the generated .java file to trading-strategies/src/main/java/.../generated/
#   4. Compile the generated strategy with mvn compile
#   5. (Optional) Run backtest with synthetic data
#   6. Print a summary of results
#
# Prerequisites:
#   - Java 21+ (JDK with javac)
#   - Apache Maven 3.8+
#   - Project built at least once: mvn compile -q

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

# == Colors ==================================================================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# == Defaults ================================================================
TYPE="trend"
NAME="GeneratedStrategy"
RUN_BACKTEST=false
BARS=250
CAPITAL=100000

# == Parse arguments =========================================================
while [[ $# -gt 0 ]]; do
  case "$1" in
    --type)
      TYPE="$2"; shift 2 ;;
    --name)
      NAME="$2"; shift 2 ;;
    --backtest)
      RUN_BACKTEST=true; shift ;;
    --bars)
      BARS="$2"; shift 2 ;;
    --capital)
      CAPITAL="$2"; shift 2 ;;
    --help|-h)
      sed -n '2,28p' "$0"
      exit 0 ;;
    *)
      echo -e "${RED}Unknown option: $1${NC}"
      echo "Usage: ./scripts/export-strategy.sh --type trend --name MyStrategy [--backtest]"
      exit 1 ;;
  esac
done

# Validate type
case "$TYPE" in
  trend|meanrev|breakout|momentum) ;;
  *)
    echo -e "${RED}Invalid type: $TYPE. Valid: trend, meanrev, breakout, momentum${NC}"
    exit 1 ;;
esac

# Validate name (basic Java identifier check)
if ! echo "$NAME" | grep -qE '^[A-Z][a-zA-Z0-9]*$'; then
  echo -e "${RED}Invalid name: '$NAME'. Must be a valid Java class name (e.g. TrendV1)${NC}"
  exit 1
fi

# == Banner ==================================================================
echo ""
echo -e "${CYAN}╔══════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   🧬 Trading Bridge — Export Strategy   ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${BOLD}Type:${NC}      $TYPE"
echo -e "  ${BOLD}Name:${NC}      $NAME"
echo -e "  ${BOLD}Backtest:${NC}  $RUN_BACKTEST ($BARS bars, \$${CAPITAL})"
echo ""

# == Step 1: Compile project ================================================
echo -e "${YELLOW}[1/4] Compiling project...${NC}"
if mvn compile -q 2>&1; then
  echo -e "${GREEN}  ✅ Project compiled${NC}"
else
  echo -e "${RED}  ❌ Compilation failed${NC}"
  exit 1
fi

# == Step 2: Build Maven command for StrategyExporter =======================
echo ""
echo -e "${YELLOW}[2/4] Generating strategy via StrategyExporter...${NC}"

# Build Maven exec command
MVN_EXEC="mvn exec:java -pl trading-genetics -q \
  -Dexec.mainClass=\"com.martinfou.trading.genetics.StrategyExporter\" \
  -Dexec.args=\"--type $TYPE --name $NAME\""

if [ "$RUN_BACKTEST" = true ]; then
  MVN_EXEC="mvn exec:java -pl trading-genetics -q \
    -Dexec.mainClass=\"com.martinfou.trading.genetics.StrategyExporter\" \
    -Dexec.args=\"--type $TYPE --name $NAME --backtest --bars $BARS --capital $CAPITAL\""
fi

# We need to set project.root for the exporter to find the project root
# Since we're already in the project root, user.dir handles it
if eval "$MVN_EXEC"; then
  echo -e "${GREEN}  ✅ Strategy generated: $NAME${NC}"
else
  echo -e "${RED}  ❌ Strategy generation failed${NC}"
  exit 1
fi

# == Step 3: Compile generated strategy ======================================
echo ""
echo -e "${YELLOW}[3/4] Compiling generated strategy...${NC}"

GENERATED_FILE="trading-strategies/src/main/java/com/martinfou/trading/strategies/generated/${NAME}.java"

if [ ! -f "$GENERATED_FILE" ]; then
  echo -e "${RED}  ❌ Generated file not found: $GENERATED_FILE${NC}"
  echo -e "${YELLOW}  Trying to find it...${NC}"
  find . -name "${NAME}.java" -path "*/generated/*" 2>/dev/null || true
  exit 1
fi

echo -e "  Source: $GENERATED_FILE"
echo -e "  Size:   $(wc -l < "$GENERATED_FILE") lines"

if mvn compile -q 2>&1; then
  echo -e "${GREEN}  ✅ Generated strategy compiled successfully${NC}"
else
  echo -e "${RED}  ❌ Generated strategy compilation failed${NC}"
  exit 1
fi

# == Step 4: Show results ====================================================
echo ""
echo -e "${YELLOW}[4/4] Results${NC}"
echo ""

# Get the generated class path
CLASS_PATH="com.martinfou.trading.strategies.generated.${NAME}"
GENERATED_CLASS="trading-strategies/target/classes/com/martinfou/trading/strategies/generated/${NAME}.class"

echo -e "${CYAN}═══════════════════════════════════════════${NC}"
echo -e "${CYAN}  EXPORT SUMMARY${NC}"
echo -e "${CYAN}═══════════════════════════════════════════${NC}"
echo -e "  ${BOLD}Type:${NC}         $TYPE"
echo -e "  ${BOLD}Class:${NC}        $CLASS_PATH"
echo -e "  ${BOLD}Source:${NC}       $GENERATED_FILE"

if [ -f "$GENERATED_CLASS" ]; then
  echo -e "  ${BOLD}Compiled:${NC}     ${GREEN}YES${NC}"
  echo -e "  ${BOLD}Class file:${NC}   $GENERATED_CLASS"
else
  echo -e "  ${BOLD}Compiled:${NC}     ${YELLOW}NO (not found in target/classes)${NC}"
fi

echo ""

if [ "$RUN_BACKTEST" = true ]; then
  echo -e "  ${BOLD}Backtest:${NC}     Run during generation (see above)${NC}"
fi

echo ""
echo -e "${CYAN}───────────────────────────────────────────${NC}"
echo -e "${CYAN}  NEXT STEPS${NC}"
echo -e "${CYAN}───────────────────────────────────────────${NC}"
echo -e "  • Edit the strategy:          ${GREEN}code $GENERATED_FILE${NC}"
echo -e "  • Run all tests:              ${GREEN}./scripts/test-all.sh${NC}"
echo -e "  • Run genetic engine:         ${GREEN}./scripts/test-all.sh genetic${NC}"
echo -e "  • Run backtest manually:      ${GREEN}mvn test -pl trading-backtest${NC}"
echo ""
echo -e "${CYAN}═══════════════════════════════════════════${NC}"
echo ""
echo -e "${GREEN}✅ Export complete!${NC}"
echo ""
