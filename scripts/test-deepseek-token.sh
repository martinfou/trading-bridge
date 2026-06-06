#!/usr/bin/env bash
# Probe DeepSeek API credentials (minimal chat completion).
#
# Usage:
#   export DEEPSEEK_TOKEN="sk-..."
#   ./scripts/test-deepseek-token.sh
#
# Also accepts DEEPSEEK_API_KEY (used by trading-intelligence).
# Optional: DEEPSEEK_BASE_URL (default https://api.deepseek.com)
#           DEEPSEEK_MODEL     (default deepseek-chat)

set -euo pipefail

TOKEN="${DEEPSEEK_TOKEN:-${DEEPSEEK_API_KEY:-}}"
BASE_URL="${DEEPSEEK_BASE_URL:-https://api.deepseek.com}"
MODEL="${DEEPSEEK_MODEL:-deepseek-chat}"

if [[ -z "$TOKEN" ]]; then
  echo "FAIL: DEEPSEEK_TOKEN (or DEEPSEEK_API_KEY) is not set." >&2
  echo "  export DEEPSEEK_TOKEN=\"sk-...\"" >&2
  exit 1
fi

BASE_URL="${BASE_URL%/}"
URL="${BASE_URL}/v1/chat/completions"

payload=$(cat <<EOF
{
  "model": "${MODEL}",
  "max_tokens": 5,
  "temperature": 0,
  "messages": [
    {"role": "user", "content": "Reply with exactly: ok"}
  ]
}
EOF
)

echo "DeepSeek token check"
echo "  endpoint: ${URL}"
echo "  model:    ${MODEL}"
echo "  token:    ${TOKEN:0:7}... (${#TOKEN} chars)"
echo ""

response_file=$(mktemp)
http_code=$(
  curl -sS -w "%{http_code}" -o "$response_file" \
    -X POST "$URL" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$payload" \
    --connect-timeout 15 \
    --max-time 60
)

body=$(cat "$response_file")
rm -f "$response_file"

if [[ "$http_code" == "200" ]]; then
  reply=$(printf '%s' "$body" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    print(data['choices'][0]['message']['content'].strip())
except Exception:
    print('(could not parse reply)')
" 2>/dev/null || echo "(could not parse reply)")
  echo "OK: token is valid (HTTP 200)."
  echo "  model reply: ${reply}"
  echo ""
  if [[ -z "${DEEPSEEK_API_KEY:-}" && -n "${DEEPSEEK_TOKEN:-}" ]]; then
    echo "Note: trading-intelligence reads DEEPSEEK_API_KEY, not DEEPSEEK_TOKEN."
    echo "  export DEEPSEEK_API_KEY=\"\$DEEPSEEK_TOKEN\"   # for /weekly-build --plan"
  fi
  exit 0
fi

echo "FAIL: HTTP ${http_code}" >&2
detail=$(printf '%s' "$body" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    err = data.get('error', data)
    if isinstance(err, dict):
        msg = err.get('message') or err.get('code') or str(err)
    else:
        msg = str(err)
    print(msg[:400])
except Exception:
    raw = sys.stdin.read().strip()
    print(raw[:400] if raw else '(empty body)')
" 2>/dev/null || printf '%s' "$body" | head -c 400)
echo "  ${detail}" >&2

case "$http_code" in
  401) echo "  → Invalid or expired token." >&2 ;;
  402) echo "  → Billing / quota issue (insufficient balance)." >&2 ;;
  429) echo "  → Rate limited; token may still be valid." >&2 ;;
esac
exit 1
