#!/usr/bin/env bash
# =============================================================================
# dev-lane.sh — the dev lane: one container, the -013 account, nothing at stake
#
# The point of this lane is to be the place where execution-level work happens: order paths,
# the retry question, a candidate running end to end before it is trusted with the 30-day
# window. Mistakes here cost play money on an account that has never traded.
#
# Why a script instead of a documented compose command: the guard below. The lane refuses to
# start if the dev account is the paper account, because that is the one mistake that would
# turn "safe sandbox" into "contaminating the observation window".
#
# Usage:
#   scripts/dev-lane.sh up|down|status|logs|account
#   STRATEGY=consecbar scripts/dev-lane.sh up
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.dev.yml --project-name tb-dev)

env_value() {  # env_value <file> <KEY>
  [ -f "$1" ] || return 0
  grep -E "^$2=" "$1" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'"
}

dev_account=$(env_value .env.dev OANDA_ACCOUNT_ID)
paper_account=$(env_value .env.paper OANDA_ACCOUNT_ID)

case "${1:-}" in
  up)
    if [ -z "$dev_account" ]; then
      echo "REFUSING: .env.dev has no OANDA_ACCOUNT_ID, so the dev lane would fall back to" >&2
      echo "          whatever the environment provides. That is the 2026-10-01 incident shape." >&2
      exit 1
    fi
    if [ "$dev_account" = "$paper_account" ]; then
      echo "REFUSING: the dev lane would use the observation window's account ($dev_account)." >&2
      echo "          .env.dev must point at the dev account (-013), never at paper (-014)." >&2
      exit 1
    fi
    echo "dev account: $dev_account   (paper window: $paper_account, untouched)"
    "${COMPOSE[@]}" up -d --build dev
    echo
    echo "watch it with: scripts/dev-lane.sh logs"
    ;;
  down)    "${COMPOSE[@]}" down ;;
  status)  "${COMPOSE[@]}" ps -a ;;
  logs)    "${COMPOSE[@]}" logs -f --tail 80 dev ;;
  account)
    echo "dev account:   ${dev_account:-<missing>}"
    echo "paper account: ${paper_account:-<missing>}"
    if [ -n "$dev_account" ] && [ "$dev_account" = "$paper_account" ]; then
      echo "state: BROKEN, these are the same account" >&2; exit 1
    fi
    echo "state: distinct, the lane is isolated"
    ;;
  *) echo "usage: scripts/dev-lane.sh {up|down|status|logs|account}" >&2; exit 2 ;;
esac
