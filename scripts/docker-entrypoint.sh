#!/bin/bash
# Docker entrypoint for trading-bridge
# Reads env vars and passes them as args to LiveStrategyRunner
# Wraps in retry loop with exponential backoff to prevent
# crash-looping on invalid API keys or transient failures.
#
# The entrypoint tracks how long Java ran. If it ran <5 min,
# we assume a startup failure (401, config error, etc.) and
# backoff before retrying. Long successful runs exit cleanly
# and Docker's restart: unless-stopped takes over normally.
#
# 2026-09-21 — rendre une panne PERMANENTE audible.
#   Constat mesuré : un token OANDA révoqué fait mourir les stratégies en
#   ~2 s avec « OANDA API error 401 ». Le conteneur sortait alors en code 0
#   et se contentait de retenter : `docker ps` affichait « Up 10 days » et
#   l'échec était invisible. C'est ainsi que le pipeline paper trading est
#   resté mort du 10 juillet au 21 septembre 2026 (69 jours, 0 trade) sans
#   que rien ni personne ne le signale.
#   Deux corrections :
#     1. une panne d'authentification est désormais NOMMÉE (bloc explicite
#        avec la cause et le remède) au lieu du message générique
#        « exited with code 0 » ;
#     2. le retry passe immédiatement à la cadence maximale (1 h) : un token
#        révoqué ne se répare pas en retentant, et l'environnement du
#        conteneur est figé à sa création, donc seul `docker compose up -d
#        --force-recreate` peut le réparer.
#   La visibilité, elle, est portée par le HEALTHCHECK du Dockerfile
#   (voir Dockerfile) : plus de conteneur « Up » avec une stratégie morte,
#   mais « Up (unhealthy) ». On ne sort PAS en erreur : `restart:
#   unless-stopped` relancerait le conteneur en boucle serrée — du bruit
#   de logs, pas de la visibilité.

set -o pipefail

API_KEY="${OANDA_API_KEY:-}"
ACCOUNT_ID="${OANDA_ACCOUNT_ID:-}"
STRATEGY="${STRATEGY:-vwpreversion}"
GRANULARITY="${GRANULARITY:-H1}"
INTERVAL_SEC="${INTERVAL_SEC:-60}"

if [ -z "$API_KEY" ] || [ -z "$ACCOUNT_ID" ]; then
    echo "ERROR: OANDA_API_KEY and OANDA_ACCOUNT_ID must be set"
    echo "Set them in the environment or via an env_file"
    exit 1
fi

BACKOFF=10                                      # initial retry delay (seconds)
MAX_BACKOFF=3600                                # max delay cap (1 hour)
MIN_RUN_THRESHOLD="${MIN_RUN_THRESHOLD:-300}"   # if Java ran < 5 min, it was a startup failure

# Signatures d'un refus d'authentification OANDA : non récupérable par retry.
# Couvre le message REST v20 et le libellé exact observé en production :
# {"errorMessage":"Insufficient authorization to perform request."}
AUTH_FAILURE_RE='OANDA API error 401|OANDA API returned error status 401|Insufficient authorization|Invalid authorization|401 Unauthorized'

while true; do
    START_TS=$(date +%s)
    OUTFILE=$(mktemp)

    java -cp "/app/classes/trading-core:/app/classes/trading-data:/app/classes/trading-strategies:/app/classes/trading-broker:/app/classes/trading-parser:/app/libs/*" \
        com.martinfou.trading.strategies.LiveStrategyRunner \
        "$API_KEY" "$ACCOUNT_ID" $STRATEGY "$GRANULARITY" "$INTERVAL_SEC" 2>&1 | tee "$OUTFILE"
    EXIT_CODE=${PIPESTATUS[0]}

    RUNTIME=$(( $(date +%s) - START_TS ))

    if [ "$EXIT_CODE" -eq 0 ] && [ "$RUNTIME" -ge "$MIN_RUN_THRESHOLD" ]; then
        rm -f "$OUTFILE"
        echo "✅ Strategy runner exited cleanly after ${RUNTIME}s. Container stopping."
        exit 0
    fi

    # Panne PERMANENTE (identifiants refusés) : on la NOMME au lieu de la
    # déguiser en « sortie propre », et on cale le retry sur la cadence max.
    if [ "$RUNTIME" -lt "$MIN_RUN_THRESHOLD" ] && grep -qE "$AUTH_FAILURE_RE" "$OUTFILE"; then
        rm -f "$OUTFILE"
        echo "════════════════════════════════════════════════════════════════"
        echo "🛑 ÉCHEC D'AUTHENTIFICATION OANDA — identifiants refusés (HTTP 401)."
        echo "   Compte : ${ACCOUNT_ID}"
        echo "   Stratégie(s) : ${STRATEGY} — AUCUN ordre ne sera passé tant que"
        echo "   ce bloc apparaît. Le conteneur reste 'Up' mais la sonde de"
        echo "   santé le déclare 'unhealthy' (docker ps)."
        echo ""
        echo "   Vérifier :   ./scripts/test-oanda-token.sh --paper"
        echo "   Remède :"
        echo "     1. régénérer un token practice dans l'interface OANDA"
        echo "     2. mettre à jour OANDA_API_KEY dans"
        echo "        /home/martinfou/projects/trading-dashboard/.env"
        echo "     3. docker compose up -d --force-recreate \\"
        echo "          trader comp-momentum month-week lt-rsi3"
        echo ""
        echo "   (l'env du conteneur est figé à sa création : un simple"
        echo "    redémarrage automatique ne peut pas charger un nouveau token)"
        echo "   Prochaine tentative dans ${MAX_BACKOFF}s."
        echo "════════════════════════════════════════════════════════════════"
        BACKOFF=$MAX_BACKOFF
    else
        # Panne TRANSITOIRE (réseau, indispo temporaire) → backoff progressif.
        # This prevents tight restart loops with Docker restart: unless-stopped
        echo "⚠️ Strategy exited with code $EXIT_CODE after ${RUNTIME}s (< ${MIN_RUN_THRESHOLD}s = startup failure)."
        echo "   Retrying in ${BACKOFF}s..."
        BACKOFF=$(( BACKOFF * 2 ))
    fi

    sleep "$BACKOFF"
    # Exponential backoff, capped at 1 hour
    [ "$BACKOFF" -gt "$MAX_BACKOFF" ] && BACKOFF=$MAX_BACKOFF
done
