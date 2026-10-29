#!/bin/bash
# ============================================================================
# Trading Bridge — RETIRED deployment helper
# ============================================================================
# This script used to pretend to be a deployment pipeline. It never was one:
#
#   * DEPLOY_LOG was /dev/null, so `status` printed "Aucun déploiement" and
#     `history` printed "Aucun historique" no matter what was live (2026-10-29).
#   * `rollback <id>` could never find an id, so it exited 1 with
#     "Deployment not found" and a rollback path that only ever marked a status
#     inside a file nobody wrote.
#   * `promote` validated against a hardcoded all-true CHECKS_JSON, then printed
#     "✅ Deployment complete" without touching a container, a lane, or the DB.
#
# A safety net that looks alive and is not is worse than admitted downtime: the
# operator believes they can roll back. The false paths are therefore gone, and
# every command now refuses and names the tool that does the real job.
#
# THE REAL TOOLS
#   Deploy to paper      scripts/deploy-paper.sh          (dry run by default,
#                                                         --apply to act)
#   Pre-deploy gate      scripts/pre-deploy-gate.sh       (build + tests +
#                                                         compose + OANDA smoke,
#                                                         must exit 0)
#   Weekly bundle        scripts/weekly-deploy.sh
#   Runbook (the prose)  docs/deploy-runbook.md
#   Guardrails           docs/TRADING-GUARDRAILS.md
#
# WHERE THE TRUTH LIVES
#   Deployment + lifecycle state is in the Laravel DB of hermes-web, not here.
#   Read it on the /trading dashboard (Dev -> Paper -> Prod lanes) or through
#   GET /api/trading/status. trading-bridge only pushes snapshots to it
#   (POST /api/trading/operations/snapshot, POST /api/trading/broker/snapshot).
#
# STILL MISSING, and it needs a human decision (not a bash script):
#   There is no rollback command anywhere in the stack. Rolling a lane back
#   means either (a) git checkout the previous commit and re-run
#   scripts/deploy-paper.sh --apply on the affected service, which restores the
#   runner state it backed up, or (b) flip the strategy back to its previous
#   lane through the 1-click control on /trading. Pick one, then wire it into the
#   runbook. Until then a rollback is a manual, hand-checked operation against
#   the broker, and this script will not claim otherwise.
# ============================================================================

set -euo pipefail
cd "$(dirname "$0")/.."

RED=$'\033[0;31m'
YELLOW=$'\033[1;33m'
NC=$'\033[0m'

retired() {
    local what=$1 real=$2
    echo -e "${RED}✋ deploy.sh $what is retired.${NC}" >&2
    echo    "   It never did the work it claimed: its journal was /dev/null, so it" >&2
    echo    "   could neither read nor record a deployment." >&2
    echo -e "${YELLOW}   Use instead: $real${NC}" >&2
    exit 2
}

show_help() {
    cat << 'HELP'
Trading Bridge — deploy.sh (retired)

This script does nothing on purpose. It was retired on 2026-10-29 because its
journal was /dev/null: `status` and `history` always printed nothing, `rollback`
always failed, and `promote` printed success without deploying.

Use these instead:
  scripts/deploy-paper.sh        deploy paper containers, dry run by default
                                 (--apply to act, --skip-gate says so loudly)
  scripts/pre-deploy-gate.sh     build + tests + compose + OANDA smoke, must exit 0
  scripts/weekly-deploy.sh       weekly compiled-bundle deploy
  docs/deploy-runbook.md         the procedure, including the state trap
  docs/TRADING-GUARDRAILS.md     the gates and the review layers

Deployment and lifecycle state live in the hermes-web Laravel DB:
  /trading (dashboard lanes)  |  GET /api/trading/status

Rollback: no command exists. See the header of this file.
HELP
}

case "${1:-help}" in
    promote)  retired promote "scripts/deploy-paper.sh, after scripts/pre-deploy-gate.sh exits 0" ;;
    status)   retired status  "the /trading dashboard lanes, or GET /api/trading/status" ;;
    history)  retired history "the /trading dashboard P&L tab, or the hermes-web Laravel DB" ;;
    rollback) retired rollback "nothing yet: no rollback command exists (see the header of this file)" ;;
    validate) retired validate "scripts/pre-deploy-gate.sh" ;;
    *)        show_help ;;
esac
