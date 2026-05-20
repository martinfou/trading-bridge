#!/usr/bin/env bash
# =============================================================================
# run-strategy.sh — Activate a trading strategy on this machine
# =============================================================================
# Called by cron-promote.sh to start/stop a strategy.
#
# Usage:
#   run-strategy.sh <strategy-id> <phase>
#
# Example:
#   run-strategy.sh TREND_FOLLOWING_1_EURUSD_H1 live
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DEPLOY_DIR="$PROJECT_DIR/deploy"

STRATEGY_ID="${1:?Usage: run-strategy.sh <strategy-id> <phase>}"
PHASE="${2:?Usage: run-strategy.sh <strategy-id> <phase>}"

STRATEGY_DIR="$PROJECT_DIR/trading-strategies"
PID_FILE="$DEPLOY_DIR/.strategy-$STRATEGY_ID.pid"
LOG_FILE="$DEPLOY_DIR/strategy-$STRATEGY_ID.log"

mkdir -p "$DEPLOY_DIR"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Activating $STRATEGY_ID ($PHASE)..." | tee -a "$LOG_FILE"

# Kill existing instance if any
if [ -f "$PID_FILE" ]; then
    old_pid=$(cat "$PID_FILE")
    if kill -0 "$old_pid" 2>/dev/null; then
        echo "Killing existing instance (PID $old_pid)..." | tee -a "$LOG_FILE"
        kill "$old_pid" 2>/dev/null || true
        sleep 1
    fi
    rm -f "$PID_FILE"
fi

# TODO: Actual strategy runner invocation
# This is a placeholder that records the activation.
# The real runner will be:
#   java -jar trading-runner.jar --strategy "$STRATEGY_ID" --phase "$PHASE"

# Record activation
echo "{
  \"strategy\": \"$STRATEGY_ID\",
  \"phase\": \"$PHASE\",
  \"activated_at\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",
  \"machine\": \"$(hostname -s)\",
  \"git_commit\": \"$(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo 'unknown')\"
}" > "$DEPLOY_DIR/.strategy-$STRATEGY_ID.json"

echo "✅ $STRATEGY_ID activated ($PHASE)" | tee -a "$LOG_FILE"
