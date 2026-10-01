#!/bin/bash
# Run FxFridayCarryReal (47e résultat, jeudi 1er oct 2026) — axe INTERMARKET / CROSS-ASSET.
# Le fade du vendredi FX (37e/43e) re-testé sous le CARRY RÉEL des 8 devises : table de taux
# 3 mois interbancaires (moyennes annuelles 2006-2026, FRED/OCDE IR3TIB01) au lieu des
# constantes 2024-2026 du repo, dont le 44e a montré qu'elles sont un artefact (signe inclus).
export JAVA_HOME=/home/martinfou/.local/share/mise/installs/java/26.0
export PATH="$JAVA_HOME/bin:$PATH"
REPO=/home/martinfou/projects/tb-research
MLOCAL=$HOME/.m2/repository
cd "$REPO" || exit 1

# /tmp peut être purgé : régénérer le classpath Maven si absent (sinon NoClassDefFoundError jsr310).
if [ ! -f /tmp/bt-cp.txt ]; then
  ./mvnw -q dependency:build-classpath -Dmdep.outputFile=/tmp/bt-cp.txt -pl trading-examples >/dev/null 2>&1
fi

CP="trading-examples/target/classes"
CP="$CP:trading-strategies/target/classes"
CP="$CP:trading-backtest/target/classes"
CP="$CP:trading-core/target/classes"
CP="$CP:trading-data/target/classes"
CP="$CP:trading-intelligence/target/classes"
for jar in $(find $MLOCAL/com/fasterxml/jackson -name "*.jar" -not -name "*sources*" -not -name "*javadoc*" 2>/dev/null); do CP="$CP:$jar"; done
for jar in $(find $MLOCAL/org/slf4j -name "slf4j-api-2*.jar" -not -name "*sources*" 2>/dev/null); do CP="$CP:$jar"; done
for jar in $MLOCAL/ch/qos/logback/logback-classic/1.*/logback-classic-1.*.jar; do [ -f "$jar" ] && CP="$CP:$jar"; done
for jar in $MLOCAL/ch/qos/logback/logback-core/1.*/logback-core-1.*.jar; do [ -f "$jar" ] && CP="$CP:$jar"; done
# jsr310 (JavaTimeModule) n'est pas sous com/fasterxml/jackson/datatype → l'ajouter explicitement.
CP="$CP:$(cat /tmp/bt-cp.txt 2>/dev/null)"
JSR=$MLOCAL/com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.17.2/jackson-datatype-jsr310-2.17.2.jar
[ -f "$JSR" ] && CP="$CP:$JSR"

javac -nowarn -cp "$CP" -d trading-strategies/target/classes \
  trading-strategies/src/main/java/com/martinfou/trading/strategies/creative/GoldWeekdayEffectStrategy.java || exit 1
javac -nowarn -cp "$CP" -d trading-examples/target/classes \
  trading-examples/src/main/java/com/martinfou/trading/examples/RunFxFridayCarryReal.java \
  trading-examples/src/main/java/com/martinfou/trading/examples/RunUsdcadSwapRetest.java || exit 1

MODE="${1:---all}"
echo "=== MODE: $MODE | $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
# stderr -> /dev/null : les ERROR de persistence (SQLite absent du CP) corrompent les tableaux.
timeout --preserve-status 2400 java -cp "$CP" com.martinfou.trading.examples.RunFxFridayCarryReal "$MODE" 2>/dev/null
