#!/bin/bash
set -e

echo "Starting Xvfb on display ${DISPLAY}..."
Xvfb :1 -screen 0 1024x768x16 &
sleep 2

echo "Initializing IB Gateway Headless..."
echo "Mode: ${IBKR_TRADING_MODE:-paper} | Port: ${IBKR_PORT:-4002}"

# Keep container alive and stream supervisor logs
exec tail -f /dev/null
