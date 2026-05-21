#!/usr/bin/env bash
#===============================================================================
# Paper Trade — SQ Strategies (Best Performers)
# 
# Lance les stratégies SQ migrées sur le compte OANDA Practice (paper trading)
# Stratégies sélectionnées par backtest performance:
#   - 2_31_177 (BEST — Sharpe 1.48, +69.56% return, 55.6% WR)
#   - 2_32_120 (DECENT — Sharpe 0.73, +20.38% return, 40% WR)
#
# Usage: ./paper-trade-sq.sh [strategy] [granularity] [intervalSec]
#   strategy:   2_31_177 | 2_32_120 | all  (default: all)
#   granularity: H1 | H4 | D  (default: H1)
#   intervalSec: loop interval in seconds (default: 60)
#
# State saved to: /tmp/live-strategy-state.json (crash recovery)
# Logs: /home/martinfou/logs/paper-trade/
#===============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$HOME/logs/paper-trade"
JAR="$BRIDGE_DIR/trading-strategies/target/trading-strategies-1.0.0-SNAPSHOT.jar"
# Build classpath using Maven (ensures all runtime deps are included)
update_classpath() {
    cd "$BRIDGE_DIR"
    local deps_file="/tmp/paper-trade-cp-$$.txt"
    mvn dependency:build-classpath -pl trading-strategies -q -DincludeScope=runtime -Dmdep.outputFile="$deps_file" 2>/dev/null
    if [ -f "$deps_file" ]; then
        local deps=$(cat "$deps_file")
        CLASSPATH="$JAR:$deps"
        rm -f "$deps_file"
    else
        echo "⚠️  Maven classpath build failed — falling back to manual scan"
        CLASSPATH="$JAR:$(find "$BRIDGE_DIR" -name "*.jar" -path "*/target/*" | tr '\n' ':')"
    fi
}

# OANDA Practice Account (paper trading)
API_KEY="b8528a5a8a334ede6ee803d7e338e564-f3b9e04a58ed32b56def064fbaa4ee06"
ACCOUNT_ID="101-002-4729622-008"

# Defaults
STRATEGY="${1:-all}"
GRANULARITY="${2:-H1}"
INTERVAL="${3:-60}"

# Create log directory
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/paper-trade-$(date +%Y%m%d-%H%M%S).log"

# Build project if JAR is missing or older than source
if [ ! -f "$JAR" ] || [ "$(find "$BRIDGE_DIR/trading-strategies/src" -name "*.java" -newer "$JAR" 2>/dev/null | head -1)" != "" ]; then
    echo "📦 Building trading-bridge..."
    cd "$BRIDGE_DIR"
    mvn package -pl trading-strategies -am -DskipTests -q 2>&1
    echo "✅ Build complete."
fi

# Build classpath from Maven deps
update_classpath

# Run Paper Trading
echo "╔════════════════════════════════════════════════════╗"
echo "║       📊 Paper Trading — SQ Strategies            ║"
echo "║────────────────────────────────────────────────────║"
echo "║  Strategy:     $STRATEGY"
echo "║  Granularity:  $GRANULARITY"
echo "║  Interval:     ${INTERVAL}s"
echo "║  Account:      $ACCOUNT_ID (Practice)"
echo "║  Log:          $LOG_FILE"
echo "╚════════════════════════════════════════════════════╝"

cd "$BRIDGE_DIR"

java -cp "$CLASSPATH" \
    -Dorg.slf4j.simpleLogger.logFile="$LOG_FILE" \
    -Dorg.slf4j.simpleLogger.showDateTime=true \
    -Dorg.slf4j.simpleLogger.dateTimeFormat="yyyy-MM-dd HH:mm:ss" \
    com.martinfou.trading.strategies.LiveStrategyRunner \
    "$API_KEY" "$ACCOUNT_ID" "$STRATEGY" "$GRANULARITY" "$INTERVAL" 2>&1 | tee -a "$LOG_FILE"

EXIT_CODE=$?
if [ $EXIT_CODE -eq 0 ]; then
    echo "✅ Paper trading completed successfully."
else
    echo "❌ Paper trading exited with code $EXIT_CODE"
fi
exit $EXIT_CODE
