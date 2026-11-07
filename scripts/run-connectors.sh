#!/bin/bash
# Run the ConnectorRunner demo inside a single Maven Docker container
set -e

cd "$(dirname "$0")/.."

OANDA_KEY="${1:-$(grep -E '^OANDA_API_KEY=' .env.paper 2>/dev/null | tail -n1 | cut -d= -f2- | tr -d '"\x27\r')}"
OANDA_ACCT="${2:-$(grep -E '^OANDA_ACCOUNT_ID=' .env.paper 2>/dev/null | tail -n1 | cut -d= -f2- | tr -d '"\x27\r')}"

docker run --rm -v "$(pwd):/app" -w /app \
  -e OANDA_API_KEY="$OANDA_KEY" \
  -e OANDA_ACCOUNT_ID="$OANDA_ACCT" \
  --entrypoint bash maven:3-eclipse-temurin-21 << 'SCRIPT'
  set -e
  # Install required modules
  mvn install -DskipTests -q -pl trading-core,trading-data -am

  # Run the interactive demo
  mvn exec:java -pl trading-data \
    -Dexec.mainClass=com.martinfou.trading.data.ConnectorRunner \
    -Dexec.classpathScope=compile \
    -q 2>/dev/null
SCRIPT
