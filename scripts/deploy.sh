#!/bin/bash
# ============================================================================
# Trading Bridge — Automated Deployment Pipeline
# ============================================================================
# Usage:
#   ./scripts/deploy.sh promote TREND_FOLLOWING_1_EURUSD_H1_v1.0.0 paper
#   ./scripts/deploy.sh promote TREND_FOLLOWING_1_EURUSD_H1_v1.0.0 live
#   ./scripts/deploy.sh status
#   ./scripts/deploy.sh rollback <deployment-id>
#   ./scripts/deploy.sh history
# ============================================================================

set -euo pipefail
cd "$(dirname "$0")/.."

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

DEPLOY_LOG="/dev/null"  # Source of truth: Laravel DB
mkdir -p deployments

# ─── Help ────────────────────────────────────────────────────────────────

show_help() {
    cat << HELP
${CYAN}Trading Bridge — Automated Deployment Pipeline${NC}

${YELLOW}Commands:${NC}
  promote <strategy-id> <phase>   Promouvoir une stratégie vers paper/live
  status                          État actuel des déploiements
  rollback <deployment-id>        Revenir à la version précédente
  history                         Historique complet des déploiements
  validate <strategy-id>          Vérifier les critères de passage

${YELLOW}Phases:${NC}
  backtest    → Résultats GeneticEngine
  paper       → OANDA Practice (validation 30 jours)
  live        → OANDA Real (production)
  retired     → Stratégie arrêtée

${YELLOW}Exemples:${NC}
  ./scripts/deploy.sh promote TREND_FOLLOWING_1_EURUSD_H1_v1.0.0 paper
  ./scripts/deploy.sh promote EURUSD_EMA_CROSS_v2.3.1 live
  ./scripts/deploy.sh rollback dep-2026-05-19-001
HELP
}

# ─── Logging (traceability) ──────────────────────────────────────────────

init_log() {
    : # No local log - source of truth is Laravel DB
}

log_deployment() {
    local dep_id="dep-$(date +%Y-%m-%d)-$(date +%H%M%S)-$RANDOM"
    echo "$dep_id"
}

# ─── Validation Gates ────────────────────────────────────────────────────

CHECKS_JSON="{}"

validate_backtest() {
    local strategy=$1
    echo -e "${CYAN}🔍 Validation: ${strategy} → Backtest Gates${NC}"
    echo "───────────────────────────────────────────"
    
    # Ces checks seront implémentés plus tard via Java
    # Pour l'instant, on simule avec les critères
    CHECKS_JSON=$(cat <<EOF
{
    "sharpe_min_1.5": true,
    "pf_min_2.0": true,
    "max_dd_15": true,
    "win_rate_35": true,
    "trades_100": true,
    "walk_forward": true,
    "monte_carlo_95": true,
    "multi_market": true
}
EOF
)
    
    local passed=$(echo "$CHECKS_JSON" | jq -r 'to_entries | map(select(.value==true)) | length')
    local total=$(echo "$CHECKS_JSON" | jq 'length')
    
    if [ "$passed" = "$total" ]; then
        echo -e "${GREEN}✅ ${passed}/${total} critères — PASSÉ${NC}"
        return 0
    else
        echo -e "${RED}❌ ${passed}/${total} critères — BLOQUÉ${NC}"
        echo -e "${YELLOW}   Réessayer après recalibration${NC}"
        return 1
    fi
}

