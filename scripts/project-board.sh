#!/usr/bin/env bash

# Get repository root
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$(dirname "$DIR")"

PORT=8010

echo "📊 Refreshing static sprint status..."
python3 "/Users/martinfou/.gemini/config/skills/bmad-sprint-status-ui/scripts/render_sprint_status.py"

# Check if server is already running on the port
if lsof -i tcp:$PORT | grep -q "LISTEN"; then
    echo "🌐 BMad Project Board server is already running on http://localhost:$PORT"
    echo "Opening browser..."
    open "http://localhost:$PORT"
    exit 0
fi

echo "🚀 Starting BMad Project Board server..."
python3 "/Users/martinfou/.gemini/config/skills/bmad-sprint-status-ui/scripts/project_board_server.py" --project-root "$REPO_ROOT" &
SERVER_PID=$!

# Trap SIGINT (Ctrl+C) and SIGTERM to terminate the python server cleanly
cleanup() {
    echo -e "\n🛑 Stopping BMad Project Board server (PID: $SERVER_PID)..."
    kill $SERVER_PID
    wait $SERVER_PID 2>/dev/null
    echo "Done."
    exit 0
}
trap cleanup SIGINT SIGTERM

# Wait 1 second for the server to spin up, then open browser
sleep 1
echo "🌐 Opening project board in browser: http://localhost:$PORT"
open "http://localhost:$PORT"

# Keep the script running to hold the terminal open and monitor process
wait $SERVER_PID
