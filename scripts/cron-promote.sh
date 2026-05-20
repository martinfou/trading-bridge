#!/usr/bin/env bash
# =============================================================================
# cron-promote.sh — Cross-Machine Strategy Promotion Check
# =============================================================================
# Runs every hour on paper/live machines. Checks the Laravel API for newly
# promoted strategies and auto-activates them.
#
# Flow:
#   1. Detect machine role (paper/live from hostname)
#   2. GET /api/mission/promotions?role=<role> from Laravel dashboard
#   3. For each new strategy: git pull -> build -> activate -> record
#   4. Report result via POST /api/health/ping
#
# Install as cron (run as `crontab -e`):
#   0 * * * * /path/to/project/scripts/cron-promote.sh >> /var/log/trading-promote.log 2>&1
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DEPLOY_DIR="$PROJECT_DIR/deploy"
CONFIG_FILE="$DEPLOY_DIR/$(hostname -s).env"
LOG_FILE="$DEPLOY_DIR/cron-promote.log"
STATE_FILE="$DEPLOY_DIR/.last-promotion-check.json"
LARAVEL_API="${LARAVEL_API_URL:-http://localhost:8082/api}"
HEALTH_PORT="${HEALTH_PORT:-9090}"

mkdir -p "$DEPLOY_DIR"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG_FILE"; }
warn() { echo "[WARN] $*" | tee -a "$LOG_FILE"; }
error() { echo "[ERROR] $*" | tee -a "$LOG_FILE"; }

# ---- Detect machine role ---- #

detect_role() {
    local hostname
    hostname="$(hostname -s)"
    case "$hostname" in
        *paper*|*vps*)     echo "paper" ;;
        *live*|*prod*)     echo "live" ;;
        *backtest*)        echo "backtest" ;;
        *)
            # Try config file
            if [ -f "$CONFIG_FILE" ]; then
                # shellcheck source=/dev/null
                source "$CONFIG_FILE"
                echo "${MACHINE_ROLE:-unknown}"
            else
                echo "unknown"
            fi
            ;;
    esac
}

load_config() {
    local role
    role="$(detect_role)"

    if [ -f "$CONFIG_FILE" ]; then
        # shellcheck source=/dev/null
        source "$CONFIG_FILE"
    fi

    # Load from env file or use detected role
    if [ -z "${MACHINE_ROLE:-}" ] || [ "$MACHINE_ROLE" = "unknown" ]; then
        MACHINE_ROLE="$role"
    fi

    export MACHINE_ROLE OANDA_API_KEY OANDA_ACCOUNT_ID HEALTH_PORT LARAVEL_API_URL STRATEGIES_DIR
}

# ---- Git Sync ---- #

git_sync() {
    log "Git sync ($(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo '?'))..."
    cd "$PROJECT_DIR"
    git stash 2>/dev/null || true
    git pull 2>&1 | tail -1 | log
    local new_hash
    new_hash=$(git rev-parse --short HEAD)
    log "Now at $new_hash"
    echo "$new_hash"
}

# ---- Maven Build ---- #

maven_build() {
    log "Building Maven modules..."
    if cd "$PROJECT_DIR" && mvn package -q -DskipTests 2>&1; then
        log "Build successful"
        return 0
    else
        warn "Build failed — skipping activation"
        return 1
    fi
}

# ---- Strategy Activation ---- #

activate_strategy() {
    local strategy_id="$1"
    local phase="$2"

    log "Activating $strategy_id (phase: $phase)..."

    # If it's a Java strategy, run it via the trading runner
    local runner_script="$PROJECT_DIR/scripts/run-strategy.sh"
    if [ -x "$runner_script" ]; then
        "$runner_script" "$strategy_id" "$phase" 2>&1 | log
    fi

    # Update health server
    local health_url="http://localhost:${HEALTH_PORT}/health"
    if curl -sf "$health_url" > /dev/null 2>&1; then
        curl -s -X POST "http://localhost:${HEALTH_PORT}/health/strategies" \
            -H "Content-Type: application/json" \
            -d "{\"add\": \"$strategy_id\"}" 2>/dev/null || true
    fi

    log "✅ $strategy_id activated"
}

# ---- Main Promotion Check ---- #

main() {
    load_config

    local role="$MACHINE_ROLE"
    log "=== Promotion Check ==="
    log "Machine: $(hostname -s) | Role: $role"

    # Backtest machines don't auto-promote (they generate strategies)
    if [ "$role" = "backtest" ] || [ "$role" = "unknown" ]; then
        log "Role '$role' — no auto-promotion (backtest generates, not consumes)"
        # Still report health
        curl -sf -X POST "$LARAVEL_API/health/ping" \
            -H "Content-Type: application/json" \
            -d "{\"machine\": \"$(hostname -s)\", \"uptime\": \"$(uptime -p | sed 's/up //')\", \"errors_24h\": 0}" \
            > /dev/null 2>&1 || true
        exit 0
    fi

    # Fetch strategies promoted to our role
    local response
    response=$(curl -sf --connect-timeout 10 --max-time 15 \
        "$LARAVEL_API/strategies" 2>/dev/null || echo "")

    if [ -z "$response" ]; then
        warn "Cannot reach Laravel API at $LARAVEL_API"
        log "Dashboard may be down — skipping promotion check"
        exit 1
    fi

    # Parse JSON for strategies in our phase (jq or fallback)
    local our_strategies
    our_strategies=$(echo "$response" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    if isinstance(data, list):
        ours = [s for s in data if s.get('phase') == '$role' and s.get('status') == 'active']
        for s in ours:
            print(f\"{s['name']}|{s.get('version', '?')}|{s.get('pnl', 0)}|{s.get('win_rate', 0)}\")
    elif isinstance(data, dict) and 'data' in data:
        ours = [s for s in data['data'] if s.get('phase') == '$role' and s.get('status') == 'active']
        for s in ours:
            print(f\"{s['name']}|{s.get('version', '?')}|{s.get('pnl', 0)}|{s.get('win_rate', 0)}\")
except:
    pass
" 2>/dev/null || echo "")

    if [ -z "$our_strategies" ]; then
        log "No active strategies promoted to '$role'"
    else
        log "Found strategies for $role:"
        local count=0
        while IFS='|' read -r name version pnl win_rate; do
            [ -z "$name" ] && continue
            log "  📊 $name v$version (P&L: \$$pnl, WR: $win_rate%)"
            count=$((count + 1))
        done <<< "$our_strategies"

        # Git sync + build + activate
        local hash
        hash=$(git_sync)

        if maven_build; then
            while IFS='|' read -r name version pnl win_rate; do
                [ -z "$name" ] && continue
                activate_strategy "$name" "$role"
            done <<< "$our_strategies"
        fi

        # Record state
        echo "{\"checked_at\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\", \"strategies\": $count, \"git_commit\": \"$hash\", \"role\": \"$role\"}" > "$STATE_FILE"
    fi

    # Report health to dashboard
    local uptime_str
    uptime_str=$(uptime -p | sed 's/up //')
    local git_commit
    git_commit=$(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")

    curl -sf -X POST "$LARAVEL_API/health/ping" \
        -H "Content-Type: application/json" \
        -d "{
            \"machine\": \"$(hostname -s)\",
            \"uptime\": \"${uptime_str:-?}\",
            \"version\": \"1.0.0-SNAPSHOT\",
            \"git_commit\": \"$git_commit\",
            \"active_strategies\": [],
            \"errors_24h\": 0,
            \"oanda_api_status\": \"ok\"
        }" > /dev/null 2>&1 || warn "Failed to report health"

    log "=== Check complete ==="
}

main "$@"
