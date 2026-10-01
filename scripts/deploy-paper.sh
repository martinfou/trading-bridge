#!/usr/bin/env bash
# =============================================================================
# deploy-paper.sh — deploy the paper containers WITHOUT losing runner state
#
# This is the script the pre-deploy gate and docs/TRADING-GUARDRAILS.md already
# promised; docs/deploy-runbook.md is its prose form. It exists because the state
# of each strategy lives in /tmp INSIDE the container: recreating the container
# erases the runner's memory, so it restarts believing it holds nothing while the
# broker still holds a position. See docs/deploy-runbook.md for the incident.
#
# Safety properties, in order:
#   1. DRY RUN BY DEFAULT. Nothing is touched without --apply.
#   2. The gate must pass. --skip-gate exists and says loudly what it skipped.
#   3. State is copied OUT of the live container before anything is recreated.
#   4. State is restored while the new container is STOPPED (--no-start), because
#      restoring into a running container races the runner's own startup.
#   5. Only strategy-state files are restored. Monitor/status files are NOT, because
#      the runner recreates them and a restored one can be owned by another user,
#      leaving the runner unable to write while it keeps trading (runbook trap).
#   6. Success is verified against the BROKER, not against the log alone.
#
# Usage:
#   scripts/deploy-paper.sh                 # dry run, every running paper service
#   scripts/deploy-paper.sh --apply         # actually deploy
#   scripts/deploy-paper.sh --apply trader  # one service (trader|month-week|...)
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'; CYAN=$'\033[0;36m'; NC=$'\033[0m'
APPLY=0
SKIP_GATE=0
SERVICES=()
BACKUP_DIR="${TMPDIR:-/tmp}/bridge-state-backup"

for arg in "$@"; do
  case "$arg" in
    --apply)     APPLY=1 ;;
    --skip-gate) SKIP_GATE=1 ;;
    -h|--help)   sed -n '2,28p' "$0"; exit 0 ;;
    -*)          echo "${RED}unknown flag: $arg${NC}"; exit 2 ;;
    *)           SERVICES+=("$arg") ;;
  esac
done

run() {
  if [ "$APPLY" = 1 ]; then "$@"; else echo "      [dry-run] $*"; fi
}

echo "${CYAN}═══ paper deploy — $([ "$APPLY" = 1 ] && echo APPLY || echo 'DRY RUN (nothing will change)') ═══${NC}"

# ---------------------------------------------------------------- 1. the gate
if [ "$SKIP_GATE" = 1 ]; then
  echo "${YELLOW}⚠ --skip-gate: the pre-deploy gate was NOT run. A deploy without a green${NC}"
  echo "${YELLOW}  gate is a process failure, not a shortcut.${NC}"
else
  echo "→ pre-deploy gate"
  if [ "$APPLY" = 1 ]; then
    bash scripts/pre-deploy-gate.sh || { echo "${RED}✗ gate failed — NOT deploying${NC}"; exit 1; }
  else
    echo "      [dry-run] bash scripts/pre-deploy-gate.sh"
  fi
fi

# ------------------------------------------------------- 2. what are we doing
if [ "${#SERVICES[@]}" -eq 0 ]; then
  mapfile -t SERVICES < <(docker compose ps --services --status running 2>/dev/null | sort)
fi
[ "${#SERVICES[@]}" -gt 0 ] || {
  echo "${RED}✗ no service found. If the stack is stopped, name the services explicitly:${NC}"
  echo "   scripts/deploy-paper.sh --apply trader month-week comp-momentum lt-rsi3"
  exit 1
}
echo "→ services: ${SERVICES[*]}"

