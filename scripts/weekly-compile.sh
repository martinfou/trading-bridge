#!/usr/bin/env bash
# Job 2 — poll pending/ and compile weekly plans (story 22.4).
#
# Cron: every 1–5 min while pending/ has plans, or continuous watcher daemon.
#
# Usage: ./scripts/weekly-compile.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export TRADING_BRIDGE_ROOT="${TRADING_BRIDGE_ROOT:-$ROOT}"

exec mvn -q exec:java -pl trading-intelligence \
  -Dexec.mainClass=com.martinfou.trading.intelligence.compile.WeeklyCompileWatcherMain
