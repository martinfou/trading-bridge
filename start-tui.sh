#!/usr/bin/env bash

# Exit immediately if a command exits with a non-zero status
set -e

# Helper to check if a command exists
check_command() {
  local cmd="$1"
  local friendly_name="$2"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "========================================================================"
    echo "ERROR: $friendly_name ('$cmd') is not installed or not in your PATH."
    echo "Please install it before running this script."
    echo "========================================================================"
    exit 1
  fi
}

echo "=== Checking Prerequisites ==="
check_command "java" "Java Runtime Environment"
echo "Prerequisites OK."
echo ""

# Resolve the root directory of the project
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Ports/URLs
PORT=8080
HEALTH_URL="http://localhost:$PORT/api/sq-bridge/status"

# Cleanup function to kill the background control plane if we started it
cleanup() {
  if [ -n "$CONTROL_PLANE_PID" ]; then
    echo ""
    echo "=== Shutting down background Control Plane (PID: $CONTROL_PLANE_PID) ==="
    kill "$CONTROL_PLANE_PID" 2>/dev/null || true
    wait "$CONTROL_PLANE_PID" 2>/dev/null || true
    echo "Control Plane stopped."
  fi
}

# Trap exit signals to ensure cleanup runs
trap cleanup EXIT SIGINT SIGTERM

echo "=== 1. Compiling Project ==="
./mvnw compile

# Check if something is already running on port 8080
CONTROL_PLANE_ALREADY_RUNNING=false
if curl -s -f "$HEALTH_URL" >/dev/null 2>&1; then
  echo "=== Control Plane is already running on port $PORT ==="
  CONTROL_PLANE_ALREADY_RUNNING=true
else
  echo "=== 2. Starting Control Plane in the background ==="
  ./mvnw exec:java -pl trading-runtime -Dexec.mainClass="com.martinfou.trading.runtime.ControlPlaneMain" > control-plane.log 2>&1 &
  CONTROL_PLANE_PID=$!
  echo "Control Plane started with PID: $CONTROL_PLANE_PID"
  echo "Waiting for Control Plane to become healthy on $HEALTH_URL..."

  # Poll health check endpoint up to 30 times (30 seconds)
  count=0
  until curl -s -f "$HEALTH_URL" >/dev/null 2>&1; do
    sleep 1
    count=$((count + 1))
    if [ "$count" -ge 30 ]; then
      echo "ERROR: Control Plane failed to start within 30 seconds."
      echo "Check control-plane.log for details."
      exit 1
    fi
  done
  echo "Control Plane is UP and healthy."
fi

echo ""
echo "=== 3. Starting Trading TUI Client ==="
./mvnw exec:java -pl trading-tui -Dexec.mainClass="com.martinfou.trading.tui.TradingTuiMain"

# If the TUI exits normally, cleanup will execute and shut down the control plane (if we started it)
