#!/bin/bash
# =============================================================================
# sq-nightly.sh — StrategyQuant nightly pipeline (story 21-6)
# =============================================================================
# Runs sqcli maintenance (update-data, list-databanks) under mutex, optionally
# imports SQ exports into data/sq-inbox/pending/, then drains the inbox.
#
# Requires SQ_HOME for real sqcli runs. Use --dry-run for offline validation.
#
# Cron example (08:00 daily, prevent sleep during run on Mac):
#   0 8 * * * SQ_HOME=$HOME/sq-bridge/SQ_HOME SQ_EXPORT_DIR=$HOME/sq-exports \
#     /path/to/trading-bridge/scripts/sq-nightly.sh --caffeinate
#
# launchd: wrap the same command in a StartCalendarInterval plist.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

CAFFEINATE=false
ARGS=()
for arg in "$@"; do
  if [ "$arg" = "--caffeinate" ]; then
    CAFFEINATE=true
  else
    ARGS+=("$arg")
  fi
done

EXEC_ARGS=""
if [ "${#ARGS[@]}" -gt 0 ]; then
  EXEC_ARGS="${ARGS[*]}"
fi

cd "$PROJECT_DIR"

run_pipeline() {
  if [ -n "$EXEC_ARGS" ]; then
    mvn -q exec:java -pl trading-parser \
      -Dexec.mainClass=com.martinfou.trading.parser.bridge.SqNightlyPipeline \
      -Dexec.args="$EXEC_ARGS"
  else
    mvn -q exec:java -pl trading-parser \
      -Dexec.mainClass=com.martinfou.trading.parser.bridge.SqNightlyPipeline
  fi
}

if [ "$CAFFEINATE" = true ] && command -v caffeinate >/dev/null 2>&1; then
  caffeinate -i bash -c "$(declare -f run_pipeline); run_pipeline"
else
  run_pipeline
fi
