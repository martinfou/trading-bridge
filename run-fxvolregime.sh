#!/bin/bash
# Run FxVolRegimeSize (43e résultat, deep dive vendredi 25 sept 2026) — le fade du
# vendredi FX conditionné par la volatilité : GATE (sélection) vs OVERLAY (taille),
# avec benchmark de levier uniforme à exposition ÉGALE.
export JAVA_HOME=/home/martinfou/.local/share/mise/installs/java/26.0
export PATH="$JAVA_HOME/bin:$PATH"
REPO=/home/martinfou/projects/trading-bridge
MLOCAL=$HOME/.m2/repository
cd "$REPO" || exit 1

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
# ⚠️ jsr310 (JavaTimeModule) n'est pas sous com/fasterxml/jackson/datatype → l'ajouter explicitement,
# sinon RunContext.run() plante en NoClassDefFoundError (Jackson ne peut pas sérialiser les LocalDate).
CP="$CP:$(cat /tmp/bt-cp.txt 2>/dev/null)"

javac -nowarn -cp "$CP" -d trading-strategies/target/classes \
  trading-strategies/src/main/java/com/martinfou/trading/strategies/creative/GoldWeekdayEffectStrategy.java || exit 1
javac -nowarn -cp "$CP" -d trading-examples/target/classes \
  trading-examples/src/main/java/com/martinfou/trading/examples/RunFxVolRegimeSize.java || exit 1

MODE="${1:---base}"
echo "=== MODE: $MODE | $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
# stderr -> /dev/null : les ERROR de persistence (SQLite absent du CP) corrompent les tableaux.
timeout --preserve-status 1500 java -cp "$CP" com.martinfou.trading.examples.RunFxVolRegimeSize "$MODE" 2>/dev/null
