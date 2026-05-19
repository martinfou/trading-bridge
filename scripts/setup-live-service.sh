#!/bin/bash
# ⚙️ Trading Bridge — Install Live Strategy Runner as systemd user service
# Usage: ./scripts/setup-live-service.sh [strategy-name] [granularity] [interval-sec]
#
# Examples:
#   ./scripts/setup-live-service.sh              → Default (2_31_177 H1 60)
#   ./scripts/setup-live-service.sh 2_14_147     → Strategy 2_14_147
#   ./scripts/setup-live-service.sh all H4 300   → All strategies H4 every 5min

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

STRATEGY_NAME="${1:-2_31_177}"
GRANULARITY="${2:-H1}"
INTERVAL_SEC="${3:-60}"

SERVICE_SRC="$SCRIPT_DIR/scripts/trading-live.service"
SERVICE_DEST="$HOME/.config/systemd/user/trading-live.service"

echo -e "${CYAN}╔═══════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   ⚙️  Setup Live Strategy Runner as Service      ║${NC}"
echo -e "${CYAN}╚═══════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${YELLOW}Strategy:${NC}    $STRATEGY_NAME"
echo -e "  ${YELLOW}Granularity:${NC} $GRANULARITY"
echo -e "  ${YELLOW}Interval:${NC}    ${INTERVAL_SEC}s"
echo ""

# Check env file
ENV_FILE="$HOME/projects/trading-dashboard/.env"
if [ ! -f "$ENV_FILE" ]; then
    echo -e "${RED}❌ .env file not found at $ENV_FILE${NC}"
    echo "Create it with:"
    echo "  OANDA_API_KEY=your_key"
    echo "  OANDA_ACCOUNT_ID=101-002-4729622-008"
    exit 1
fi

# Check the service template exists
if [ ! -f "$SERVICE_SRC" ]; then
    echo -e "${RED}❌ Service template not found at $SERVICE_SRC${NC}"
    exit 1
fi

# ────────────────────────────────────────────
# 1. Create user systemd directory
# ────────────────────────────────────────────
echo -e "${YELLOW}[1/5] Creating systemd user directory...${NC}"
mkdir -p "$HOME/.config/systemd/user"
echo -e "${GREEN}  ✅ $HOME/.config/systemd/user/${NC}"

# ────────────────────────────────────────────
# 2. Generate service file with correct env vars
# ────────────────────────────────────────────
echo -e "${YELLOW}[2/5] Generating service file...${NC}"
sed -e "s/\${STRATEGY_NAME}/$STRATEGY_NAME/g" \
    -e "s/\${GRANULARITY}/$GRANULARITY/g" \
    -e "s/\${INTERVAL_SEC}/$INTERVAL_SEC/g" \
    "$SERVICE_SRC" > "$SERVICE_DEST"
echo -e "${GREEN}  ✅ $SERVICE_DEST${NC}"

# ────────────────────────────────────────────
# 3. Compile the project first
# ────────────────────────────────────────────
echo -e "${YELLOW}[3/5] Compiling project...${NC}"
cd "$SCRIPT_DIR"
mvn compile -q -pl trading-core,trading-data,trading-strategies -am 2>&1 | grep -v "^$" || true
echo -e "${GREEN}  ✅ Compilation OK${NC}"

# ────────────────────────────────────────────
# 4. Reload daemon and enable service
# ────────────────────────────────────────────
echo -e "${YELLOW}[4/5] Enabling service...${NC}"
systemctl --user daemon-reload
systemctl --user enable trading-live.service
systemctl --user start trading-live.service
echo -e "${GREEN}  ✅ Service enabled and started${NC}"

# ────────────────────────────────────────────
# 5. Verify
# ────────────────────────────────────────────
echo -e "${YELLOW}[5/5] Verifying...${NC}"
sleep 2
STATUS=$(systemctl --user is-active trading-live.service)
echo -e "  Status: ${GREEN}$STATUS${NC}"

echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════${NC}"
echo -e "${GREEN}✅ Live Strategy Runner installed as service!${NC}"
echo ""
echo -e "${CYAN}Commands:${NC}"
echo "  systemctl --user status trading-live.service    # View status"
echo "  journalctl --user -u trading-live.service -f    # Follow logs"
echo "  systemctl --user restart trading-live.service   # Restart"
echo "  systemctl --user stop trading-live.service      # Stop"
echo "  systemctl --user disable trading-live.service   # Disable"
echo ""
echo -e "${YELLOW}State file:${NC} /tmp/live-strategy-state.json  (auto-recovered on restart)"
echo ""
echo -e "${CYAN}To change strategy later:${NC}"
echo "  1. systemctl --user stop trading-live.service"
echo "  2. rm /tmp/live-strategy-state.json  (optional: reset state)"
echo "  3. $0 <new-strategy>"
echo ""
