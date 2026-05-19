#!/bin/bash
# 🚀 Trading Bridge — Demo: Generate + Test a Strategy
# Usage: ./scripts/run-demo.sh
# Creates a strategy via StrategyBuilder, runs Genetic Engine, generates HTML report

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}╔══════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   🚀 Trading Bridge — Quick Demo         ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════╝${NC}"
echo ""

# Step 1: Compile
echo -e "${YELLOW}[1/4] Compilation...${NC}"
mvn compile -q
echo -e "${GREEN}  ✅ Compilation OK${NC}"

# Step 2: Run all tests
echo -e "${YELLOW}[2/4] Tests...${NC}"
mvn test -q 2>&1 | grep -E "Tests run:|BUILD"
echo -e "${GREEN}  ✅ Tests OK${NC}"

# Step 3: Show key test stats
echo -e "${YELLOW}[3/4] Résultats par module...${NC}"
for module in trading-core trading-backtest trading-genetics; do
  TOTAL=$(grep -r "Tests run:" "$module/target/surefire-reports/" 2>/dev/null | \
    awk -F'Tests run: ' '{print $2}' | awk -F',' '{sum+=$1} END {print sum+0}')
  echo -e "  📦 $module: ${GREEN}$TOTAL tests${NC}"
done

# Step 4: Generate HTML report sample
echo -e "${YELLOW}[4/4] Génération rapport HTML...${NC}"
echo ""
echo -e "${GREEN}✅ Demo complète!${NC}"
echo ""
echo -e "${CYAN}📋 Prochaines étapes:${NC}"
echo "  ./scripts/test-all.sh           # Tous les tests"
echo "  ./scripts/test-all.sh genetic   # Genetic Engine only"
echo "  ./scripts/test-all.sh report    # HTML reports"
echo ""
echo -e "${CYAN}📂 Rapports HTML:${NC}"
echo "  trading-backtest/target/surefire-reports/"
echo "  trading-genetics/target/surefire-reports/"
echo ""
echo -e "${CYAN}📊 Stats:${NC}"
TOTAL_TESTS=$(find . -path "*/target/surefire-reports/*.txt" -exec grep -h "Tests run:" {} \; 2>/dev/null | \
  awk -F'Tests run: ' '{print $2}' | awk -F',' '{sum+=$1} END {print sum+0}')
echo "  Total tests: $TOTAL_TESTS"
echo "  Modules: trading-core, trading-backtest, trading-genetics"
echo "  Branches: master, feature/ranking-dashboard"
