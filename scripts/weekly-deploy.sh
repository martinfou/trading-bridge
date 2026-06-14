#!/usr/bin/env bash
# Job 3 — deploy compiled/ bundle to PAPER_OANDA via control plane.
#
# Cron: after successful compile, or Monday 00:05 UTC (configurable).
# Requires control plane: ./scripts/run-control-plane.sh
#
# Usage: ./scripts/weekly-deploy.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export TRADING_BRIDGE_ROOT="${TRADING_BRIDGE_ROOT:-$ROOT}"
export CONTROL_PLANE_URL="${CONTROL_PLANE_URL:-http://localhost:${CONTROL_PLANE_PORT:-8080}}"

exec mvn -q exec:java -pl trading-intelligence \
  -Dexec.mainClass=com.martinfou.trading.intelligence.deploy.WeeklyDeployWatcherMain
