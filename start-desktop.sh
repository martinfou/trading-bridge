#!/usr/bin/env bash

# Exit immediately if a command exits with a non-zero status
set -e

# Helper to check if a command exists, giving OS-specific instructions if missing
check_command() {
  local cmd="$1"
  local friendly_name="$2"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "========================================================================"
    echo "ERROR: $friendly_name ('$cmd') is not installed or not in your PATH."
    
    local os_type
    os_type="$(uname -s)"
    case "$os_type" in
      Darwin)
        if [ "$cmd" = "java" ]; then
          echo "Recommendation (macOS): Install JDK 21+ using Homebrew:"
          echo "  brew install openjdk@21"
        else
          echo "Recommendation (macOS): Install Node.js using Homebrew:"
          echo "  brew install node"
        fi
        ;;
      Linux)
        if [ "$cmd" = "java" ]; then
          echo "Recommendation (Linux): Install JDK 21+ using apt or dnf:"
          echo "  sudo apt update && sudo apt install openjdk-21-jdk"
        else
          echo "Recommendation (Linux): Install Node.js using apt or dnf:"
          echo "  sudo apt update && sudo apt install nodejs npm"
        fi
        ;;
      MINGW*|MSYS*|CYGWIN*|Windows_NT)
        if [ "$cmd" = "java" ]; then
          echo "Recommendation (Windows): Install JDK 21+ using Chocolatey or winget:"
          echo "  choco install openjdk21"
          echo "  or"
          echo "  winget install Oracle.JDK.21"
        else
          echo "Recommendation (Windows): Install Node.js using Chocolatey or winget:"
          echo "  choco install nodejs-lts"
          echo "  or"
          echo "  winget install OpenJS.NodeJS.LTS"
        fi
        ;;
      *)
        echo "Please install $friendly_name and add it to your PATH."
        ;;
    esac
    echo "========================================================================"
    exit 1
  fi
}

echo "=== Checking Prerequisites ==="
check_command "java" "Java Runtime Environment"
check_command "npm" "Node.js Package Manager (npm)"
echo "Prerequisites OK."
echo ""

# Resolve the root directory of the project
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== 1. Setting up Java Environment ==="
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

echo "=== 2. Setting up Frontend ==="
cd "$SCRIPT_DIR/desktop"
if [ ! -d "node_modules" ]; then
  echo "node_modules not found in desktop directory, installing dependencies..."
  npm install
fi

echo "=== 3. Starting Desktop Application ==="
# Runs Electron dev server (without spawning the Java backend)
NO_CONTROL_PLANE=true npm run electron:dev
