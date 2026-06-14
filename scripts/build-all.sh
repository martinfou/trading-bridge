#!/bin/bash
# 🏗️ Build All — Trading Bridge
# Usage: ./scripts/build-all.sh
# Compiles the full Java project and the Desktop Electron app.

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

CYAN='\033[0;36m'
GREEN='\033[0;32m'
NC='\033[0m'

echo -e "${CYAN}═══════════════════════════════════════════${NC}"
echo -e "${CYAN}  ☕ Compiling Java Backend (Maven)${NC}"
echo -e "${CYAN}═══════════════════════════════════════════${NC}"
mvn clean install -DskipTests
echo -e "${GREEN}✅ Java Backend Compiled Successfully${NC}"
echo ""

echo -e "${CYAN}═══════════════════════════════════════════${NC}"
echo -e "${CYAN}  🖥️ Compiling Desktop App (Node/Vite)${NC}"
echo -e "${CYAN}═══════════════════════════════════════════${NC}"
cd desktop
npm install
npm run build
echo -e "${GREEN}✅ Desktop App Compiled Successfully${NC}"
echo ""

echo -e "${GREEN}🎉 Full Project Compilation Complete!${NC}"