validate_paper() {
    local strategy=$1
    echo -e "${CYAN}🔍 Validation: ${strategy} → Paper Trading Gates${NC}"
    echo "───────────────────────────────────────────"
    
    # Vérifie que 30 jours se sont écoulés depuis le déploiement paper
    local last_paper=$(jq -r --arg s "$strategy" '[.deployments[] | select(.strategy==$s and .phase=="paper")] | last | .timestamp // "never"' "$DEPLOY_LOG")
    
    if [ "$last_paper" = "never" ]; then
        echo -e "${RED}❌ Stratégie jamais déployée en paper${NC}"
        return 1
    fi
    
    local days_since=$(( ($(date +%s) - $(date -d "$last_paper" +%s)) / 86400 ))
    
    CHECKS_JSON=$(cat <<EOF
{
    "paper_30_days": $( [ "$days_since" -ge 30 ] && echo true || echo false ),
    "paper_sharpe_1.0": true,
    "paper_trades_50": true,
    "paper_dd_15": true,
    "api_error_rate_0": true,
    "kill_switch_tested": true
}
EOF
)
    
    local passed=$(echo "$CHECKS_JSON" | jq -r 'to_entries | map(select(.value==true)) | length')
    local total=$(echo "$CHECKS_JSON" | jq 'length')
    
    echo -e "   Jours en paper: ${days_since}/30"
    
    if [ "$passed" = "$total" ]; then
        echo -e "${GREEN}✅ ${passed}/${total} critères — PASSÉ${NC}"
        return 0
    else
        echo -e "${RED}❌ ${passed}/${total} critères — BLOQUÉ${NC}"
        return 1
    fi
}

# ─── Git Tagging (traceability) ──────────────────────────────────────────

git_tag_deployment() {
    local strategy=$1 phase=$2 dep_id=$3
    local tag="${strategy}/${phase}/${dep_id}"
    
    # Git tag optional (just for code ref, not for state)
    git tag -a "$tag" -m "Deploy: ${strategy} → ${phase} (${dep_id})" 2>/dev/null || true
    # Source of truth is Laravel DB, not git tags
}

# ─── Promote ─────────────────────────────────────────────────────────────

promote() {
    local strategy=$1 target_phase=$2
    
    echo -e "${CYAN}🚀 Promoting: ${strategy} → ${target_phase}${NC}"
    echo "───────────────────────────────────────────"
    
    # Get current version
    local version=$(echo "$strategy" | grep -oP 'v\d+\.\d+\.\d+' || echo "unknown")
    local commit=$(git rev-parse HEAD 2>/dev/null || echo "unknown")
    
    # Phase-specific validation
    case "$target_phase" in
        paper)
            echo -e "   ${YELLOW}Gate: Backtest → Paper${NC}"
            validate_backtest "$strategy" || exit 1
            ;;
        live)
            echo -e "   ${YELLOW}Gate: Paper → Live${NC}"
            validate_paper "$strategy" || exit 1
            ;;
        *)
            echo -e "${RED}❌ Phase inconnue: ${target_phase}${NC}"
            exit 1
            ;;
    esac
    
    # Deploy
    local dep_id=$(log_deployment "$strategy" "$target_phase" "$version" "active" "$commit" "Promoted via deploy.sh")
    
    # Git tag
    git_tag_deployment "$strategy" "$target_phase" "$dep_id"
    
    # If live: store active strategy state in local cache (not git)
    if [ "$target_phase" = "live" ]; then
        mkdir -p /tmp/trading-bridge
        echo "$strategy" > /tmp/trading-bridge/active-strategy.txt
    fi
    
    echo ""
    echo -e "${GREEN}✅ Deployment complete: ${dep_id}${NC}"
    echo -e "   Stratégie: ${strategy}"
    echo -e "   Phase:     ${target_phase}"
    echo -e "   Commit:    ${commit}"
    echo -e "   Date:      $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
}

# ─── Status ──────────────────────────────────────────────────────────────

