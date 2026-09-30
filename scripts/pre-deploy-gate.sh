#!/usr/bin/env bash
# =============================================================================
# Pre-deploy gate — trading-bridge
#
# Nothing reaches the paper containers unless this exits 0.
#   1. full build + test suite
#   2. docker compose sanity
#   3. OANDA read-only smoke test (credentials + account reachable)
#
# Independent review by a second agent is still required before merging to the
# default branch — see docs/TRADING-GUARDRAILS.md (layer 4).
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

echo "═══ 1/3  build + full test suite ═══"
./mvnw -B -DskipITs test
echo "   → green"

echo
echo "═══ 2/3  docker compose config ═══"
docker compose config >/dev/null
echo "   → valid"

echo
echo "═══ 3/3  OANDA read-only smoke test ═══"
python3 scripts/smoke-oanda-readonly.py

echo
echo "✅ GATE PASSED — safe to deploy with scripts/deploy-paper.sh"
echo "   (independent review of the diff is still required before merge)"
