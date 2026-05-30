#!/usr/bin/env bash
#
# Upload a backtest PDF to Hermes-Web's algo trading section.
#
# Usage:
#   ./scripts/upload-to-hermes-web.sh <pdf_path> <strategy_name> <asset> [options]
#
# Options:
#   --title "Custom Title"          (default: "<strategy_name> — <asset>")
#   --sharpe 1.50
#   --pf 1.50                      Profit Factor
#   --wr 55.0                      Win Rate %
#   --dd 12.0                      Max Drawdown %
#   --trades 5000                  Total trades
#   --return 12.5                  Total return %
#   --qualified                    Mark as prop shop qualified
#   --date 2026-05-30              Backtest date (default: today)
#   --notes "Key findings..."
#   --url https://hermes.martinfournier.com   (default: localhost or HERMES_WEB_URL)
#
# Requires:
#   - curl
#   - HERMES_WEB_TOKEN env var set (or pass --token)
#
# Example:
#   ./scripts/upload-to-hermes-web.sh creative-lab/__False_Break_Rev_USDJPY.pdf \
#       "False Breakout Reversal" "USD/JPY" \
#       --sharpe 6.57 --pf 1.71 --wr 57.2 --dd 11.27 --trades 3959 --return 818.08 \
#       --qualified --notes "Best performer. Sharpe 6.57 on USD/JPY."
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

# ── Parse args ──────────────────────────────────────────────────────────

PDF_PATH=""
STRATEGY=""
ASSET=""
TITLE=""
SHARPE=""
PF=""
WR=""
DD=""
TRADES=""
RETURN=""
QUALIFIED=false
NOTES=""
DATE="$(date +%Y-%m-%d)"
URL="${HERMES_WEB_URL:-http://localhost:8000}"
TOKEN="${HERMES_WEB_TOKEN:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --title) TITLE="$2"; shift 2 ;;
        --sharpe) SHARPE="$2"; shift 2 ;;
        --pf) PF="$2"; shift 2 ;;
        --wr) WR="$2"; shift 2 ;;
        --dd) DD="$2"; shift 2 ;;
        --trades) TRADES="$2"; shift 2 ;;
        --return) RETURN="$2"; shift 2 ;;
        --qualified) QUALIFIED=true; shift ;;
        --date) DATE="$2"; shift 2 ;;
        --notes) NOTES="$2"; shift 2 ;;
        --url) URL="$2"; shift 2 ;;
        --token) TOKEN="$2"; shift 2 ;;
        --help|-h)
            echo "Usage: $0 <pdf_path> <strategy_name> <asset> [options]"
            echo ""
            echo "Options:"
            echo "  --title \"...\"       Default: \"<strategy> — <asset>\""
            echo "  --sharpe NUM        Sharpe Ratio"
            echo "  --pf NUM            Profit Factor"
            echo "  --wr NUM            Win Rate %"
            echo "  --dd NUM            Max Drawdown %"
            echo "  --trades NUM        Total trades"
            echo "  --return NUM        Total return %"
            echo "  --qualified         Mark as prop shop qualified"
            echo "  --date YYYY-MM-DD   Backtest date (default: today)"
            echo "  --notes \"...\"       Key findings"
            echo "  --url URL           Hermes-Web URL (default: \$HERMES_WEB_URL or http://localhost:8000)"
            echo "  --token TOKEN       API token (default: \$HERMES_WEB_TOKEN)"
            echo ""
            echo "Example:"
            echo "  HERMES_WEB_TOKEN=\$(grep HEALTH_IMPORT_TOKEN ~/projects/hermes-web/.env | cut -d= -f2)"
            echo "  export HERMES_WEB_URL=https://hermes.martinfournier.com"
            echo "  $0 creative-lab/__FBR_USDJPY.pdf \"FBR\" \"USD/JPY\" --sharpe 6.57 --qualified"
            exit 0
            ;;
        -*)
            echo "Unknown option: $1"
            exit 1
            ;;
        *)
            if [[ -z "$PDF_PATH" ]]; then PDF_PATH="$1"
            elif [[ -z "$STRATEGY" ]]; then STRATEGY="$1"
            elif [[ -z "$ASSET" ]]; then ASSET="$1"
            else echo "Unexpected argument: $1"; exit 1
            fi
            shift
            ;;
    esac
done

if [[ -z "$PDF_PATH" ]]; then
    echo "❌ Usage: $0 <pdf_path> <strategy_name> <asset>"
    exit 1
fi
if [[ ! -f "$PDF_PATH" ]]; then
    echo "❌ PDF not found: $PDF_PATH"
    exit 1
fi
if [[ -z "$TOKEN" ]]; then
    echo "❌ HERMES_WEB_TOKEN not set. Use --token or export HERMES_WEB_TOKEN"
    exit 1
fi
if [[ -z "$ASSET" ]]; then
    echo "❌ Asset required (e.g. USD/JPY)"
    exit 1
fi

# Default title
if [[ -z "$TITLE" ]]; then
    TITLE="${STRATEGY} — ${ASSET}"
fi

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Upload Backtest to Hermes-Web"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  PDF:      $PDF_PATH ($(du -h "$PDF_PATH" | cut -f1))"
echo "  Strategy: $STRATEGY"
echo "  Asset:    $ASSET"
echo "  Date:     $DATE"
echo "  Server:   $URL/api/trading/backtest-upload"
echo ""

# ── Build multipart form data ──

FORM_ARGS=(
    -F "pdf=@$PDF_PATH"
    -F "title=$TITLE"
    -F "strategy_name=$STRATEGY"
    -F "asset=$ASSET"
    -F "backtest_date=$DATE"
    -F "qualified=$QUALIFIED"
)

if [[ -n "$SHARPE" ]]; then FORM_ARGS+=(-F "sharpe_ratio=$SHARPE"); fi
if [[ -n "$PF" ]]; then FORM_ARGS+=(-F "profit_factor=$PF"); fi
if [[ -n "$WR" ]]; then FORM_ARGS+=(-F "win_rate=$WR"); fi
if [[ -n "$DD" ]]; then FORM_ARGS+=(-F "max_drawdown=$DD"); fi
if [[ -n "$TRADES" ]]; then FORM_ARGS+=(-F "total_trades=$TRADES"); fi
if [[ -n "$RETURN" ]]; then FORM_ARGS+=(-F "total_return_pct=$RETURN"); fi
if [[ -n "$NOTES" ]]; then FORM_ARGS+=(-F "notes=$NOTES"); fi

# ── Upload ──

RESPONSE=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $TOKEN" \
    "${FORM_ARGS[@]}" \
    "$URL/api/trading/backtest-upload")

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

if [[ "$HTTP_CODE" == "201" ]]; then
    ID=$(echo "$BODY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id','?'))" 2>/dev/null)
    echo "✅ Uploaded (ID: $ID) — $TITLE"
else
    echo "❌ Upload failed (HTTP $HTTP_CODE): $BODY"
    exit 1
fi

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
