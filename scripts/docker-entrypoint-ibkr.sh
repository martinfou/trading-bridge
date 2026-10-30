#!/bin/bash
# Docker entrypoint for the IBKR Futures Lane (Story 46.11).
# Launches com.martinfou.trading.runtime.ibkr.IbkrLiveRunner in a supervised
# retry loop with exponential backoff. Never touches OANDA credentials or state.

set -o pipefail

IBKR_ACCOUNT_ID="${IBKR_ACCOUNT_ID:-}"
IBKR_PORT="${IBKR_PORT:-4002}"
STRATEGY="${STRATEGY:-sq-mgc-m30-breakout-0-24542}"
DRY_RUN="${DRY_RUN:-false}"
TB_ALLOW_ORDERS="${TB_ALLOW_ORDERS:-}"

if [ -z "$IBKR_ACCOUNT_ID" ]; then
    echo "ERROR: IBKR_ACCOUNT_ID must be set for the futures lane"
    exit 1
fi

if [ "$IBKR_PORT" = "4001" ] || [ "$IBKR_PORT" = "7496" ] || [ "$IBKR_PORT" = "7497" ]; then
    echo "ERROR: Refusing forbidden IBKR_PORT=${IBKR_PORT}. Only paper gateway port (4002) is allowed."
    exit 2
fi

if [ "$DRY_RUN" != "true" ] && [ "$TB_ALLOW_ORDERS" != "1" ] && [ "$TB_ALLOW_ORDERS" != "true" ]; then
    echo "ERROR: TB_ALLOW_ORDERS must be set to 1 (or DRY_RUN=true) for IbkrLiveRunner"
    exit 1
fi

BACKOFF=10
MAX_BACKOFF=3600
MIN_RUN_THRESHOLD="${MIN_RUN_THRESHOLD:-300}"

CLASSPATH="/app/classes/trading-core:/app/classes/trading-data:/app/classes/trading-strategies:/app/classes/trading-broker:/app/classes/trading-parser:/app/classes/trading-backtest:/app/classes/trading-intelligence:/app/classes/trading-runtime:/app/libs/*"

while true; do
    START_TS=$(date +%s)

    java -cp "$CLASSPATH" com.martinfou.trading.runtime.ibkr.IbkrLiveRunner
    EXIT_CODE=$?

    RUNTIME=$(( $(date +%s) - START_TS ))

    if [ "$EXIT_CODE" -eq 0 ] && [ "$RUNTIME" -ge "$MIN_RUN_THRESHOLD" ]; then
        echo "✅ IbkrLiveRunner exited cleanly after ${RUNTIME}s. Container stopping."
        exit 0
    fi

    echo "⚠️ IbkrLiveRunner exited with code $EXIT_CODE after ${RUNTIME}s (< ${MIN_RUN_THRESHOLD}s)."
    echo "   Retrying in ${BACKOFF}s..."
    sleep "$BACKOFF"
    BACKOFF=$(( BACKOFF * 2 ))
    [ "$BACKOFF" -gt "$MAX_BACKOFF" ] && BACKOFF=$MAX_BACKOFF
done
