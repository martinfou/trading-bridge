#!/usr/bin/env bash
# ibkr-gateway-health.sh — vérifie que le Gateway IBKR écoute sur le port paper 7497.
# Usage: scripts/ibkr-gateway-health.sh
# Exit 0 = port ouvert, exit 1 = port fermé (Gateway down ou en login).
set -euo pipefail

PORT="${IBKR_GATEWAY_PORT:-7497}"
HOST="${IBKR_GATEWAY_HOST:-127.0.0.1}"

if ss -tln 2>/dev/null | grep -q ":$PORT "; then
  echo "✅ IB Gateway paper: $HOST:$PORT ouvert"
  exit 0
fi

# Fallback: test TCP sans ss (macOS / busybox).
if command -v nc >/dev/null 2>&1 && nc -z -w 2 "$HOST" "$PORT" 2>/dev/null; then
  echo "✅ IB Gateway paper: $HOST:$PORT ouvert (nc)"
  exit 0
fi

echo "❌ IB Gateway paper: $HOST:$PORT fermé (Gateway down ou login requis)" >&2
exit 1
