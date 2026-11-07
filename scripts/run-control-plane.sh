#!/usr/bin/env bash
# Start the Trading Bridge control plane (HTTP :8080).
#
# Usage: ./scripts/run-control-plane.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# .env  -> creds Hermes-web (hub)
# .env.paper -> creds OANDA practice (les seules creds OANDA du repo, avec .env.live)
for f in .env .env.paper; do
  if [ -f "$f" ]; then
    echo "Loading environment variables from $f"
    set -a
    # shellcheck disable=SC1090
    . "./$f"
    set +a
  fi
done

export TRADING_BRIDGE_ROOT="${TRADING_BRIDGE_ROOT:-$ROOT}"
export CONTROL_PLANE_PORT="${CONTROL_PLANE_PORT:-8080}"
export DISABLE_STDIN_WATCHER="true"

echo "Building trading-runtime (and dependencies)…"
./mvnw -q -pl trading-runtime -am clean install -DskipTests

echo "Control plane → http://localhost:${CONTROL_PLANE_PORT}"
echo "Stop: Ctrl+C in this terminal, or: ./scripts/stop-control-plane.sh"
exec ./mvnw exec:java -pl trading-runtime \
  -Dexec.mainClass=com.martinfou.trading.runtime.ControlPlaneMain
