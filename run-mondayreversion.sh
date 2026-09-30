#!/bin/bash
# Run FxMondayReversion (39e résultat, deep dive vendredi 18 sept 2026) — réversion du lundi FX.
# Compile le véhicule + le runner, puis exécute.
export JAVA_HOME=/home/martinfou/.local/share/mise/installs/java/26.0
export PATH="$JAVA_HOME/bin:$PATH"
REPO=/home/martinfou/projects/trading-bridge
MLOCAL=$HOME/.m2/repository
cd "$REPO"

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

javac -nowarn -cp "$CP" -d trading-strategies/target/classes \
  trading-strategies/src/main/java/com/martinfou/trading/strategies/creative/MondayReversionStrategy.java || exit 1
javac -nowarn -cp "$CP" -d trading-examples/target/classes \
  trading-examples/src/main/java/com/martinfou/trading/examples/RunFxMondayReversion.java || exit 1

MODE="${1:---scan}"
echo "=== MODE: $MODE | $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
timeout --preserve-status 1800 java -cp "$CP" com.martinfou.trading.examples.RunFxMondayReversion $MODE 2>&1
