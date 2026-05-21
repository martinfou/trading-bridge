#!/usr/bin/env bash
# =============================================================================
# run-backtest.sh — Run a single strategy backtest via Java classpath
# =============================================================================
# Usage: ./scripts/run-backtest.sh <symbol> [strategy_class] [granularity] [count] [capital]
#
# Examples:
#   ./scripts/run-backtest.sh EUR/USD                                        # default SMA
#   ./scripts/run-backtest.sh EUR/USD com...sqimported.Strategy_2_14_147_Adapted
#   ./scripts/run-backtest.sh USD/JPY --json --html                           # with export
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Build classpath
CP=""
for m in trading-core trading-backtest trading-data trading-strategies trading-genetics trading-examples; do
    CP="$CP:$PROJECT_DIR/$m/target/classes"
done

# Add SLF4J
SLF4J_API=$(find "$HOME/.m2/repository/org/slf4j/slf4j-api" -name "slf4j-api-2.*.jar" 2>/dev/null | head -1)
SLF4J_SIMPLE=$(find "$HOME/.m2/repository/org/slf4j/slf4j-simple" -name "slf4j-simple-2.*.jar" 2>/dev/null | head -1)
[ -n "$SLF4J_API" ] && CP="$CP:$SLF4J_API"
[ -n "$SLF4J_SIMPLE" ] && CP="$CP:$SLF4J_SIMPLE"

# Run
exec java -cp "$CP" com.martinfou.trading.examples.RunBacktest --proxy local --json --html "$@"
