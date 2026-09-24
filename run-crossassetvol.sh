#!/bin/bash
# Run CrossAssetVolRegimeCheck (42e résultat, jeudi 24 sept 2026 — INTERMARKET : la VOLATILITÉ cross-asset).
#
# Question : la volatilité ACTIONS (réalisée sur MES/MNQ D1 2006-2026) conditionne-t-elle l'amplitude des
# deux edges calendaires FX vivants (fade du vendredi 37e, réversion du lundi long-only 39e) — et apporte-t-elle
# de l'information AU-DELÀ de la vol propre de la paire ?
#
# P0 calibration (reproduit le 37e/41e AU BP) · P2/P3 quintiles vol actions vs vol propre (rang causal) ·
# P4 test incrémental 2×2 · P5 robustesse d'ère + hors crises · P6 spécificité du jour · P7 réversion du lundi ·
# P8 sensibilité de la mesure (4 proxies + 4 fenêtres de traîne) · P9 CONTRÔLE DÉCISIF : normalisation z-score
# (l'amplitude brute n'est pas un edge — la vol gonfle le bp de TOUS les régimes).
#
# Pré-validation Pattern D. Aucune stratégie codée : tout verdict de tradeabilité exige un backtest AVEC coûts
# (0.07 $ + 0.01 % slippage) et la lecture séparée du swap.
# Rapport complet : note Joplin 2026-09-24 + ~/.hermes/skills/simons/references/2026-09-24-cross-asset-vol-regime.md
export JAVA_HOME=/home/martinfou/.local/share/mise/installs/java/26.0
export PATH="$JAVA_HOME/bin:$PATH"
REPO=/home/martinfou/projects/trading-bridge
MLOCAL=$HOME/.m2/repository
cd "$REPO"

CP="trading-intelligence/target/classes"
CP="$CP:trading-core/target/classes"
CP="$CP:trading-data/target/classes"
CP="$CP:trading-strategies/target/classes"
for jar in $(find $MLOCAL/com/fasterxml/jackson -name "*.jar" -not -name "*sources*" -not -name "*javadoc*" 2>/dev/null); do CP="$CP:$jar"; done
for jar in $(find $MLOCAL/org/slf4j -name "slf4j-api-2*.jar" -not -name "*sources*" 2>/dev/null); do CP="$CP:$jar"; done

javac -nowarn -cp "$CP" -d trading-intelligence/target/classes \
  trading-intelligence/src/main/java/com/martinfou/trading/intelligence/research/CrossAssetVolRegimeCheck.java || exit 1

echo "=== CrossAssetVolRegimeCheck | $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
timeout --preserve-status 1800 java -cp "$CP" com.martinfou.trading.intelligence.research.CrossAssetVolRegimeCheck 2>&1
