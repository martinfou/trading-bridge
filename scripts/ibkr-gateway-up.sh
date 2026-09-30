#!/usr/bin/env bash
# ibkr-gateway-up.sh — démarre IB Gateway 10.50 en mode paper (port 7497).
# AUTHENTIFICATION: interactive (Martin saisit ses identifiants paper dans la fenêtre).
# Usage: scripts/ibkr-gateway-up.sh
set -euo pipefail

GATEWAY_DIR="${IBKR_GATEWAY_DIR:-$HOME/ibgateway}"
PORT="${IBKR_GATEWAY_PORT:-7497}"

if [ ! -d "$GATEWAY_DIR" ]; then
  echo "❌ Gateway introuvable: $GATEWAY_DIR (set IBKR_GATEWAY_DIR)" >&2
  exit 1
fi

# Le port paper ne doit jamais être 7496 (live). Garde de sécurité.
if [ "$PORT" = "7496" ]; then
  echo "❌ Refus: port 7496 = LIVE. Paper uniquement (7497)." >&2
  exit 1
fi

# Si déjà en écoute sur le port paper, ne pas relancer.
if ss -tln 2>/dev/null | grep -q ":$PORT "; then
  echo "✅ IB Gateway déjà en écoute sur $PORT"
  exit 0
fi

echo "🚀 Démarrage IB Gateway (paper, port $PORT) depuis $GATEWAY_DIR ..."
# IB Gateway 10.50 desktop: le script ibgateway est le lanceur. Le mode paper se choisit
# au login (le compte paper DU... est un compte simulateur). En headless:
#   ibgateway -Xms... (voir ibgateway.vmoptions)
if [ -x "$GATEWAY_DIR/ibgateway" ]; then
  nohup "$GATEWAY_DIR/ibgateway" > /tmp/ibgateway-up.log 2>&1 &
  echo "PID: $!"
else
  echo "❌ Lanceur $GATEWAY_DIR/ibgateway introuvable. Vérifier l'installation." >&2
  exit 1
fi

echo "⏳ Attente du port $PORT (max 90s)..."
for i in $(seq 1 90); do
  if ss -tln 2>/dev/null | grep -q ":$PORT "; then
    echo "✅ IB Gateway paper opérationnel sur $PORT"
    exit 0
  fi
  sleep 1
done

echo "⚠️ Port $PORT non ouvert après 90s. Voir /tmp/ibgateway-up.log. Le login est probablement requis (fenêtre)." >&2
exit 1
