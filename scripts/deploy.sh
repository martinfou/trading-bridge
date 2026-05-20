#!/usr/bin/env bash
# =============================================================================
# deploy.sh — Multi-Machine Deployment Script
# =============================================================================
# Usage:
#   ./deploy.sh promote <strategy-id> <env>         # Promote strategy to env
#   ./deploy.sh status                              # Show current status
#   ./deploy.sh health                              # Test /health endpoints
#   ./deploy.sh rollback <strategy-id>              # Rollback strategy
#
# Environments: backtest, paper, live
#
# Philosophy: GIT for CODE, API for STATE
#   - Code is synced via git pull
#   - Strategy state (which env, active/inactive) is tracked via Laravel API
#   - deploy/ folder contains machine-specific configs (.env files)
#     which are NEVER committed to git
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DEPLOY_DIR="$PROJECT_DIR/deploy"
CONFIG_FILE="$DEPLOY_DIR/$(hostname -s).env"
LOG_FILE="$DEPLOY_DIR/deploy.log"
LARAVEL_API="${LARAVEL_API_URL:-http://localhost:8082/api}"
GIT_REMOTE="${GIT_REMOTE:-origin}"
GIT_BRANCH="${GIT_BRANCH:-master}"
HEALTH_PORT="${HEALTH_PORT:-9090}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# ---- Helpers ---- #

log() { echo -e "${GREEN}[$(date '+%H:%M:%S')]${NC} $*" | tee -a "$LOG_FILE"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*" | tee -a "$LOG_FILE"; }
error() { echo -e "${RED}[ERROR]${NC} $*" | tee -a "$LOG_FILE" >&2; exit 1; }

detect_machine_role() {
    local hostname
    hostname="$(hostname -s)"
    case "$hostname" in
        *backtest*) echo "backtest" ;;
        *paper*)    echo "paper" ;;
        *live*)     echo "live" ;;
        *vps*)      echo "paper" ;;  # Default VPS = paper
        *)          echo "unknown" ;;
    esac
}

ensure_deploy_dir() {
    mkdir -p "$DEPLOY_DIR"
    if [ ! -f "$CONFIG_FILE" ]; then
        local role
        role="$(detect_machine_role)"
        if [ "$role" = "unknown" ]; then
            warn "Cannot detect machine role from hostname. Creating template config."
        fi
        cat > "$CONFIG_FILE" <<- EOF
# Machine Config — Auto-generated $(date +%Y-%m-%d)
# WARNING: Never commit this file to git!
MACHINE_ROLE=$role
OANDA_API_KEY=
OANDA_ACCOUNT_ID=
HEALTH_PORT=$HEALTH_PORT
LARAVEL_API_URL=$LARAVEL_API
STRATEGIES_DIR=$PROJECT_DIR/strategies
EOF
        log "Created config file: $CONFIG_FILE"
    fi
}

load_config() {
    ensure_deploy_dir
    # shellcheck source=/dev/null
    source "$CONFIG_FILE"
    export MACHINE_ROLE OANDA_API_KEY OANDA_ACCOUNT_ID HEALTH_PORT LARAVEL_API_URL STRATEGIES_DIR
}

git_sync() {
    log "Syncing code via git pull ($GIT_REMOTE/$GIT_BRANCH)..."
    cd "$PROJECT_DIR"
    if ! git diff --quiet HEAD 2>/dev/null; then
        warn "Local changes detected. Stashing..."
        git stash
    fi
    git pull "$GIT_REMOTE" "$GIT_BRANCH" 2>&1 | tee -a "$LOG_FILE"
    log "Git sync complete. HEAD: $(git rev-parse --short HEAD)"
}

# ---- Commands ---- #