# The account every paper container uses, so verification asks the right broker.
ACC=$(python3 -c "
import os,re
p='.env.paper'
print(next((l.split('=',1)[1].strip() for l in open(p) if l.startswith('OANDA_ACCOUNT_ID=')),'') if os.path.exists(p) else '')
")
[ -n "$ACC" ] || { echo "${RED}✗ OANDA_ACCOUNT_ID not found in .env.paper${NC}"; exit 1; }
echo "→ broker account: $ACC"

# ------------------------------------------------------------------ 3. build
echo "→ building the image once for every service"
run docker compose build

# --------------------------------------------------- 4. copy state OUT first
echo "→ copying strategy state out of the LIVE containers"
run mkdir -p "$BACKUP_DIR"
for svc in "${SERVICES[@]}"; do
  if [ "$APPLY" = 1 ]; then
    cid=$(docker compose ps -aq "$svc" | head -1)
    if [ -z "$cid" ]; then echo "   $svc: no container, skipped"; continue; fi
    st=$(docker inspect "$cid" --format '{{.State.Status}}')
    # Look at EVERY container, not only running ones, and use docker cp rather than docker exec:
    # docker cp works on a stopped container, and recreating a stopped container would otherwise
    # silently discard a position the runner still has to re-adopt. Found by deploying a paused stack.
    tmp=$(mktemp -d)
    docker cp "$cid:/tmp/." "$tmp/" >/dev/null 2>&1 || true
    found=0
    for f in "$tmp"/live-strategy-state-*.json; do
      [ -e "$f" ] || continue
      cp "$f" "$BACKUP_DIR/$svc-$(basename "$f")"; found=1
      echo "   $svc ($st): saved $(basename "$f")"
    done
    [ "$found" = 1 ] || echo "   $svc ($st): no strategy-state file to save"
    rm -rf "$tmp"
  else
    echo "      [dry-run] docker cp <state files> from $svc -> $BACKUP_DIR/$svc-*"
  fi
done

# ------------------------------------- 5. recreate STOPPED, restore, then start
for svc in "${SERVICES[@]}"; do
  echo "→ $svc"
  run docker compose up -d --no-start --force-recreate "$svc"
  if [ "$APPLY" = 1 ]; then
    new=$(docker compose ps -aq "$svc" | head -1)
    st=$(docker inspect "$new" --format '{{.State.Status}}')
    if [ "$st" != "created" ]; then
      echo "   ${RED}✗ expected status 'created', got '$st' — STOP. Restore manually before starting.${NC}"
      exit 1
    fi
    # only strategy state goes back; monitor/status files are left for the runner to recreate
    for f in "$BACKUP_DIR/$svc"-live-strategy-state-*.json; do
      [ -e "$f" ] || continue
      docker cp "$f" "$new:/tmp/$(basename "$f" | sed "s/^$svc-//")" >/dev/null
      echo "   restored $(basename "$f")"
    done
    docker start "$new" >/dev/null
    echo "   started $new"
  else
    echo "      [dry-run] verify status=created, restore state, docker start"
  fi
done

# ------------------------------------------------------ 6. verify vs the broker
echo "→ verification"
if [ "$APPLY" = 1 ]; then
  sleep 25
  broker=$(python3 - "$ACC" <<'PY'
import json,os,sys,urllib.request
acc=sys.argv[1]; env={}
for l in open('.env.paper'):
    if '=' in l and not l.startswith('#'):
        k,v=l.strip().split('=',1); env[k]=v
req=urllib.request.Request(f"https://api-fxpractice.oanda.com/v3/accounts/{acc}/openTrades",
                           headers={"Authorization":f"Bearer {env['OANDA_API_KEY']}"})
print(len(json.load(urllib.request.urlopen(req,timeout=20)).get('trades',[])))
PY
)
  echo "   broker reports $broker open trade(s) on $ACC"
  bad=0
  for svc in "${SERVICES[@]}"; do
    cid=$(docker compose ps -q "$svc" | head -1)
    resumed=$(docker logs "$cid" 2>&1 | grep -aoE 'Resumed state: [0-9]+ active trades' | tail -1 || true)
    perm=$(docker logs "$cid" 2>&1 | grep -ac "Failed to write aggregated monitor" || true)
    echo "   $svc: ${resumed:-'(no Resume line yet)'} | monitor write failures: $perm"
    [ "$perm" = "0" ] || bad=1
    # local trades must never exceed the positions the broker actually holds
    n=$(printf '%s' "$resumed" | grep -oE '[0-9]+' | head -1)
    if [ -n "$n" ] && [ "$n" -gt "$broker" ]; then
      echo "   ${RED}✗ $svc claims $n active trade(s) but the broker holds $broker — orphan state${NC}"; bad=1
    fi
  done
  [ "$bad" = 0 ] && echo "${GREEN}✓ deployment verified${NC}" || { echo "${RED}✗ verification FAILED — inspect before trusting${NC}"; exit 1; }
else
  echo "      [dry-run] compare each runner's 'Resumed state: N' against the broker's open trades"
fi
