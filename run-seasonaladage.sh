#!/bin/bash
# RunSeasonalAdageAudit — vendredi 2 octobre 2026 (48e résultat, deep dive).
# Audit de la 2e bibliothèque saisonnière (trading-data/SeasonalityAnalyzer) +
# mesure propre de la matrice mensuelle et des 6 fenêtres adages.
export JAVA_HOME=/home/martinfou/.local/share/mise/installs/java/26.0
export PATH="$JAVA_HOME/bin:$PATH"
REPO=/home/martinfou/projects/tb-research
MLOCAL=$HOME/.m2/repository
cd "$REPO" || exit 1

CP="trading-examples/target/classes"
CP="$CP:trading-strategies/target/classes"
CP="$CP:trading-backtest/target/classes"
CP="$CP:trading-core/target/classes"
CP="$CP:trading-data/target/classes"
CP="$CP:trading-intelligence/target/classes"
for jar in $(find $MLOCAL/com/fasterxml/jackson -name "*.jar" -not -name "*sources*" -not -name "*javadoc*" 2>/dev/null); do CP="$CP:$jar"; done
CP="$CP:$(find $MLOCAL -name 'jackson-datatype-jsr310*.jar' 2>/dev/null | head -1)"
for jar in $(find $MLOCAL/org/slf4j -name "slf4j-api-2*.jar" -not -name "*sources*" 2>/dev/null); do CP="$CP:$jar"; done
for jar in $MLOCAL/ch/qos/logback/logback-classic/1.*/logback-classic-1.*.jar; do [ -f "$jar" ] && CP="$CP:$jar"; done
for jar in $MLOCAL/ch/qos/logback/logback-core/1.*/logback-core-1.*.jar; do [ -f "$jar" ] && CP="$CP:$jar"; done
for jar in $(find $MLOCAL/org/ta4j -name "*.jar" -not -name "*sources*" 2>/dev/null); do CP="$CP:$jar"; done
CP="$CP:$(cat /tmp/bt-cp.txt 2>/dev/null)"

javac -nowarn -cp "$CP" -d trading-examples/target/classes \
  trading-examples/src/main/java/com/martinfou/trading/examples/RunSeasonalAdageAudit.java || exit 1

echo "=== RunSeasonalAdageAudit | $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
timeout --preserve-status 900 java -cp "$CP" com.martinfou.trading.examples.RunSeasonalAdageAudit "$@" 2>/tmp/seasonaladage-stderr.log
