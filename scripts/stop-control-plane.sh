#!/usr/bin/env bash
# Stop any Trading Bridge control plane (Maven exec:java or JVM main class).
#
# Usage:
#   ./scripts/stop-control-plane.sh
#   ./scripts/stop-control-plane.sh --check-port
#   CONTROL_PLANE_PORT=9090 ./scripts/stop-control-plane.sh --check-port

set -euo pipefail

PORT="${CONTROL_PLANE_PORT:-8080}"
CHECK_PORT=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check-port) CHECK_PORT=true; shift ;;
    -h|--help)
      sed -n '2,9p' "$0"
      exit 0
      ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

stopped=0

stop_pattern() {
  local pattern="$1"
  local pids
  pids=$(pgrep -f "$pattern" 2>/dev/null || true)
  if [[ -z "$pids" ]]; then
    return
  fi
  echo "Stopping (SIGTERM): $pattern"
  # shellcheck disable=SC2086
  kill -TERM $pids 2>/dev/null || true
  stopped=1
}

stop_pattern 'com.martinfou.trading.runtime.ControlPlaneMain'
stop_pattern 'exec:java -pl trading-runtime.*ControlPlaneMain'

sleep 1

for pattern in \
  'com.martinfou.trading.runtime.ControlPlaneMain' \
  'exec:java -pl trading-runtime.*ControlPlaneMain'
do
  pids=$(pgrep -f "$pattern" 2>/dev/null || true)
  if [[ -n "$pids" ]]; then
    echo "Force stopping (SIGKILL): $pattern"
    # shellcheck disable=SC2086
    kill -KILL $pids 2>/dev/null || true
    stopped=1
  fi
done

if [[ "$stopped" -eq 0 ]]; then
  echo "No control plane process found."
else
  echo "Control plane stopped."
fi

if [[ "$CHECK_PORT" == true ]]; then
  if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Port $PORT is still in use:" >&2
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >&2 || true
    exit 1
  fi
  echo "Port $PORT is free."
fi
