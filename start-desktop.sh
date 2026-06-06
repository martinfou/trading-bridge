#!/usr/bin/env bash

# Exit immediately if a command exits with a non-zero status
set -e

# Resolve the root directory of the project
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== 1. Compiling & Testing Java Backend ==="
./mvnw clean test
./mvnw package -pl trading-runtime -am -DskipTests

echo "=== 2. Setting up Java Environment ==="
# Detect JAVA_HOME on macOS if not set
if [ -z "$JAVA_HOME" ]; then
  if [ "$(uname)" == "Darwin" ] && [ -x /usr/libexec/java_home ]; then
    export JAVA_HOME=$(/usr/libexec/java_home)
    echo "Set JAVA_HOME=$JAVA_HOME"
  else
    echo "Warning: JAVA_HOME is not set. The desktop app will try to use the system default 'java'."
  fi
else
  echo "Using existing JAVA_HOME=$JAVA_HOME"
fi

echo "=== 3. Setting up Frontend ==="
cd "$SCRIPT_DIR/desktop"
if [ ! -d "node_modules" ]; then
  echo "node_modules not found in desktop directory, installing dependencies..."
  npm install
fi

echo "=== 4. Starting Desktop Application ==="
# Runs Electron dev server (which spawns the Java backend automatically)
npm run electron:dev
