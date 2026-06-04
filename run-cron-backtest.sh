#!/bin/bash
# Minimal backtest runner for cron strategies
export JAVA_HOME=/home/martinfou/.local/share/mise/installs/java/26.0
export PATH="$JAVA_HOME/bin:/home/martinfou/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin:$PATH"
REPO=/home/martinfou/projects/trading-bridge
MLOCAL=$HOME/.m2/repository
cd "$REPO"

# Build classpath
CP="trading-backtest/target/classes"
CP="$CP:trading-strategies/target/classes"
CP="$CP:trading-core/target/classes"
CP="$CP:trading-data/target/classes"

# Add Jackson + SLF4J
for jar in $(find $MLOCAL/com/fasterxml/jackson -name "*.jar" -not -name "*sources*" -not -name "*javadoc*" 2>/dev/null); do CP="$CP:$jar"; done
for jar in $(find $MLOCAL/org/slf4j -name "slf4j-api-2*.jar" -not -name "*sources*" 2>/dev/null); do CP="$CP:$jar"; done

# Add logback if available
for jar in $MLOCAL/ch/qos/logback/logback-classic/1.*/logback-classic-1.*.jar; do [ -f "$jar" ] && CP="$CP:$jar"; done
for jar in $MLOCAL/ch/qos/logback/logback-core/1.*/logback-core-1.*.jar; do [ -f "$jar" ] && CP="$CP:$jar"; done

echo "Starting backtest (timeout after 600s)..."
echo "Timestamp: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
timeout --preserve-status 600 java -cp "$CP" com.martinfou.trading.backtest.batch.RunCronStrategyBacktest
EXIT=$?
if [ $EXIT -eq 0 ] || [ $EXIT -eq 124 ]; then
    echo "Run completed or timed out gracefully."
fi
