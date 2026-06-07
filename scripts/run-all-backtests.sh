#!/usr/bin/env bash
# Runs batch backtests of all strategies on all pairs.
#
# Usage: ./scripts/run-all-backtests.sh
#

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "Compiling trading modules..."
mvn compile -pl trading-examples -am -q

echo "Running all backtests..."
exec mvn exec:java -pl trading-examples \
  -Dexec.mainClass=com.martinfou.trading.examples.RunAllBatchBacktests
