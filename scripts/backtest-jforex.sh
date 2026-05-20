#!/usr/bin/env bash
# =============================================================================
# backtest-jforex.sh — Backtest des stratégies JForex converties sur données 2026
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║   JForex Strategy Backtest — EUR/USD H1 2026    ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

# Data file (2026 real data)
DATA_CSV="${PROJECT_DIR}/data/historical/dukascopy/eurusd-h1-bid-2026-01-01-2026-05-20.csv"
if [ ! -f "$DATA_CSV" ]; then
    echo "❌ Data file not found: $DATA_CSV"
    echo "   Run download-data.sh first"
    exit 1
fi
echo "📊 Data: $(wc -l < "$DATA_CSV") bars EUR/USD H1 2026"
echo ""

# Build classpath
CLASSPATH=""
for module in trading-core trading-backtest trading-strategies trading-examples trading-parser; do
    dir="$PROJECT_DIR/$module/target/classes"
    [ -d "$dir" ] && CLASSPATH="$CLASSPATH:$dir"
done
# Maven dependencies
MAVEN_REPO="${HOME}/.m2/repository"
for jar in \
    org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar \
    org/slf4j/slf4j-simple/2.0.16/slf4j-simple-2.0.16.jar \
    com/fathzer/javaluator/3.0.3/javaluator-3.0.3.jar; do
    j="$MAVEN_REPO/$jar"
    [ -f "$j" ] && CLASSPATH="$CLASSPATH:$j"
done

# Ensure compiled
echo "🔨 Compiling..."
mvn compile -q -DskipTests -pl trading-core,trading-backtest,trading-strategies,trading-examples -am 2>/dev/null
echo ""

# List of converted JForex strategies
STRATEGIES=(
    "com.martinfou.trading.strategies.sqimported.Strategy_2_31_175_Converted"
    "com.martinfou.trading.strategies.sqimported.Strategy_2_31_177_Converted"
    "com.martinfou.trading.strategies.sqimported.Strategy_2_32_120_Converted"
    "com.martinfou.trading.strategies.sqimported.Strategy_2_36_190_Converted"
    "com.martinfou.trading.strategies.sqimported.Strategy_2_38_112_Converted"
    "com.martinfou.trading.strategies.sqimported.Strategy_2_14_147_Adapted"
    "com.martinfou.trading.strategies.sqimported.Strategy_2_15_195_Adapted"
)

echo "╔══════════════════════════════════════════════════════════════════════╗"
echo "║  Backtesting ${#STRATEGIES[@]} JForex strategies on EUR/USD H1 2026       ║"
echo "╚══════════════════════════════════════════════════════════════════════╝"

PASS=0
FAIL=0
RESULTS_FILE="${PROJECT_DIR}/batch-results-jforex/summary.txt"
mkdir -p "$(dirname "$RESULTS_FILE")"

> "$RESULTS_FILE"
echo "JForex Strategy Backtest Results — EUR/USD H1 2026" >> "$RESULTS_FILE"
echo "=====================================================" >> "$RESULTS_FILE"
echo "" >> "$RESULTS_FILE"
printf "%-40s %10s %10s %10s %10s %10s %10s\n" "Strategy" "Trades" "Win%" "P&L" "Sharpe" "PF" "DD%" >> "$RESULTS_FILE"
printf "%s\n" "──────────────────────────────────────────────────────────────────────────────────" >> "$RESULTS_FILE"

for strat in "${STRATEGIES[@]}"; do
    name=$(echo "$strat" | awk -F. '{print $NF}')
    echo -n "  Testing $name ... "

    output=$(java -Xmx2g -cp "$CLASSPATH" \
        com.martinfou.trading.examples.RunBacktest \
        "$DATA_CSV" "EUR/USD" \
        2>/dev/null || echo "ERROR")

    trades=$(echo "$output" | grep -oP 'Total Trades: \K\d+' || echo "0")
    win_rate=$(echo "$output" | grep -oP 'Win Rate: \K[\d.]+' || echo "0")
    pnl=$(echo "$output" | grep -oP 'Net Profit: \$[\d.+-]+' || echo "0")
    sharpe=$(echo "$output" | grep -oP 'Sharpe Ratio: [\d.+-]+' || echo "0")
    pf=$(echo "$output" | grep -oP 'Profit Factor: [\d.]+' || echo "0")
    dd=$(echo "$output" | grep -oP 'Max Drawdown: [\d.]+%' || echo "0")

    if [ "$trades" = "0" ] || echo "$output" | grep -q "ERROR"; then
        echo -e "${RED}❌ FAILED${NC}"
        printf "  ⚠️  Error running strategy\n"
        echo "$output" | tail -5
        FAIL=$((FAIL + 1))
        printf "%-40s %10s %10s %10s %10s %10s %10s\n" "$name" "ERROR" "-" "-" "-" "-" "-" >> "$RESULTS_FILE"
    else
        echo -e "${GREEN}✅ ${trades} trades | WR: ${win_rate}% | P&L: \$${pnl}${NC}"
        PASS=$((PASS + 1))
        printf "%-40s %10s %10s %10s %10s %10s %10s\n" "$name" "$trades" "${win_rate}%" "\$${pnl}" "$sharpe" "$pf" "$dd" >> "$RESULTS_FILE"
    fi
    echo ""
done

echo "╔══════════════════════════════════════════════════╗"
echo "║  Results: $PASS ✅ / $FAIL ❌                        ║"
echo "╚══════════════════════════════════════════════════╝"
echo "  Full report: $RESULTS_FILE"
echo ""
