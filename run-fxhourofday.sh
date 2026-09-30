#!/bin/bash
# Run FxHourOfDayCheck (40e résultat, lundi 21 sept 2026 — idée NOUVELLE : structure HORAIRE intraday du FX).
# Pré-validation Pattern D : profil 24 h sur 7 paires, fix de Londres 16:00 (DST-aware), fix × cycle du mois,
# décomposition par décennie. Aucun backtest (aucune fenêtre ne franchit le seuil de 2.14 bp de coûts).
# Rapport complet : note Joplin 2026-09-21 + ~/.hermes/skills/simons/references/2026-09-21-fx-hour-of-day.md
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
  trading-intelligence/src/main/java/com/martinfou/trading/intelligence/research/FxHourOfDayCheck.java || exit 1

echo "=== FxHourOfDayCheck | $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
timeout --preserve-status 1800 java -cp "$CP" com.martinfou.trading.intelligence.research.FxHourOfDayCheck 2>&1
