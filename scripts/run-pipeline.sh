#!/usr/bin/env bash
# Start the Trading Bridge Unified Strategy Engine pipeline (Story 22.2 / Epic 22).
#
# Usage:
#   ./scripts/run-pipeline.sh [options]
#
# Options:
#   --profile PROFILE    Validation profile (LONG_TERM, PROP_SHOP, NEWS_WEEKLY)
#   --iterations N       Max iterations (default: 5)
#   --list, -l           Print the Strategy Catalog and exit
#   --help, -h           Show this help message

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export TRADING_BRIDGE_ROOT="${TRADING_BRIDGE_ROOT:-$ROOT}"

# If DEEPSEEK_API_KEY is not set, warn the user
if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
  echo "Warning: DEEPSEEK_API_KEY is not set in your environment." >&2
  echo "Make sure it is configured, or strategy generation will fail." >&2
  echo ""
fi

# Print startup info
echo "Starting Unified Strategy Engine pipeline..."
if [[ $# -gt 0 ]]; then
  echo "Arguments: $*"
fi

exec mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunStrategyPipeline" \
  -Dexec.args="$*"