cmd_promote() {
    local strategy_id="$1"
    local target_env="$2"

    if [ -z "$strategy_id" ] || [ -z "$target_env" ]; then
        error "Usage: deploy promote <strategy-id> <backtest|paper|live>"
    fi

    case "$target_env" in
        backtest|paper|live) ;;
        *) error "Invalid environment '$target_env'. Must be: backtest, paper, or live." ;;
    esac

    load_config

    # Step 1: Git sync before promoting
    log "Promoting $strategy_id → $target_env..."

    # Step 2: Record the promotion in Laravel
    local payload
    payload=$(cat <<- JSON
{
  "strategy_id": "$strategy_id",
  "environment": "$target_env",
  "machine": "$(hostname -s)",
  "git_commit": "$(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo 'unknown')"
}
JSON
    )

    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$LARAVEL_API/deployments" \
        -H "Content-Type: application/json" \
        -d "$payload" 2>/dev/null || echo "000")

    if [ "$http_code" = "200" ] || [ "$http_code" = "201" ]; then
        log "✅ Promotion recorded in Laravel (HTTP $http_code)"
    else
        warn "Laravel API returned $http_code — promotion recorded locally only"
        echo "$payload" >> "$DEPLOY_DIR/pending-promotions.json"
    fi

    # Step 3: Build the strategy JAR if on backtest machine
    if [ "$MACHINE_ROLE" = "backtest" ]; then
        log "Building $strategy_id..."
        (cd "$PROJECT_DIR" && mvn package -q -DskipTests 2>&1 | tee -a "$LOG_FILE")
    fi

    # Step 4: Activate via health server (if running)
    local health_url="http://localhost:${HEALTH_PORT}/health"
    if curl -sf "$health_url" > /dev/null 2>&1; then
        log "Health server responding on $health_url"
    fi

    log "✅ Promotion complete: $strategy_id → $target_env"
}

cmd_status() {
    load_config

    echo ""
    echo "=== Machine Status ==="
    echo "Hostname:    $(hostname -s)"
    echo "Role:        ${MACHINE_ROLE:-$(detect_machine_role)}"
    echo "Git commit:  $(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo 'N/A')"
    echo "Health port: ${HEALTH_PORT:-9090}"
    echo "Strategies:  ${STRATEGIES_DIR:-N/A}"
    echo ""

    # Check health endpoint
    local health_url="http://localhost:${HEALTH_PORT:-9090}/health"
    local health_status
    health_status=$(curl -sf "$health_url" 2>/dev/null || echo "DOWN")
    echo "Health server: $health_status"

    # Check git status
    cd "$PROJECT_DIR"
    if git diff --quiet HEAD 2>/dev/null; then
        echo "Git status:   clean"
    else
        echo "Git status:   ${YELLOW}uncommitted changes${NC}"
    fi
}

cmd_health() {
    # Test this machine's health
    local port="${HEALTH_PORT:-9090}"
    echo "=== Self Health Check (port $port) ==="
    curl -s "http://localhost:$port/health" 2>/dev/null || echo "Health server not running"
    echo ""

    # If we know other machines, test them too
    if [ -f "$CONFIG_FILE" ]; then
        load_config
        # TODO: read peer machines from Laravel API
    fi
}

cmd_check() {
    log "Checking for strategy promotions..."
    "$SCRIPT_DIR/cron-promote.sh" 2>&1 | tee -a "$LOG_FILE"
}

cmd_build() {
    log "Building all modules..."
    cd "$PROJECT_DIR"
    mvn package -q -DskipTests 2>&1 | tee -a "$LOG_FILE"
    log "Build complete."
}

# ---- Main ---- #

main() {
    mkdir -p "$(dirname "$LOG_FILE")"

    case "${1:-help}" in
        promote)
            shift
            cmd_promote "$@"
            ;;
        status)
            cmd_status
            ;;
        health)
            cmd_health
            ;;
        check)
            cmd_check
            ;;
        build)
            cmd_build
            ;;
        help|--help|-h)
            echo "Usage:"
            echo "  deploy promote <strategy-id> <env>   Promote strategy to environment"
            echo "  deploy status                         Show machine status"
            echo "  deploy health                         Test health endpoints"
            echo "  deploy check                           Check strategy promotions (cron)"
            echo "  deploy build                          Build all Maven modules"
            echo ""
            echo "Config in: deploy/\$(hostname -s).env"
            echo "API:       \$LARAVEL_API_URL/deployments"
            ;;
        *)
            error "Unknown command: $1. Use: promote, status, check, health, build"
            ;;
    esac
}

main "$@"
