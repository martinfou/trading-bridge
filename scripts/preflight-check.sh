#!/bin/bash
# Pre-flight check for Daily Strategy Lab
# Exits with 0 if ready, 1 if blocked

set -euo pipefail
REPO="/home/martinfou/projects/trading-bridge"
cd "$REPO"

# 1. Check working directory
if [ ! -d "$REPO/.git" ]; then
    echo "FATAL: Not a git repo at $REPO"
    exit 1
fi

# 2. Git pull (fast-forward only)
git pull --ff-only origin master 2>/dev/null || echo "WARN: git pull failed, continuing with existing code"

# 3. Quick Maven compile check (no tests)
export PATH="$HOME/.local/share/mise/shims:$PATH"
if ! mvn compile -q -pl trading-core,trading-strategies,trading-backtest -am -DskipTests 2>&1; then
    echo "FATAL: Maven compile failed"
    exit 1
fi

# 4. Check data files exist
MISSING=0
for pair in EUR_USD GBP_USD USD_JPY AUD_USD USD_CAD NZD_USD USD_CHF EUR_JPY GBP_JPY; do
    if [ ! -f "data/historical/${pair}_H1.csv" ]; then
        echo "MISSING: data/historical/${pair}_H1.csv"
        MISSING=1
    fi
done

if [ "$MISSING" -eq 1 ]; then
    echo "FATAL: Missing historical data files"
    exit 1
fi

# 5. Check if control plane is already running (could cause port conflict)
if curl -sf http://localhost:8080/api/strategies >/dev/null 2>&1; then
    echo "WARN: Control plane already running on port 8080"
fi

# 6. Load previous state
STATE_FILE="$REPO/.strategy-lab-state.json"
if [ -f "$STATE_FILE" ]; then
    echo "STATE: $(cat "$STATE_FILE")"
else
    echo "STATE: No previous state found"
fi

# 7. List existing creative strategies
echo "EXISTING_STRATEGIES:"
find trading-strategies/src/main/java/com/martinfou/trading/strategies/creative -name "*.java" -exec basename {} .java \; | sort | tr '\n' ' '
echo ""

echo "READY"
exit 0
