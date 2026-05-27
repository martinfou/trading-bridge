#!/bin/bash
# =============================================================================
# Deploy Creative Strategy #1 — NYMidSessionMomentum to Paper Trading
# Stratégie la plus robuste: Sharpe 1.29-1.58 sur TOUS les 9 assets
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$HOME/logs/paper-trade"
mkdir -p "$LOG_DIR"

API_KEY="b8528a5a8a334ede6ee803d7e338e564-f3b9e04a58ed32b56def064fbaa4ee06"
ACCOUNT_ID="101-002-4729622-008"
STRATEGY="nymid"
GRANULARITY="H1"
INTERVAL="60"
LOG_FILE="$LOG_DIR/creative-${STRATEGY}-$(date +%Y%m%d-%H%M%S).log"

# Build classpath
JAR="$BRIDGE_DIR/trading-strategies/target/trading-strategies-1.0.0-SNAPSHOT.jar"
DEPS_FILE="/tmp/creative-cp.txt"
mvn dependency:build-classpath -pl trading-strategies -q -DincludeScope=runtime -Dmdep.outputFile="$DEPS_FILE" 2>/dev/null || true
if [ -f "$DEPS_FILE" ]; then
    CLASSPATH="$JAR:$(cat "$DEPS_FILE")"
    rm -f "$DEPS_FILE"
else
    CLASSPATH="$JAR:$(find "$BRIDGE_DIR" -name "*.jar" -path "*/target/*" | tr '\n' ':')"
fi

echo "╔══════════════════════════════════════════════╗"
echo "║  🧪 CREATIVE STRATEGY PAPER TRADING          ║"
echo "║  Strategy: $STRATEGY"
echo "║  Account:  $ACCOUNT_ID"
echo "║  Gran:     $GRANULARITY / ${INTERVAL}s"
echo "║  Log:      $LOG_FILE"
echo "╚══════════════════════════════════════════════╝"
echo ""

java -Xmx512m -cp "$CLASSPATH" \
    com.martinfou.trading.strategies.LiveStrategyRunner \
    "$API_KEY" "$ACCOUNT_ID" "$STRATEGY" "$GRANULARITY" "$INTERVAL" \
    2>&1 | tee "$LOG_FILE"
