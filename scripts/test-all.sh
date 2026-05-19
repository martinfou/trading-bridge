#!/bin/bash
# 🧪 Test Suite — Trading Bridge
# Usage: ./scripts/test-all.sh [option]
# Options: full | quick | genetic | monte-carlo | walk-forward | report | smoke

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

case "${1:-full}" in
  full)
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    echo -e "${CYAN}  🧪 Trading Bridge — Full Test Suite${NC}"
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    mvn test 2>&1 | tail -10
    ;;

  smoke)
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    echo -e "${CYAN}  🔥 Smoke Test — Compile + Quick Tests${NC}"
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    mvn compile -q && echo -e "${GREEN}✅ Compilation OK${NC}"
    mvn test -pl trading-core,trading-backtest -q && echo -e "${GREEN}✅ Core + Backtest tests OK${NC}"
    ;;

  genetic)
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    echo -e "${CYAN}  🧬 Genetic Engine — Demo${NC}"
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    mvn test -pl trading-genetics -Dtest=GeneticEngineTest -q
    echo -e "${GREEN}✅ Genetic Engine tests OK${NC}"
    echo ""
    echo -e "${YELLOW}Pour lancer une optimisation complète:${NC}"
    echo "  mvn exec:java -pl trading-examples \\"
    echo "    -Dexec.mainClass=\\"com.martinfou.trading.RunOptimization\\""
    ;;

  monte-carlo)
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    echo -e "${CYAN}  🔬 Monte Carlo Simulation — Demo${NC}"
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    mvn test -pl trading-backtest -Dtest=MonteCarloSimulationTest -q
    echo -e "${GREEN}✅ Monte Carlo tests OK${NC}"
    ;;

  walk-forward)
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    echo -e "${CYAN}  📈 Walk-Forward Optimization — Demo${NC}"
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    mvn test -pl trading-backtest -Dtest=WalkForwardOptimizerTest -q
    echo -e "${GREEN}✅ Walk-Forward tests OK${NC}"
    ;;

  report)
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    echo -e "${CYAN}  📊 Generate HTML Reports${NC}"
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    mvn test -pl trading-genetics -Dtest=RankingDashboardTest -q
    mvn test -pl trading-backtest -Dtest=HtmlReportGeneratorTest -q
    echo -e "${GREEN}✅ Reports tests OK${NC}"
    ;;

  all-modules)
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    echo -e "${CYAN}  📦 All Module Tests${NC}"
    echo -e "${CYAN}═══════════════════════════════════════════${NC}"
    for module in trading-core trading-backtest trading-genetics; do
      echo -e "${YELLOW}Testing $module...${NC}"
      mvn test -pl "$module" -q && echo -e "${GREEN}  ✅ $module OK${NC}"
    done
    ;;

  help|*)
    echo -e "${CYAN}Trading Bridge — Scripts de test${NC}"
    echo ""
    echo "Usage: ./scripts/test-all.sh [option]"
    echo ""
    echo "Options:"
    echo "  full           Tous les tests (défaut)"
    echo "  smoke          Compilation + tests core/backtest"
    echo "  genetic        Genetic Engine uniquement"
    echo "  monte-carlo    Monte Carlo simulation"
    echo "  walk-forward   Walk-Forward optimization"
    echo "  report         HTML reports (Ranking + HtmlReport)"
    echo "  all-modules    Module par module"
    echo "  help           Ce message"
    ;;
esac
