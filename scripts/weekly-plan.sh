#!/usr/bin/env bash
# Job 1 — weekly intel ingest (+ LLM plan in story 22.2).
#
# Cron: Friday 17:00 UTC ; retry Saturday 10:00 UTC if Friday ingest incomplete.
#
# Usage: ./scripts/weekly-plan.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export TRADING_BRIDGE_ROOT="${TRADING_BRIDGE_ROOT:-$ROOT}"

exec mvn -q exec:java -pl trading-intelligence \
  -Dexec.mainClass=com.martinfou.trading.intelligence.WeeklyPlanJobMain
