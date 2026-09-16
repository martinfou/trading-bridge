#!/bin/bash
# Compile + run a research pre-validation class (Pattern D) without Maven.
# Usage: ./scripts/run-research-check.sh com.martinfou.trading.intelligence.research.FxCalendarDimCheck
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

CLASS="$1"
SRC="trading-intelligence/src/main/java/$(echo $CLASS | tr '.' '/').java"
echo "=== javac $SRC ==="
javac -nowarn -cp "$CP" -d trading-intelligence/target/classes "$SRC" || exit 1
echo "=== run $CLASS | $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
java -cp "$CP" "$CLASS" 2>&1
