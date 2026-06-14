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

echo "=== Starting Control Plane ==="
# Delegate to the internal script
exec ./scripts/run-control-plane.sh
