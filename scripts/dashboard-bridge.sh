#!/usr/bin/env bash
# ============================================================================
#  📡 Trading Bridge → Dashboard Sync Script
#  Run backtest with the advanced trading-bridge engine and sync results
#  to the trading-dashboard Laravel app for display.
#
#  Usage:
#    ./dashboard-bridge.sh --sample              # Run sample backtest
#    ./dashboard-bridge.sh <csv-file> [symbol]   # Run real backtest
#    ./dashboard-bridge.sh --sample --sync       # Run + sync to dashboard
#
#  Output: JSON report matching the schema expected by trading-dashboard
#  BacktestController.
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DASHBOARD_DIR="/home/martinfou/projects/trading-dashboard"
DASHBOARD_STORAGE="${DASHBOARD_DIR}/storage/app/backtest-results"
OUTPUT_DIR="${PROJECT_DIR}/_bmad-output/implementation-artifacts/backtest-reports"

DO_SYNC=false
ARGS=()

for arg in "$@"; do
    case "$arg" in
        --sync) DO_SYNC=true ;;
        *) ARGS+=("$arg") ;;
    esac
done

# ── Build the project (skip tests for speed) ──
echo "🔨 Building trading-bridge..."
cd "$PROJECT_DIR"
mvn package -DskipTests -q 2>&1 | tail -3 || {
    echo "❌ Build failed. Aborting."
    exit 1
}

# ── Run backtest with JSON export ──
echo "🏃 Running backtest..."
MAVEN_OPTS="${MAVEN_OPTS:-}" \
mvn exec:java -pl trading-examples \
    -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
    -Dexec.args="--json --output-dir ${OUTPUT_DIR} ${ARGS[*]}" \
    -q 2>&1 | tail -5

echo ""
echo "📄 Reports in: ${OUTPUT_DIR}"
ls -la "${OUTPUT_DIR}"/*.json 2>/dev/null || echo "  (no JSON files yet)"

# ── Sync to dashboard ──
if [ "$DO_SYNC" = true ]; then
    echo ""
    echo "📡 Syncing to trading-dashboard storage..."
    mkdir -p "${DASHBOARD_STORAGE}"
    cp -v "${OUTPUT_DIR}"/*_report.json "${DASHBOARD_STORAGE}/" 2>/dev/null || echo "  (no JSON files to copy)"
    cp -v "${OUTPUT_DIR}"/*_report.html "${DASHBOARD_STORAGE}/" 2>/dev/null || echo "  (no HTML files to copy)"
    cp -v "${OUTPUT_DIR}"/*_report.csv "${DASHBOARD_STORAGE}/" 2>/dev/null || echo "  (no CSV files to copy)"

    echo ""
    echo "📊 Backtest results in dashboard storage:"
    ls -la "${DASHBOARD_STORAGE}/" 2>/dev/null || echo "  (empty)"
    echo ""
    echo "✅ Synced! Visit http://localhost:8000/backtest to see results."
fi

echo ""
echo "✨ Done."
