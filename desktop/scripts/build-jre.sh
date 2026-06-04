#!/bin/bash
# Build a minimal JRE via jlink for the Trading Bridge control plane.
#
# Usage:
#   bash scripts/build-jre.sh <fat-jar-path> <output-dir>
#
# Example:
#   bash scripts/build-jre.sh ../trading-runtime/target/*-shaded.jar ./desktop-resources
#
# Requires: JDK 21+ on PATH or JAVA_HOME

set -euo pipefail

FAT_JAR="${1:?Usage: build-jre.sh <fat-jar-path> <output-dir>}"
OUTPUT_DIR="${2:?Usage: build-jre.sh <fat-jar-path> <output-dir>}"

# Detect JAVA_HOME: try mise first (local dev), then env var, then PATH fallback
if command -v mise &>/dev/null && MISE_JAVA="$(mise which java 2>/dev/null)" && [ -n "$MISE_JAVA" ]; then
  JAVA_HOME="$(dirname "$(dirname "$MISE_JAVA")")"
elif [ -n "${JAVA_HOME:-}" ] && [ "$JAVA_HOME" != "/" ] && [ -f "$JAVA_HOME/bin/jlink" ]; then
  : # JAVA_HOME already valid
else
  JAVA_BIN="$(command -v java)"
  if [ -n "$JAVA_BIN" ]; then
    JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
  fi
fi
JLINK="$JAVA_HOME/bin/jlink"
JDEPS="$JAVA_HOME/bin/jdeps"

if [ ! -x "$JLINK" ]; then
  echo "ERROR: jlink not found at $JLINK. Set JAVA_HOME or add JDK to PATH." >&2
  exit 1
fi

echo "==> Detecting module dependencies from $FAT_JAR ..."
MODULES=$("$JDEPS" --ignore-missing-deps --print-module-deps "$FAT_JAR" 2>/dev/null)
echo "    Modules: $MODULES"

echo "==> Building minimal JRE at $OUTPUT_DIR/jre ..."
"$JLINK" \
  --add-modules "$MODULES" \
  --output "$OUTPUT_DIR/jre" \
  --no-header-files \
  --no-man-pages \
  --strip-debug \
  --compress=2 \
  --vm=server

echo "==> Verifying JRE ..."
"$OUTPUT_DIR/jre/bin/java" --version

echo "==> JRE size:"
du -sh "$OUTPUT_DIR/jre"

echo "==> Done. JRE ready at $OUTPUT_DIR/jre"
