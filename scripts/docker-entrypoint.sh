#!/bin/bash
# Docker entrypoint for trading-bridge
# Reads env vars and passes them as args to LiveStrategyRunner
set -e

API_KEY="${OANDA_API_KEY:-}"
ACCOUNT_ID="${OANDA_ACCOUNT_ID:-}"
STRATEGY="${STRATEGY:-vwp}"
GRANULARITY="${GRANULARITY:-H1}"
INTERVAL_SEC="${INTERVAL_SEC:-60}"

if [ -z "$API_KEY" ] || [ -z "$ACCOUNT_ID" ]; then
    echo "ERROR: OANDA_API_KEY and OANDA_ACCOUNT_ID must be set"
    echo "Set them in the environment or via an env_file"
    exit 1
fi

exec java -cp "/app/classes/trading-core:/app/classes/trading-data:/app/classes/trading-strategies:/app/classes/trading-broker:/app/classes/trading-parser:/app/libs/*" \
    com.martinfou.trading.strategies.LiveStrategyRunner \
    "$API_KEY" "$ACCOUNT_ID" "$STRATEGY" "$GRANULARITY" "$INTERVAL_SEC"
