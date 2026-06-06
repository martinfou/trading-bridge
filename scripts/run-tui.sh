#!/usr/bin/env bash
# Start the Trading Bridge TUI (requires control plane on CONTROL_PLANE_URL).
#
# Usage:
#   ./scripts/run-tui.sh
#   ./scripts/run-tui.sh --with-control-plane   # start control plane in background first
# Stop orphaned servers: ./scripts/stop-control-plane.sh
#
# Environment:
#   CONTROL_PLANE_URL   default http://localhost:8080
#   CONTROL_PLANE_PORT  default 8080 (used when URL unset)
#   TRADING_BRIDGE_ROOT repo root for historical data discovery (default: repo root)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export TRADING_BRIDGE_ROOT="${TRADING_BRIDGE_ROOT:-$ROOT}"
export CONTROL_PLANE_PORT="${CONTROL_PLANE_PORT:-8080}"
export CONTROL_PLANE_URL="${CONTROL_PLANE_URL:-http://localhost:${CONTROL_PLANE_PORT}}"

WITH_CP=false
for arg in "$@"; do
  case "$arg" in
    --with-control-plane|-c) WITH_CP=true ;;
    -h|--help)
      sed -n '2,12p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg (try --help)" >&2
      exit 1
      ;;
  esac
done

control_plane_healthy() {
  curl -sf "${CONTROL_PLANE_URL}/api/health" >/dev/null 2>&1
}

CP_PID=""
CP_PGID=""
stop_background_control_plane() {
  if [[ -n "${CP_PGID}" ]]; then
    kill -TERM "-${CP_PGID}" 2>/dev/null || true
  elif [[ -n "${CP_PID}" ]] && kill -0 "${CP_PID}" 2>/dev/null; then
    kill -TERM "${CP_PID}" 2>/dev/null || true
    wait "${CP_PID}" 2>/dev/null || true
  fi
  # Maven often survives when only the shell job is killed; stop the JVM explicitly.
  "${ROOT}/scripts/stop-control-plane.sh" 2>/dev/null || true
}
cleanup() {
  stop_background_control_plane
}
trap cleanup EXIT INT TERM

if [[ "${WITH_CP}" == true ]]; then
  if control_plane_healthy; then
    if ! curl -sf "${CONTROL_PLANE_URL}/api/health" 2>/dev/null | grep -q '"dataCatalog"'; then
      echo "Control plane is up but missing data catalog API — restarting from current sources…" >&2
      "${ROOT}/scripts/stop-control-plane.sh" 2>/dev/null || true
      sleep 1
    else
      echo "Control plane already up at ${CONTROL_PLANE_URL}"
    fi
  fi
  if ! control_plane_healthy; then
    echo "Starting control plane (background)…"
    mvn -q -pl trading-runtime -am install -DskipTests
    # Own process group so Ctrl+C on the TUI can signal the whole Maven + JVM tree.
    set -m
    mvn -q exec:java -pl trading-runtime \
      -Dexec.mainClass=com.martinfou.trading.runtime.ControlPlaneMain &
    CP_PID=$!
    CP_PGID=$(ps -o pgid= -p "${CP_PID}" 2>/dev/null | tr -d ' ' || true)
    for _ in $(seq 1 45); do
      if control_plane_healthy; then
        echo "Control plane ready at ${CONTROL_PLANE_URL}"
        break
      fi
      if ! kill -0 "${CP_PID}" 2>/dev/null; then
        echo "Control plane exited unexpectedly." >&2
        exit 1
      fi
      sleep 1
    done
    if ! control_plane_healthy; then
      echo "Timed out waiting for control plane at ${CONTROL_PLANE_URL}" >&2
      exit 1
    fi
  fi
elif ! control_plane_healthy; then
  echo "Warning: control plane not reachable at ${CONTROL_PLANE_URL}" >&2
  echo "  Start it in another terminal:" >&2
  echo "    mvn exec:java -pl trading-runtime \\" >&2
  echo "      -Dexec.mainClass=com.martinfou.trading.runtime.ControlPlaneMain" >&2
  echo "  Or re-run: ./scripts/run-tui.sh --with-control-plane" >&2
  echo ""
fi

echo "Trading Bridge TUI → ${CONTROL_PLANE_URL}"
exec mvn exec:java -pl trading-tui \
  -Dexec.mainClass=com.martinfou.trading.tui.TradingTuiMain
