#!/usr/bin/env bash
# Probe OANDA v20 REST credentials (account summary — read-only).
#
# Usage:
#   # Paper (practice) — uses OANDA_API_KEY / OANDA_API_TOKEN + OANDA_ACCOUNT_ID
#   ./scripts/test-oanda-token.sh --paper
#
#   # Live (production)
#   ./scripts/test-oanda-token.sh --live
#
#   # Both (default)
#   ./scripts/test-oanda-token.sh
#   ./scripts/test-oanda-token.sh --all
#
# Paper env:
#   OANDA_API_KEY or OANDA_API_TOKEN
#   OANDA_ACCOUNT_ID
#   OANDA_REST_URL          optional (default https://api-fxpractice.oanda.com)
#
# Live env:
#   OANDA_LIVE_API_KEY or OANDA_LIVE_API_TOKEN
#   OANDA_LIVE_ACCOUNT_ID
#   OANDA_LIVE_REST_URL     optional (default https://api-fxtrade.oanda.com)

set -euo pipefail

PAPER_URL="${OANDA_REST_URL:-https://api-fxpractice.oanda.com}"
LIVE_URL="${OANDA_LIVE_REST_URL:-https://api-fxtrade.oanda.com}"

MODE="all"
for arg in "$@"; do
  case "$arg" in
    --paper|-p) MODE="paper" ;;
    --live|-l) MODE="live" ;;
    --all|-a) MODE="all" ;;
    -h|--help)
      sed -n '2,24p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg (try --help)" >&2
      exit 1
      ;;
  esac
done

first_non_blank() {
  local a="${1:-}" b="${2:-}"
  if [[ -n "$a" ]]; then echo "$a"; return; fi
  if [[ -n "$b" ]]; then echo "$b"; return; fi
  echo ""
}

check_oanda() {
  local label="$1"
  local token="$2"
  local account_id="$3"
  local base_url="$4"

  base_url="${base_url%/}"
  local url="${base_url}/v3/accounts/${account_id}/summary"

  echo "OANDA ${label} check"
  echo "  endpoint:  ${url}"
  echo "  account:   ${account_id}"
  echo "  token:     ${token:0:8}... (${#token} chars)"
  echo ""

  local response_file http_code body
  response_file=$(mktemp)
  http_code=$(
    curl -sS -w "%{http_code}" -o "$response_file" \
      -X GET "$url" \
      -H "Authorization: Bearer ${token}" \
      -H "Content-Type: application/json" \
      --connect-timeout 15 \
      --max-time 30
  )
  body=$(cat "$response_file")
  rm -f "$response_file"

  if [[ "$http_code" == "200" ]]; then
    local balance nav currency
    balance=$(printf '%s' "$body" | python3 -c "
import json, sys
try:
    a = json.load(sys.stdin)['account']
    print(a.get('balance', '?'))
except Exception:
    print('?')
" 2>/dev/null || echo "?")
    nav=$(printf '%s' "$body" | python3 -c "
import json, sys
try:
    a = json.load(sys.stdin)['account']
    print(a.get('NAV', '?'))
except Exception:
    print('?')
" 2>/dev/null || echo "?")
    currency=$(printf '%s' "$body" | python3 -c "
import json, sys
try:
    a = json.load(sys.stdin)['account']
    print(a.get('currency', '?'))
except Exception:
    print('?')
" 2>/dev/null || echo "?")
    echo "OK: ${label} credentials valid (HTTP 200)."
    echo "  balance: ${balance} ${currency}"
    echo "  NAV:     ${nav} ${currency}"
    echo ""
    return 0
  fi

  echo "FAIL: ${label} — HTTP ${http_code}" >&2
  local detail
  detail=$(printf '%s' "$body" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    print(data.get('errorMessage', str(data))[:400])
except Exception:
    raw = sys.stdin.read().strip()
    print(raw[:400] if raw else '(empty body)')
" 2>/dev/null || printf '%s' "$body" | head -c 400)
  echo "  ${detail}" >&2

  case "$http_code" in
    401)
      echo "  → Invalid or revoked API token." >&2
      ;;
    403)
      echo "  → Token valid but not authorized for this account or environment." >&2
      ;;
    404)
      echo "  → Account not found — wrong OANDA_ACCOUNT_ID or token/env mismatch." >&2
      echo "     (Practice token + practice account on api-fxpractice; live on api-fxtrade.)" >&2
      ;;
  esac
  echo "" >&2
  return 1
}

run_paper() {
  local token account_id
  token=$(first_non_blank "${OANDA_API_KEY:-}" "${OANDA_API_TOKEN:-}")
  account_id="${OANDA_ACCOUNT_ID:-}"

  if [[ -z "$token" || -z "$account_id" ]]; then
    echo "SKIP: paper — set OANDA_API_KEY (or OANDA_API_TOKEN) and OANDA_ACCOUNT_ID." >&2
    return 2
  fi
  check_oanda "paper (practice)" "$token" "$account_id" "$PAPER_URL"
}

run_live() {
  local token account_id
  token=$(first_non_blank "${OANDA_LIVE_API_KEY:-}" "${OANDA_LIVE_API_TOKEN:-}")
  account_id="${OANDA_LIVE_ACCOUNT_ID:-}"

  if [[ -z "$token" || -z "$account_id" ]]; then
    echo "SKIP: live — set OANDA_LIVE_API_KEY (or OANDA_LIVE_API_TOKEN) and OANDA_LIVE_ACCOUNT_ID." >&2
    return 2
  fi
  check_oanda "live (production)" "$token" "$account_id" "$LIVE_URL"
}

failures=0
skips=0
runs=0

run_check() {
  local rc=0
  set +e
  "$@"
  rc=$?
  set -e
  if [[ "$rc" -eq 0 ]]; then
    runs=$((runs + 1))
  elif [[ "$rc" -eq 2 ]]; then
    skips=$((skips + 1))
  else
    failures=$((failures + 1))
    runs=$((runs + 1))
  fi
}

case "$MODE" in
  paper) run_check run_paper ;;
  live) run_check run_live ;;
  all)
    run_check run_paper
    run_check run_live
    ;;
esac

if [[ "$runs" -eq 0 && "$skips" -gt 0 ]]; then
  echo "No credentials configured for the requested environment(s)." >&2
  exit 1
fi

if [[ "$failures" -gt 0 ]]; then
  echo "Summary: ${failures} check(s) failed." >&2
  exit 1
fi

echo "Summary: all configured OANDA checks passed."
exit 0
