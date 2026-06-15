#!/usr/bin/env bash
# Start the Trading Bridge control plane (HTTP :8080).
#
# Usage: ./scripts/run-control-plane.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ -f .env ]; then
  echo "Loading environment variables from .env"
  export $(grep -v '^#' .env | xargs)
fi

export TRADING_BRIDGE_ROOT="${TRADING_BRIDGE_ROOT:-$ROOT}"
export CONTROL_PLANE_PORT="${CONTROL_PLANE_PORT:-8080}"

echo "Building trading-runtime (and dependencies)…"
mvn -q -pl trading-runtime -am install -DskipTests

echo "Control plane → http://localhost:${CONTROL_PLANE_PORT}"
echo "Stop: Ctrl+C in this terminal, or: ./scripts/stop-control-plane.sh"
exec mvn exec:java -pl trading-runtime \
  -Dexec.mainClass=com.martinfou.trading.runtime.ControlPlaneMain
