#!/usr/bin/env bash
# =============================================================================
# deploy-paper.sh — déploiement des conteneurs paper (OANDA practice)
#
# Référencé par :
#   - docs/TRADING-GUARDRAILS.md (couche 4 — porte de déploiement)
#   - scripts/pre-deploy-gate.sh (dernière ligne : "safe to deploy with ...")
#
# CONTRAT : ce script REFUSE de partir si la porte pré-déploiement échoue.
# Il ne remplace pas la porte, il la rend obligatoire.
#
# Usage :
#   scripts/deploy-paper.sh [options] [service...]
#
#   (défaut)        porte → build → recreate des services paper → vérification santé
#   service...      ne déploie que ces services (ex: `deploy-paper.sh lt-rsi3`)
#   --dry-run       exécute la porte (sauf --skip-gate) puis AFFICHE les commandes,
#                   sans rien construire ni redémarrer
#   --skip-gate     saute la porte (déconseillé, journalisé comme tel)
#   --yes           pas de confirmation interactive
#   -h | --help     cette aide
#
# Services paper par défaut : trader, comp-momentum, month-week, lt-rsi3.
# `nfp-week` est exclu : fenêtre NFP de juin 2026 terminée, `restart: "no"`.
# L'ajouter explicitement si un jour il redevient d'actualité.
#
# Ce que ce script ne fait PAS : aucun merge, aucun push, aucun ordre au courtier,
# aucune modification de la configuration de risque.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

DEFAULT_SERVICES="trader comp-momentum month-week lt-rsi3"
LOG_FILE="deploy/deploy-paper.log"
HEALTH_TIMEOUT_S=180

DRY_RUN=0
SKIP_GATE=0
ASSUME_YES=0
SERVICES=()

usage() {
    sed -n '3,26p' "$0" | sed 's/^# \{0,1\}//'
}

# La stratégie tourne-t-elle vraiment dans ce conteneur ?
# Même exigence que le HEALTHCHECK du Dockerfile, et même piège évité : le motif
# est coupé en deux parce qu'une sonde qui cherche un littéral se trouve elle-même
# dans sa propre ligne de commande et se déclare toujours saine.
runner_alive() {
    docker exec "$1" sh -c 'n=LiveStrategy; n=${n}Runner; for p in /proc/[0-9]*; do tr "\000" " " < "$p/cmdline" 2>/dev/null | grep -q "$n" && exit 0; done; exit 1' >/dev/null 2>&1
}

for arg in "$@"; do
    case "$arg" in
        --dry-run)   DRY_RUN=1 ;;
        --skip-gate) SKIP_GATE=1 ;;
        --yes|-y)    ASSUME_YES=1 ;;
        -h|--help)   usage; exit 0 ;;
        -*)
            echo "❌ option inconnue : $arg" >&2
            echo "   (les services se passent en argument positionnel)" >&2
            exit 2
            ;;
        *) SERVICES+=("$arg") ;;
    esac
done

[ "${#SERVICES[@]}" -eq 0 ] && read -r -a SERVICES <<< "$DEFAULT_SERVICES"

command -v docker >/dev/null || { echo "❌ docker introuvable" >&2; exit 2; }
docker compose version >/dev/null 2>&1 || { echo "❌ 'docker compose' indisponible" >&2; exit 2; }

# --- Les services demandés existent-ils dans le compose ? -------------------
mapfile -t COMPOSE_SERVICES < <(docker compose config --services | sort)
for svc in "${SERVICES[@]}"; do
    if ! printf '%s\n' "${COMPOSE_SERVICES[@]}" | grep -qx "$svc"; then
        echo "❌ service inconnu dans docker-compose.yml : $svc" >&2
        echo "   services disponibles : ${COMPOSE_SERVICES[*]}" >&2
        exit 2
    fi
done

if git rev-parse --git-dir >/dev/null 2>&1; then
    GIT_SHA="$(git rev-parse --short HEAD)"
    # `|| true` : sans lui, `set -e` + `pipefail` tuent le script silencieusement
    # si un des maillons de la pipeline échoue (constaté hors dépôt git).
    GIT_DIRTY="$(git status --porcelain | wc -l | tr -d ' ' || true)"
else
    GIT_SHA="hors-git"
    GIT_DIRTY="0"
fi
if [ "$GIT_DIRTY" != "0" ]; then
    echo "⚠️  arbre de travail modifié ($GIT_DIRTY fichier(s)) : l'image construite contiendra"
    echo "    du code NON commité, en plus du commit $GIT_SHA."
fi

echo "═══ Cible : ${SERVICES[*]} ═══"
echo "    commit $GIT_SHA · arbre modifié : $GIT_DIRTY fichier(s) · dry-run : $DRY_RUN"