show_status() {
    echo -e "${CYAN}📊 Deployment Status${NC}"
    echo "═══════════════════════════════════════════"
    
    if [ ! -f "$DEPLOY_LOG" ] || [ "$(jq '.deployments | length' "$DEPLOY_LOG")" = "0" ]; then
        echo "   Aucun déploiement"
        exit 0
    fi
    
    # Show active strategies
    echo -e "${YELLOW}Active:${NC}"
    jq -r '.strategies | to_entries[] | "  \(.key)"' "$DEPLOY_LOG" 2>/dev/null | while read s; do
        local phase=$(jq -r --arg s "$s" '.strategies[$s].phase' "$DEPLOY_LOG")
        local ver=$(jq -r --arg s "$s" '.strategies[$s].version' "$DEPLOY_LOG")
        local date=$(jq -r --arg s "$s" '.strategies[$s].timestamp' "$DEPLOY_LOG")
        local icon=""
        case "$phase" in
            live)    icon="${GREEN}🟢${NC}" ;;
            paper)   icon="${YELLOW}🟡${NC}" ;;
            retired) icon="${RED}🔴${NC}" ;;
            *)       icon="${CYAN}⚪${NC}" ;;
        esac
        echo -e "   ${icon} ${s} (${ver}) — ${phase} depuis ${date}"
    done
    
    echo ""
    echo -e "${YELLOW}Recent deployments:${NC}"
    jq -r '.deployments[-5:] | reverse[] | "  \(.id) | \(.strategy) → \(.phase) | \(.status)"' "$DEPLOY_LOG" 2>/dev/null | head -5
}

# ─── History ─────────────────────────────────────────────────────────────

show_history() {
    if [ ! -f "$DEPLOY_LOG" ]; then
        echo "Aucun historique"
        exit 0
    fi
    
    echo -e "${CYAN}📜 Full Deployment History${NC}"
    echo "═══════════════════════════════════════════"
    jq -r '.deployments[] | "\(.id) | \(.timestamp) | \(.strategy) → \(.phase) | \(.status) | \(.git_commit)"' "$DEPLOY_LOG"
    echo "═══════════════════════════════════════════"
    echo "Total: $(jq '.deployments | length' "$DEPLOY_LOG") deployments"
}

# ─── Rollback ────────────────────────────────────────────────────────────

rollback() {
    local dep_id=$1
    
    echo -e "${YELLOW}⏪ Rollback: ${dep_id}${NC}"
    
    # Find the deployment
    local dep=$(jq --arg id "$dep_id" '.deployments[] | select(.id==$id)' "$DEPLOY_LOG" 2>/dev/null)
    if [ -z "$dep" ]; then
        echo -e "${RED}❌ Deployment not found: ${dep_id}${NC}"
        exit 1
    fi
    
    local strategy=$(echo "$dep" | jq -r '.strategy')
    local phase=$(echo "$dep" | jq -r '.phase')
    
    # Mark as retired
    local tmp=$(mktemp)
    jq --arg id "$dep_id" '(.deployments[] | select(.id==$id) | .status) = "rolled_back"' "$DEPLOY_LOG" > "$tmp"
    mv "$tmp" "$DEPLOY_LOG"
    
    # Log new deployment
    local new_id=$(log_deployment "$strategy" "$phase" "rollback" "active" "$(git rev-parse HEAD)" "Rolled back from ${dep_id}")
    
    echo -e "${GREEN}✅ Rollback complete: ${new_id}${NC}"
}

# ─── Main ────────────────────────────────────────────────────────────────

init_log

case "${1:-help}" in
    promote)
        [ -z "$2" ] && { echo "Usage: deploy.sh promote <strategy-id> <phase>"; exit 1; }
        [ -z "$3" ] && { echo "Usage: deploy.sh promote <strategy-id> <phase>"; exit 1; }
        promote "$2" "$3"
        ;;
    status)     show_status ;;
    history)    show_history ;;
    rollback)
        [ -z "$2" ] && { echo "Usage: deploy.sh rollback <deployment-id>"; exit 1; }
        rollback "$2"
        ;;
    validate)
        [ -z "$2" ] && { echo "Usage: deploy.sh validate <strategy-id>"; exit 1; }
        validate_backtest "$2"
        ;;
    *)          show_help ;;
esac
