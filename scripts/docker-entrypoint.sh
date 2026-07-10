#!/bin/bash
# Docker entrypoint for trading-bridge
# Reads env vars and passes them as args to LiveStrategyRunner
# Wraps in retry loop with exponential backoff to prevent
# crash-looping on invalid API keys or transient failures.
#
# The entrypoint tracks how long Java ran. If it ran <5 min,
# we assume a startup failure (401, config error, etc.) and
# backoff before retrying. Long successful runs exit cleanly
# and Docker's restart: unless-stopped takes over normally.

API_KEY="${OANDA_API_KEY:-}"
ACCOUNT_ID="${OANDA_ACCOUNT_ID:-}"
STRATEGY="${STRATEGY:-vwpreversion}"
GRANULARITY="${GRANULARITY:-H1}"
INTERVAL_SEC="${INTERVAL_SEC:-60}"

if [ -z "$API_KEY" ] || [ -z "$ACCOUNT_ID" ]; then
    echo "ERROR: OANDA_API_KEY and OANDA_ACCOUNT_ID must be set"
    echo "Set them in the environment or via an env_file"
    exit 1
fi

BACKOFF=10                  # initial retry delay (seconds)
MAX_BACKOFF=3600            # max delay cap (1 hour)
MIN_RUN_THRESHOLD=300       # if Java ran < 5 min, it was a startup failure

while true; do
    START_TS=$(date +%s)

    java -cp "/app/classes/trading-core:/app/classes/trading-data:/app/classes/trading-strategies:/app/classes/trading-broker:/app/classes/trading-parser:/app/libs/*" \
        com.martinfou.trading.strategies.LiveStrategyRunner \
        "$API_KEY" "$ACCOUNT_ID" $STRATEGY "$GRANULARITY" "$INTERVAL_SEC"

    EXIT_CODE=$?
    RUNTIME=$(( $(date +%s) - START_TS ))

    if [ "$EXIT_CODE" -eq 0 ] && [ "$RUNTIME" -ge "$MIN_RUN_THRESHOLD" ]; then
        echo "✅ Strategy runner exited cleanly after ${RUNTIME}s. Container stopping."
        exit 0
    fi

    # Short run or non-zero exit → backoff before retrying
    # This prevents tight restart loops with Docker restart: unless-stopped
    echo "⚠️ Strategy exited with code $EXIT_CODE after ${RUNTIME}s (< ${MIN_RUN_THRESHOLD}s = startup failure)."
    echo "   Retrying in ${BACKOFF}s..."
    sleep "$BACKOFF"

    # Exponential backoff, capped at 1 hour
    BACKOFF=$(( BACKOFF * 2 ))
    [ "$BACKOFF" -gt "$MAX_BACKOFF" ] && BACKOFF=$MAX_BACKOFF
done