# --- 1/4 Porte pré-déploiement ---------------------------------------------
GATE_RESULT="skipped"
if [ "$SKIP_GATE" = "1" ]; then
    echo
    echo "⚠️  --skip-gate : la porte pré-déploiement N'EST PAS exécutée."
    echo "    docs/TRADING-GUARDRAILS.md (couche 4) demande qu'aucun code non testé"
    echo "    ne parte en production : ce déploiement sera journalisé comme non gardé."
else
    echo
    echo "═══ 1/4  porte pré-déploiement (scripts/pre-deploy-gate.sh) ═══"
    if ./scripts/pre-deploy-gate.sh; then
        GATE_RESULT="passed"
    else
        GATE_RESULT="failed"
        echo
        echo "❌ PORTE ÉCHOUÉE — aucun déploiement n'a été fait."
        echo "   Corriger la porte, puis relancer. (--skip-gate existe, mais il est journalisé.)"
        exit 1
    fi
fi

# --- 2/4 Construction des images -------------------------------------------
echo
echo "═══ 2/4  build : docker compose build ${SERVICES[*]} ═══"
if [ "$DRY_RUN" = "1" ]; then
    echo "    [dry-run] docker compose build ${SERVICES[*]}"
else
    docker compose build "${SERVICES[@]}"
fi

# --- 3/4 Recréation des conteneurs ----------------------------------------
echo
echo "═══ 3/4  recreate : docker compose up -d --no-deps ${SERVICES[*]} ═══"
if [ "$DRY_RUN" = "1" ]; then
    echo "    [dry-run] docker compose up -d --no-deps ${SERVICES[*]}"
    echo
    echo "✅ DRY-RUN terminé — rien n'a été construit ni redémarré."
    exit 0
fi

if [ "$ASSUME_YES" != "1" ]; then
    printf 'Redémarrer %s maintenant ? [oui/non] ' "${SERVICES[*]}"
    read -r answer
    case "$answer" in
        o|oui|y|yes|O|Oui) ;;
        *) echo "Annulé — rien n'a été redémarré."; exit 1 ;;
    esac
fi

docker compose up -d --no-deps "${SERVICES[@]}"

# --- 4/4 Vérification santé ------------------------------------------------
# « Up » ne veut pas dire « la stratégie trade » : le HEALTHCHECK du Dockerfile
# exige que le processus LiveStrategyRunner existe réellement.
echo
echo "═══ 4/4  vérification santé (max ${HEALTH_TIMEOUT_S}s) ═══"
FAILED=0
for svc in "${SERVICES[@]}"; do
    cid="$(docker compose ps -q "$svc")"
    if [ -z "$cid" ]; then
        echo "   ❌ $svc : pas de conteneur"
        FAILED=1
        continue
    fi
    status="unknown"
    for _ in $(seq 1 $((HEALTH_TIMEOUT_S / 5))); do
        status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo unknown)"
        case "$status" in
            healthy|none) break ;;
        esac
        sleep 5
    done
    running="$(docker inspect --format '{{.State.Running}}' "$cid" 2>/dev/null || echo false)"
    if [ "$status" = "healthy" ]; then
        echo "   ✅ $svc : healthy (sonde de l'image)"
    elif [ "$running" != "true" ]; then
        echo "   ❌ $svc : conteneur arrêté"
        FAILED=1
    elif runner_alive "$cid"; then
        # L'image publiée n'a pas toujours le HEALTHCHECK du Dockerfile (les 4 images
        # en service le 2026-09-30 n'en avaient aucun) : on refait donc la même
        # exigence directement dans le conteneur. « Up » ne veut pas dire « ça trade ».
        echo "   ✅ $svc : processus LiveStrategyRunner vivant"
        echo "      ⚠️  image sans HEALTHCHECK (le Dockerfile en définit un : image plus ancienne ou construite ailleurs)"
    else
        echo "   ❌ $svc : conteneur Up mais AUCUN processus LiveStrategyRunner"
        echo "      (symptôme exact des 69 jours de stratégies mortes du 10 juil au 21 sept 2026)"
        FAILED=1
    fi
done

# --- Journal ----------------------------------------------------------------
{
    printf '[%s] services=%s gate=%s sha=%s dirty=%s resultat=%s\n' \
        "$(date '+%Y-%m-%d %H:%M:%S%z')" \
        "${SERVICES[*]}" "$GATE_RESULT" "$GIT_SHA" "$GIT_DIRTY" \
        "$([ "$FAILED" = "0" ] && echo OK || echo SANTE_KO)"
} >> "$LOG_FILE"
echo
echo "📝 journal : $LOG_FILE (dernière ligne : $(tail -n 1 "$LOG_FILE"))"

if [ "$FAILED" != "0" ]; then
    echo
    echo "⚠️  Déploiement effectué mais au moins un conteneur n'est pas sain."
    echo "    Regarder : docker logs <conteneur> ; le HEALTHCHECK exige LiveStrategyRunner."
    exit 1
fi

echo
echo "✅ Déploiement paper terminé : ${SERVICES[*]} ($GATE_RESULT)."
