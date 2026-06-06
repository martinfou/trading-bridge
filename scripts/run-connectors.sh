#!/bin/bash
# Run the ConnectorRunner demo inside a single Maven Docker container
set -e

cd /home/martinfou/projects/trading-bridge

OANDA_KEY="${1:-$(grep OANDA_API_KEY deploy/openclaw.env 2>/dev/null | cut -d= -f2)}"
OANDA_ACCT="${2:-$(grep OANDA_ACCOUNT_ID deploy/openclaw.env 2>/dev/null | cut -d= -f2)}"

docker run --rm -v "$(pwd):/app" -w /app --entrypoint bash maven:3-eclipse-temurin-21 << 'SCRIPT'
  set -e
  # Install required modules
  mvn install -DskipTests -q -pl trading-core,trading-data -am

  # Run the interactive demo
  mvn exec:java -pl trading-data \
    -Dexec.mainClass=com.martinfou.trading.data.ConnectorRunner \
    -Dexec.classpathScope=compile \
    -q 2>/dev/null
SCRIPT
